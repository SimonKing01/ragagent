/** 两个应用各自的 i18n 实例类型不同（语言包键集合不同），这里只约束用到的部分。 */
type I18nLike = {
  global: {
    t: (key: string, params?: Record<string, unknown>) => string
    locale: unknown
  }
}

/**
 * 非组件模块取文案的间接层。
 *
 * 之前这些工具（剪贴板、markdown 增强、mermaid 查看器、请求拦截器…）直接
 * `import i18n from '@/i18n'`，而 `@/i18n` 会静态引入平台全部 9 种语言
 * （源文件合计 2 MB+）。嵌入页只用到其中几条文案，却因此把整包语言资源
 * 带进首屏（生产约 700 KB gzip）。
 *
 * 改为由入口注册各自实例：`main.ts` 注册平台 i18n，`embed-main.ts` 注册嵌入 i18n，
 * 共享工具只依赖这个 2 KB 的访问器。
 */

type TranslateParams = Record<string, unknown>

let activeI18n: I18nLike | null = null

/** 由应用入口注册当前 i18n 实例（平台 / 嵌入各一次）。 */
export function setActiveI18n(instance: I18nLike): void {
  activeI18n = instance
}

/** 取一条文案；未注册实例时回退为 key 本身（不会抛错）。 */
export function tText(key: string, params?: TranslateParams): string {
  if (!activeI18n) return key
  return params ? activeI18n.global.t(key, params) : activeI18n.global.t(key)
}

/** 当前语言（请求头、下载文件名等场景）；未注册时沿用历史取值顺序。 */
export function activeLocale(): string {
  const locale = activeI18n?.global.locale
  if (typeof locale === 'string') return locale
  const value = (locale as { value?: string } | undefined)?.value
  if (value) return value
  try {
    return localStorage.getItem('locale') || 'zh-CN'
  } catch {
    return 'zh-CN'
  }
}
