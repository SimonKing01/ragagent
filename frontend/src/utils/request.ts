// src/utils/request.js
import axios from "axios";
import { StorageKeys } from './storageKeys'
import { generateRandomString, MAX_FILE_SIZE_MB } from "./index";
import { activeLocale, tText } from '@/utils/activeI18n'
import { getApiBaseUrl } from './api-base';
import {
  forceReloginRedirect,
  isEmbedPage,
  refreshAccessTokenShared,
} from './authRefresh';
import {
  hasEmbedTokenRefresher,
  refreshEmbedTokenShared,
} from './embedTokenRefresh';

export { forceReloginRedirect, refreshAccessTokenShared };

const t = (key: string) => tText(key)

// API基础URL
const BASE_URL = getApiBaseUrl();

/**
 * Response payload augmented with the HTTP status code.
 *
 * `$httpStatus` lets callers distinguish outcomes that share a success shape.
 * Defined as a non-enumerable property, so it stays invisible to object spread,
 * JSON.stringify and Object.keys and never leaks into downstream payloads.
 *
 * Guaranteed only for JSON responses (objects/arrays). Blob, string and SSE
 * stream responses do not carry it at runtime, so only read `$httpStatus`
 * when the payload is known to be an object.
 */
export type WithStatus<T> = T & {
  /** HTTP status code of the response. Non-enumerable. See {@link WithStatus}. */
  readonly $httpStatus: number
};

const HTTP_STATUS_KEY = '$httpStatus';

/**
 * Attach the non-enumerable `$httpStatus` property to a response payload
 * in place and return it. Primitives pass through untouched.
 * See {@link WithStatus} for where the property is guaranteed.
 */
function withHttpStatus<T>(data: T, status: number): T {
  if (data !== null && typeof data === 'object') {
    Object.defineProperty(data, HTTP_STATUS_KEY, {
      value: status,
      enumerable: false,
      configurable: true,
      writable: false,
    });
  }
  return data;
}

// 创建Axios实例
const instance = axios.create({
  baseURL: BASE_URL, // 使用配置的API基础URL
  timeout: 30000, // 请求超时时间
  headers: {
    "Content-Type": "application/json",
    "X-Request-ID": `${generateRandomString(12)}`,
  },
});

// 获取当前用户语言（用于 Accept-Language header）
export function getCurrentLanguage(): string {
  return activeLocale()
}


instance.interceptors.request.use(
  (config) => {
    const existingAuth = config.headers?.Authorization ?? config.headers?.authorization;
    const isEmbedAuth = typeof existingAuth === 'string' && existingAuth.startsWith('Embed ');
    const isEmbedPath = typeof config.url === 'string' && config.url.includes('/api/v1/embed/');

    // 嵌入渠道使用 Embed token；勿用本地 JWT 覆盖（否则调试页会 401）
    if (!isEmbedAuth) {
      const token = localStorage.getItem(StorageKeys.token);
      if (token) {
        config.headers["Authorization"] = `Bearer ${token}`;
      }
    }
    
    // 添加用户语言偏好
    config.headers["Accept-Language"] = getCurrentLanguage();
    
    // 添加跨空间访问请求头：只要 setSelectedTenant 写过激活空间，
    // 每个请求都要附 X-Tenant-ID。早期版本会 short-circuit
    // "selectedTenantId === defaultTenantId 时不附"以减少 header 体积，
    // 但这条优化会被任何把 ragagent_tenant 写成激活空间的代码（OIDC
    // 回调、UserMenu loadUserInfo、router hydrate）触发，导致后续请求
    // 静默丢失 header，前端"切换了"但实际仍跑在 home 空间里——把"切
    // 换之后只有第一批请求带 X-Tenant-ID"调成永久状态。
    // 后端 IsTenantAccessible 已经允许 header 指向 home 空间（自家），
    // 所以无脑附不会引入新风险。
    if (!isEmbedAuth && !isEmbedPath) {
      const selectedTenantId = localStorage.getItem(StorageKeys.selectedTenantIdSnake);
      if (selectedTenantId) {
        config.headers["X-Tenant-ID"] = selectedTenantId;
      }
    }
    
    config.headers["X-Request-ID"] = `${generateRandomString(12)}`;
    return config;
  },
  (error) => {
    return Promise.reject(error);
  }
);

// Share-link endpoints (/auth/invitations/lookup, /auth/register-by-invite)
// are reachable by anonymous users opening an invite link. A 401 from these
// must surface to the page (e.g. expired token), not trigger the
// refresh-then-redirect-to-login flow (issue #1617). '/auth/register' already
// covers '/auth/register-by-invite' via substring match.
const PUBLIC_AUTH_PATHS = ['/auth/auto-setup', '/auth/login', '/auth/register', '/auth/oidc/', '/auth/invitations/lookup', '/api/v1/embed/'];

function isPublicAuthRequest(url?: string): boolean {
  if (!url) return false;
  return PUBLIC_AUTH_PATHS.some(p => url.includes(p));
}

/**
 * 统一响应外壳判定：后端 `@ApiResult` 路由的成功体恒为
 * `{ code: 0, message: "ok", data: … }`（约定见 docs/api-response-convention.md）。
 *
 * 只认**恰好由这三个键构成**的对象（code 为数字）—— 避免把业务载荷里恰好叫
 * code/data 的对象误判成外壳。后端的 ApiResponse 若扩展键集，此处同步扩展。
 */
function isApiEnvelope(d: any): d is { code: number; message?: string; data: any } {
  if (!d || typeof d !== 'object' || Array.isArray(d)) return false;
  return typeof d.code === 'number'
    && 'data' in d
    && Object.keys(d).every((k) => k === 'code' || k === 'message' || k === 'data');
}

/**
 * 裸 fetch 的解包入口：把服务端响应还原成**唯一解包点**产出的形状。
 *
 * <p>为什么需要它：本文件的响应拦截器是**全前端唯一解包点**（2xx 时把 `{code,message,data}`
 * 拆成 `data`）。但有些场景必须绕过 axios 直接 `fetch` —— FormData 上传、SSE 流、二进制下载、
 * 独立发布物（widget，第三方页面不能带依赖）、以及刻意模拟外部调用方的"请求演练"。
 * 这些调用点**必须自己补解包**，否则拿到的是外壳：读 `.id` / `.token` 之类恒 `undefined`，
 * 而且**不报错**，只是"没数据"—— 排查成本极高。</p>
 *
 * <p>本仓已因此踩过三次（同型）：widget 浮标图标读不到（B226）、API 集成页请求演练必然
 * 卡在第一步（B227）、initialization 多模态联调潜伏面。故成对提供本函数 + 源码扫描守卫
 * （`src/utils/rawFetchEnvelope.test.ts`）：**新增裸 fetch 若不调用它、也没有豁免标记，测试当场变红**。</p>
 *
 * <p>形状判定复用拦截器的 {@link isApiEnvelope}（键集**精确**匹配 code/message/data），
 * 因此业务载荷自带的 `data` 字段不会被误当信封 —— 这也正是裸 fetch 处不要手写
 * `payload.data ?? payload` 的原因（那个写法会误伤业务字段）。</p>
 */
export function unwrapEnvelope<T = any>(payload: any): T {
  return isApiEnvelope(payload) ? (payload.data as T) : (payload as T);
}

instance.interceptors.response.use(
  (response) => {
    // 根据业务状态码处理逻辑
    const { status, data } = response;
    if (status >= 200 && status < 300) {
      // 统一外壳 → 就地解包成 data（全前端唯一解包点）；2xx 但 code!=0 也按失败处理
      if (isApiEnvelope(data)) {
        if (data.code !== 0) {
          return Promise.reject(withHttpStatus(
            { status, message: data.message, code: data.code, data: data.data }, status));
        }
        return withHttpStatus(data.data, status);
      }
      return withHttpStatus(data, status);
    } else {
      return Promise.reject(withHttpStatus(data, status));
    }
  },
  async (error: any) => {
    const originalRequest = error.config;
    
    if (!error.response) {
      return Promise.reject({ message: t('error.networkError') });
    }
    
    // 公开接口（auto-setup / login / register / oidc）的 401 不走 JWT refresh 逻辑，
    // 直接返回错误——但嵌入会话票（ems_，30 分钟 TTL）过期要先换票 + 原请求重放一次。
    if ((error.response.status === 401 || error.response.status === 403) && isPublicAuthRequest(originalRequest?.url)) {
      const { status, data } = error.response;
      const msg = typeof data === 'object'
        ? (typeof data?.error === 'string' ? data.error : (data?.error?.message || data?.message))
        : data;
      const embedAuth = String(originalRequest?.headers?.Authorization ?? originalRequest?.headers?.authorization ?? '');
      // /exchange 是换票动作本身，排除它避免与单飞的换票调用互等。
      const canRetryEmbed = status === 401
        && embedAuth.startsWith('Embed ')
        && !originalRequest._embedRetry
        && !String(originalRequest?.url || '').includes('/exchange')
        && hasEmbedTokenRefresher();
      if (canRetryEmbed) {
        originalRequest._embedRetry = true;
        const fresh = await refreshEmbedTokenShared();
        if (fresh) {
          originalRequest.headers['Authorization'] = 'Embed ' + fresh;
          return instance(originalRequest);
        }
      }
      return Promise.reject(withHttpStatus({ status, message: msg || t('error.invalidCredentials') }, status));
    }

    // Embed 调试页/挂件：无 JWT 时直接拒绝，勿走 refresh → /login
    if (error.response.status === 401 && isEmbedPage()) {
      const { status, data } = error.response;
      const msg = typeof data === 'object'
        ? (typeof data?.error === 'string' ? data.error : (data?.error?.message || data?.message))
        : data;
      return Promise.reject(withHttpStatus({ status, message: msg || t('error.invalidCredentials') }, status));
    }

    // 如果是401错误且不是刷新token的请求，尝试刷新token
    if (error.response.status === 401 && !originalRequest._retry && !originalRequest.url?.includes('/auth/refresh')) {
      originalRequest._retry = true;
      try {
        const token = await refreshAccessTokenShared({
          messages: {
            pleaseRelogin: t('error.pleaseRelogin'),
            tokenRefreshFailed: t('error.tokenRefreshFailed'),
          },
        });
        originalRequest.headers['Authorization'] = 'Bearer ' + token;
        return instance(originalRequest);
      } catch (refreshError) {
        // refreshAccessTokenShared already cleared credentials and redirected.
        return Promise.reject(refreshError);
      }
    }
    
    // 处理 Nginx 413 Request Entity Too Large
    const ERR_ENTITY_TOO_LARGE = 413;
    if (error.response.status === ERR_ENTITY_TOO_LARGE) {
      return Promise.reject(withHttpStatus({
        status: ERR_ENTITY_TOO_LARGE,
        message: tText('error.fileSizeExceeded', { size: MAX_FILE_SIZE_MB }),
        success: false
      }, ERR_ENTITY_TOO_LARGE));
    }

    const { status, data } = error.response;
    // 将HTTP状态码一并抛出，方便上层判断401等场景
    // 后端错误体两形态并存（迁移期，见 docs/api-response-convention.md）：
    //   - @ApiResult 路由：{ code, message, data }（data 承载 details）
    //   - 其余路由：{ error: { code, message, details } } 或 { error: "…" }
    // 这里按形态提取顶层 message；其余字段靠下面的 ...data 摊平
    // ⇒ 消费端 err.message 恒可用，err.code / err.error?.code 按后端形态各自可读
    let errorMessage: string | undefined;
    if (typeof data === 'object') {
      if (typeof data?.error === 'string') {
        errorMessage = data.error;
      } else if (data?.error?.message) {
        errorMessage = data.error.message;
      } else {
        errorMessage = data?.message;
      }
    } else if (typeof data === 'string') {
      errorMessage = data;
    }
    return Promise.reject(withHttpStatus({
      status,
      message: errorMessage,
      ...(typeof data === 'object' ? data : {}) 
    }, status));
  }
);

export function get<T = any>(url: string, config?: any): Promise<WithStatus<T>> {
  return instance.get<T>(url, config) as unknown as Promise<WithStatus<T>>;
}

export async function getDown(url: string): Promise<Blob> {
  const res = await instance.get<Blob>(url, {
    responseType: "blob",
  }) as unknown as Blob;
  return res
}

export function postUpload(
  url: string,
  data = {},
  onUploadProgress?: (progressEvent: any) => void,
  config: any = {},
): Promise<WithStatus<any>> {
  return instance.post(url, data, {
    ...config,
    headers: {
      "Content-Type": "multipart/form-data",
      "X-Request-ID": `${generateRandomString(12)}`,
      ...(config.headers || {}),
    },
    onUploadProgress: onUploadProgress || config.onUploadProgress,
  }) as unknown as Promise<any>;
}

export function postChat<T = any>(url: string, data = {}): Promise<T> {
  // SSE stream: body is a string, so no `$httpStatus` is attached (see WithStatus).
  return instance.post(url, data, {
    headers: {
      "Content-Type": "text/event-stream;charset=utf-8",
      "X-Request-ID": `${generateRandomString(12)}`,
    },
  }) as unknown as Promise<T>;
}

export function post<T = any>(url: string, data = {}, config?: any): Promise<WithStatus<T>> {
  return instance.post<T>(url, data, config) as unknown as Promise<WithStatus<T>>;
}

export function put<T = any>(url: string, data = {}, config?: any): Promise<WithStatus<T>> {
  return instance.put<T>(url, data, config) as unknown as Promise<WithStatus<T>>;
}

export function patch<T = any>(url: string, data = {}, config?: any): Promise<WithStatus<T>> {
  return instance.patch<T>(url, data, config) as unknown as Promise<WithStatus<T>>;
}

export function del<T = any>(url: string, data?: any, config?: any): Promise<WithStatus<T>> {
  return instance.delete<T>(url, { data, ...config }) as unknown as Promise<WithStatus<T>>;
}
