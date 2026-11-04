import { createI18n } from 'vue-i18n'

// 访客侧（浮标 + iframe 聊天）语言包：`locales/embed/` 下一个语言一个文件，全量手写维护。
// 键集合必须与 zh-CN 包完全一致（embedLocale.test.ts 有守卫）。
import zhCN from './locales/embed/zh-CN.ts'
import zhHK from './locales/embed/zh-HK.ts'
import enUS from './locales/embed/en-US.ts'
import ruRU from './locales/embed/ru-RU.ts'
import esES from './locales/embed/es-ES.ts'
import ptBR from './locales/embed/pt-BR.ts'
import thTH from './locales/embed/th-TH.ts'

export const SUPPORTED_LOCALES = [
  'zh-CN',
  'zh-HK',
  'en-US',
  'ru-RU',
  'es-ES',
  'pt-BR',
  'th-TH',
] as const
export type EmbedLocale = (typeof SUPPORTED_LOCALES)[number]

/** Isolated from the main app `locale` key so embed preview never hijacks admin UI language. */
export const EMBED_LOCALE_STORAGE_KEY = 'ragagent-embed-locale'

/**
 * 严格归一化：识别不了就返回 null。
 * 用于「宿主声明」这类**可忽略**的信号——宿主页面写 `lang="de"` 时应回落浏览器语言，
 * 而不是被 `normalizeEmbedLocale` 的兜底（zh-CN）强行改语言。
 */
export function matchEmbedLocale(raw: string): EmbedLocale | null {
  const s = raw.trim().toLowerCase()
  if (!s) return null
  if (s.startsWith('en')) return 'en-US'
  if (s.startsWith('ru')) return 'ru-RU'
  if (s.startsWith('es')) return 'es-ES'
  if (s.startsWith('pt')) return 'pt-BR'
  if (s.startsWith('th')) return 'th-TH'
  // 中文：港澳台/繁体归 zh-HK（繁体），其余简中归 zh-CN。
  if (s.startsWith('zh')) {
    return s.includes('hk') || s.includes('tw') || s.includes('mo') || s.includes('hant')
      ? 'zh-HK'
      : 'zh-CN'
  }
  return SUPPORTED_LOCALES.find((l) => l.toLowerCase() === s) || null
}

/** Map host-provided locale strings to a supported embed locale tag（认不出 → zh-CN）。 */
export function normalizeEmbedLocale(raw: string): EmbedLocale {
  return matchEmbedLocale(raw) || 'zh-CN'
}

export function readEmbedLocaleFromUrl(): string {
  if (typeof window === 'undefined') return ''
  return new URLSearchParams(window.location.search).get('locale')?.trim() || ''
}

/**
 * 宿主**页面**声明的语言（widget 脚本读宿主页 `<html lang>` 后经 `?hostLocale=` 传入）。
 * 与 `?locale=` 的区别：这是弱信号——渠道默认语言优先于它，它只优先于浏览器语言。
 */
export function readHostPageLocaleFromUrl(): string {
  if (typeof window === 'undefined') return ''
  return new URLSearchParams(window.location.search).get('hostLocale')?.trim() || ''
}

/** 浏览器语言 → 受支持的 embed 语言（跟随浏览器时的取值来源）。 */
export function resolveBrowserEmbedLocale(): EmbedLocale {
  const nav = typeof navigator !== 'undefined' ? navigator.language : ''
  return nav ? normalizeEmbedLocale(nav) : 'zh-CN'
}

/** 清掉持久值——渠道设为「跟随浏览器/宿主」时使用，避免旧值压过浏览器语言。 */
export function clearStoredEmbedLocale() {
  try {
    if (typeof localStorage !== 'undefined') {
      localStorage.removeItem(EMBED_LOCALE_STORAGE_KEY)
    }
  } catch {
    // localStorage may be unavailable in private mode.
  }
}

/** <html lang> 跟随当前语言（读屏/宿主脚本按它判语言）。 */
function syncDocumentLang(next: string) {
  if (typeof document === 'undefined') return
  document.documentElement.setAttribute('lang', next)
}

function resolveInitialEmbedLocale(): EmbedLocale {
  const fromUrl = readEmbedLocaleFromUrl()
  if (fromUrl) return normalizeEmbedLocale(fromUrl)

  try {
    const saved = typeof localStorage !== 'undefined'
      ? localStorage.getItem(EMBED_LOCALE_STORAGE_KEY)
      : null
    if (saved) return normalizeEmbedLocale(saved)
  } catch {
    // localStorage may be unavailable in private mode.
  }

  return resolveBrowserEmbedLocale()
}

const locale = resolveInitialEmbedLocale()
syncDocumentLang(locale)

export const EMBED_MESSAGES = {
  'zh-CN': zhCN,
  'zh-HK': zhHK,
  'en-US': enUS,
  'ru-RU': ruRU,
  'es-ES': esES,
  'pt-BR': ptBR,
  'th-TH': thTH,
} as const

const i18n = createI18n({
  legacy: false,
  locale,
  fallbackLocale: 'en-US',
  globalInjection: true,
  warnHtmlMessage: false,
  messages: EMBED_MESSAGES,
})

type LocaleRef = { value: string }

function setActiveEmbedLocale(raw: string, localeRef: LocaleRef | undefined, persist: boolean) {
  const next = normalizeEmbedLocale(raw)
  if (persist) {
    try {
      localStorage.setItem(EMBED_LOCALE_STORAGE_KEY, next)
    } catch {
      // localStorage may be unavailable in private mode.
    }
  }
  syncDocumentLang(next)
  if (localeRef) {
    localeRef.value = next
  } else {
    i18n.global.locale.value = next
  }
}

/**
 * 显式选择（宿主 `WeKnora.setLocale()`）：**写**持久值，访客下次打开沿用。
 * 只有显式选择才该持久化——派生语言写持久值会让「跟随浏览器/宿主」永久失效。
 */
export function applyEmbedLocale(raw: string, localeRef?: LocaleRef) {
  setActiveEmbedLocale(raw, localeRef, true)
}

/**
 * 派生语言（渠道默认语言 / 浏览器语言 / URL `?locale=`）：只作用于**本次加载**，
 * 不写持久值。这些值每次打开都能重新推导，写进存储反而会覆盖后续的渠道配置。
 */
export function applyDerivedEmbedLocale(raw: string, localeRef?: LocaleRef) {
  setActiveEmbedLocale(raw, localeRef, false)
}

/**
 * Honor `?locale=` on the embed URL（宿主/预览声明，每次加载都会重新给出）——
 * 按派生语言处理，不落持久值，否则预览一次就把语言钉死在访客浏览器里。
 */
export function syncEmbedLocaleFromUrl(localeRef: LocaleRef): boolean {
  const fromUrl = readEmbedLocaleFromUrl()
  if (!fromUrl) return false
  applyDerivedEmbedLocale(fromUrl, localeRef)
  return true
}

export default i18n
