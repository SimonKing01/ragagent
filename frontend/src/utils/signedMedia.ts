/**
 * 播放用签名 URL 的缓存与在途去重。
 *
 * <p>分块渲染与水合是两路并行触发的（80ms 防抖 + 渲染后管线），预览组件也会再要一次；
 * 若不做在途合并，同一个文件会连发 2–3 次 `/files/signed-url`。
 * 这里按「知识库 + 引用」共享同一个 Promise，成功结果缓存到页面卸载。</p>
 */
export type SignedUrlMinter = (kbId: string, ref: string) => Promise<any>;

/** 默认签发实现：延迟载入，避免单测（node --test）去解析 `@/` 别名。 */
let defaultMinter: SignedUrlMinter | null = null;
async function resolveMinter(): Promise<SignedUrlMinter> {
  if (defaultMinter) {
    return defaultMinter;
  }
  const mod = await import('@/api/knowledge-base/index');
  defaultMinter = mod.getSignedFileUrl as SignedUrlMinter;
  return defaultMinter;
}

const cache = new Map<string, string>();
const inflight = new Map<string, Promise<string>>();

const keyOf = (kbId: string, ref: string) => `${kbId}|${ref}`;

/** 取签名 URL（同一文件全页只请求一次）；失败返回空串，由调用方回落 blob。 */
export function getSignedMediaUrl(
  kbId: string,
  ref: string,
  mint?: SignedUrlMinter,
): Promise<string> {
  if (!kbId || !ref) {
    return Promise.resolve('');
  }
  const key = keyOf(kbId, ref);
  const hit = cache.get(key);
  if (hit) {
    return Promise.resolve(hit);
  }
  const pending = inflight.get(key);
  if (pending) {
    return pending;
  }

  const task = (async (): Promise<string> => {
    try {
      const minter = mint ?? await resolveMinter();
      const res: any = await minter(kbId, ref);
      const url = res?.url || res?.data?.url || '';
      if (url) {
        cache.set(key, url);
      }
      return url;
    } catch {
      return '';
    } finally {
      inflight.delete(key);
    }
  })();
  inflight.set(key, task);
  return task;
}

/** 测试用：清空缓存。 */
export function resetSignedMediaCache(): void {
  cache.clear();
  inflight.clear();
}
