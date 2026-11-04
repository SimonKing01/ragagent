import assert from 'node:assert/strict'
import { test } from 'node:test'

// 语言持久化语义：只有**显式选择**（宿主 set_locale）才写持久值；
// 派生语言（渠道默认 / 浏览器 / URL ?locale=）只作用于本次加载。
// 旧实现把渠道默认语言也写进 localStorage → 渠道改回「跟随浏览器/宿主」后，
// 访客仍读旧值 → 跟随永久失效（已用真机实验复现）。
//
// 注意：模块必须在**没有 document** 时装（否则 @vue/runtime-dom 走浏览器路径、要 createElement）；
// 模块内的 DOM 访问都在调用时判定，所以先 import、后装桩即可。

const KEY = 'ragagent-embed-locale'
const store = new Map<string, string>()
const htmlAttrs: Record<string, string> = {}

function installStubs(search: string, navLang: string) {
  Object.defineProperty(globalThis, 'localStorage', {
    value: {
      getItem: (k: string) => (store.has(k) ? store.get(k)! : null),
      setItem: (k: string, v: string) => void store.set(k, String(v)),
      removeItem: (k: string) => void store.delete(k),
      clear: () => void store.clear(),
    },
    configurable: true,
  })
  Object.defineProperty(globalThis, 'navigator', { value: { language: navLang }, configurable: true })
  Object.defineProperty(globalThis, 'window', {
    value: { location: { search, href: `http://localhost:5173/embed/c1${search}` } },
    configurable: true,
  })
  Object.defineProperty(globalThis, 'document', {
    value: { documentElement: { setAttribute: (k: string, v: string) => void (htmlAttrs[k] = v) } },
    configurable: true,
  })
}

const mod = await import('./embed.ts')

test('派生语言不落持久值；显式选择才落；跟随模式可清除；<html lang> 跟随', () => {
  store.clear()
  for (const k of Object.keys(htmlAttrs)) delete htmlAttrs[k]
  installStubs('?locale=en-US', 'ru-RU')

  const ref = { value: 'zh-CN' }

  // 1) 语言包齐全（模块已按无 DOM 路径完成初始化）
  assert.ok(mod.EMBED_MESSAGES['ru-RU'], '语言包应含 ru-RU')

  // 2) 派生：渠道默认语言 —— 生效但不写持久值
  mod.applyDerivedEmbedLocale('ru-RU', ref)
  assert.equal(ref.value, 'ru-RU')
  assert.equal(store.get(KEY), undefined, '渠道默认语言不得写持久值')
  assert.equal(htmlAttrs['lang'], 'ru-RU', '<html lang> 必须跟随当前语言')

  // 3) 显式：宿主 set_locale —— 写持久值（下次打开沿用）
  mod.applyEmbedLocale('en-US', ref)
  assert.equal(ref.value, 'en-US')
  assert.equal(store.get(KEY), 'en-US', '显式选择应写持久值')
  assert.equal(htmlAttrs['lang'], 'en-US')

  // 4) 跟随模式：清持久值 —— 旧值不得再压过浏览器语言
  mod.clearStoredEmbedLocale()
  assert.equal(store.get(KEY), undefined)

  // 5) 浏览器语言解析 + 归一化
  assert.equal(mod.resolveBrowserEmbedLocale(), 'ru-RU')
  // 繁体中文（港/台/澳）统一归 zh-HK，其余中文归 zh-CN
  assert.equal(mod.normalizeEmbedLocale('zh-TW'), 'zh-HK')
  assert.equal(mod.normalizeEmbedLocale('zh-Hant'), 'zh-HK')
  assert.equal(mod.normalizeEmbedLocale('zh-Hans'), 'zh-CN')
  assert.equal(mod.normalizeEmbedLocale('es-MX'), 'es-ES')
  assert.equal(mod.normalizeEmbedLocale('pt-PT'), 'pt-BR')
  assert.equal(mod.normalizeEmbedLocale('th'), 'th-TH')

  // 6) URL ?locale= 属宿主声明（派生）：生效但不落持久值
  store.clear()
  assert.equal(mod.syncEmbedLocaleFromUrl(ref), true)
  assert.equal(ref.value, 'en-US')
  assert.equal(store.get(KEY), undefined, 'URL locale 不得写持久值')
  assert.equal(htmlAttrs['lang'], 'en-US')
})

test('宿主页面语言：严格归一化（认不出返回 null）+ 从 hostLocale 参数读取', () => {
  installStubs('?hostLocale=zh-CN', 'en-US')

  // 严格归一化：认不出的语言不落到 zh-CN（否则宿主写 lang="de" 会被强行改成中文）
  assert.equal(mod.matchEmbedLocale('zh-CN'), 'zh-CN')
  assert.equal(mod.matchEmbedLocale('zh-Hans'), 'zh-CN')
  assert.equal(mod.matchEmbedLocale('EN-us'), 'en-US')
  assert.equal(mod.matchEmbedLocale('de-DE'), null)
  assert.equal(mod.matchEmbedLocale(''), null)
  // 宽容归一化仍保留旧语义（认不出 → zh-CN）
  assert.equal(mod.normalizeEmbedLocale('de-DE'), 'zh-CN')

  assert.equal(mod.readHostPageLocaleFromUrl(), 'zh-CN')
  installStubs('', 'en-US')
  assert.equal(mod.readHostPageLocaleFromUrl(), '', '无参数时返回空串')
})

test('URL 无 locale 时 syncEmbedLocaleFromUrl 返回 false 且不改语言', () => {
  store.clear()
  installStubs('', 'zh-CN')
  const ref = { value: 'th-TH' }
  assert.equal(mod.syncEmbedLocaleFromUrl(ref), false)
  assert.equal(ref.value, 'th-TH')
  assert.equal(store.get(KEY), undefined)
})
