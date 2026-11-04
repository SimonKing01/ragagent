import assert from 'node:assert/strict'
import { test } from 'node:test'

import {
  EMBED_MESSAGES,
  normalizeEmbedLocale,
  SUPPORTED_LOCALES,
} from './embed.ts'
import zhCNBundle from './locales/embed/zh-CN.ts'
import zhHK from './locales/embed/zh-HK.ts'
import enUS from './locales/embed/en-US.ts'
import ruRU from './locales/embed/ru-RU.ts'
import esES from './locales/embed/es-ES.ts'
import ptBR from './locales/embed/pt-BR.ts'
import thTH from './locales/embed/th-TH.ts'

/** 访客侧语言包：一个语言一个文件，键集合必须与 zh-CN 完全一致（缺键会静默回落英文）。 */
const EMBED_LOCALE_FILES = {
  'zh-CN': zhCNBundle,
  'zh-HK': zhHK,
  'en-US': enUS,
  'ru-RU': ruRU,
  'es-ES': esES,
  'pt-BR': ptBR,
  'th-TH': thTH,
} as const

function collectLeafKeys(tree: unknown, prefix = '', out = new Set<string>()): Set<string> {
  for (const [key, value] of Object.entries((tree || {}) as Record<string, unknown>)) {
    const path = prefix ? `${prefix}.${key}` : key
    if (value && typeof value === 'object' && !Array.isArray(value)) collectLeafKeys(value, path, out)
    else out.add(path)
  }
  return out
}

const EXPECTED_CONVERSATION_TIME_KEYS = [
  'today',
  'yesterday',
  'thisYear',
  'otherYear',
] as const

const EXPECTED_REFERENCES_DRAWER_KEYS = [
  'referencesDrawerTitle',
  'referencesDrawerTitleWeb',
  'referencesDrawerTitleDocs',
  'referencesDrawerTitleTools',
  'referencesDrawerTitleMixed',
  'referencesDrawerWebSection',
  'referencesDrawerDocsSection',
  'referencesDrawerToolsSection',
  'referencesDrawerEmpty',
] as const

test('supported embed locales match the registered bundles', () => {
  assert.deepEqual(
    [...SUPPORTED_LOCALES].sort(),
    ['en-US', 'es-ES', 'pt-BR', 'ru-RU', 'th-TH', 'zh-CN', 'zh-HK'].sort(),
  )
})

test('embed 语言包文件（一语言一文件）键集合与 zh-CN 完全一致（缺键/多键都红）', () => {
  const reference = collectLeafKeys(EMBED_MESSAGES['zh-CN'])
  const failures: string[] = []
  for (const [locale, bundle] of Object.entries(EMBED_LOCALE_FILES)) {
    const keys = collectLeafKeys(bundle)
    for (const key of reference) if (!keys.has(key)) failures.push(`${locale}: missing ${key}`)
    for (const key of keys) if (!reference.has(key)) failures.push(`${locale}: extra ${key}`)
  }
  assert.deepEqual(failures, [], failures.slice(0, 20).join('\n'))
})

test('every supported locale defines conversationTime and referencesDrawer in chat', () => {
  for (const locale of SUPPORTED_LOCALES) {
    const bundle = EMBED_MESSAGES[locale] as {
      chat?: Record<string, unknown>
      common?: Record<string, unknown>
    }
    assert.ok(bundle, `Locale bundle for ${locale} must exist`)
    assert.ok(bundle.chat, `Locale bundle for ${locale} must have chat section`)

    // conversationTime checks
    const conversationTime = bundle.chat.conversationTime as Record<string, string> | undefined
    assert.ok(
      conversationTime && typeof conversationTime === 'object',
      `Locale ${locale} is missing chat.conversationTime`,
    )
    for (const key of EXPECTED_CONVERSATION_TIME_KEYS) {
      assert.equal(
        typeof conversationTime[key],
        'string',
        `Locale ${locale} is missing chat.conversationTime.${key}`,
      )
      assert.ok(
        conversationTime[key].trim().length > 0,
        `chat.conversationTime.${key} in ${locale} should not be empty`,
      )
      assert.ok(
        conversationTime[key].includes('{time}'),
        `chat.conversationTime.${key} in ${locale} must include {time} placeholder`,
      )
    }

    // referencesDrawer checks
    const chatBag = bundle.chat as Record<string, unknown>
    for (const key of EXPECTED_REFERENCES_DRAWER_KEYS) {
      const val: unknown = chatBag[key]
      assert.equal(
        typeof val,
        'string',
        `Locale ${locale} is missing chat.${key}`,
      )
      assert.ok(
        (val as string).trim().length > 0,
        `chat.${key} in ${locale} should not be empty`,
      )
    }

    // common.close check
    assert.ok(bundle.common, `Locale bundle for ${locale} must have common section`)
    assert.equal(
      typeof bundle.common.close,
      'string',
      `Locale ${locale} is missing common.close`,
    )
    assert.ok(
      (bundle.common.close as string).trim().length > 0,
      `common.close in ${locale} should not be empty`,
    )
  }
})

test('locale-specific translations match expected strings for missing keys', () => {
  // zh-CN
  const zhChat = EMBED_MESSAGES['zh-CN'].chat as Record<string, any>
  const zhCommon = EMBED_MESSAGES['zh-CN'].common as Record<string, any>
  assert.equal(zhChat.conversationTime.today, '今天 {time}')
  assert.equal(zhChat.conversationTime.yesterday, '昨天 {time}')
  assert.equal(zhChat.conversationTime.thisYear, '{month}月{day}日 {time}')
  assert.equal(zhChat.conversationTime.otherYear, '{year}年{month}月{day}日 {time}')
  assert.equal(zhChat.referencesDrawerTitleDocs, '文档来源')
  assert.equal(zhChat.referencesDrawerEmpty, '暂无参考来源')
  assert.equal(zhCommon.close, '关闭')

  // en-US
  const enChat = EMBED_MESSAGES['en-US'].chat as Record<string, any>
  const enCommon = EMBED_MESSAGES['en-US'].common as Record<string, any>
  assert.equal(enChat.conversationTime.today, 'Today {time}')
  assert.equal(enChat.conversationTime.yesterday, 'Yesterday {time}')
  assert.equal(enChat.conversationTime.thisYear, '{month}/{day} {time}')
  assert.equal(enChat.conversationTime.otherYear, '{month}/{day}/{year} {time}')
  assert.equal(enChat.referencesDrawerTitleDocs, 'Document sources')
  assert.equal(enChat.referencesDrawerEmpty, 'No sources available')
  assert.equal(enCommon.close, 'Close')

  // ru-RU
  const ruChat = EMBED_MESSAGES['ru-RU'].chat as Record<string, any>
  const ruCommon = EMBED_MESSAGES['ru-RU'].common as Record<string, any>
  assert.equal(ruChat.conversationTime.today, 'Сегодня {time}')
  assert.equal(ruChat.conversationTime.yesterday, 'Вчера {time}')
  assert.equal(ruChat.conversationTime.thisYear, '{day}.{month} {time}')
  assert.equal(ruChat.conversationTime.otherYear, '{day}.{month}.{year} {time}')
  assert.equal(ruChat.referencesDrawerTitleDocs, 'Документы')
  assert.equal(ruChat.referencesDrawerEmpty, 'Источники отсутствуют')
  assert.equal(ruCommon.close, 'Закрыть')
})

test('normalizeEmbedLocale maps tags accurately with fallback to zh-CN', () => {
  assert.equal(normalizeEmbedLocale('zh-CN'), 'zh-CN')
  assert.equal(normalizeEmbedLocale('zh'), 'zh-CN')
  assert.equal(normalizeEmbedLocale('ZH-cn'), 'zh-CN')
  assert.equal(normalizeEmbedLocale('en-US'), 'en-US')
  assert.equal(normalizeEmbedLocale('en'), 'en-US')
  assert.equal(normalizeEmbedLocale('EN-gb'), 'en-US')
  // 已下线的语言回落到兜底 zh-CN
  assert.equal(normalizeEmbedLocale('ja-JP'), 'zh-CN')
  assert.equal(normalizeEmbedLocale('ko'), 'zh-CN')
  assert.equal(normalizeEmbedLocale('ru-RU'), 'ru-RU')
  assert.equal(normalizeEmbedLocale('ru'), 'ru-RU')
  assert.equal(normalizeEmbedLocale('unknown-locale'), 'zh-CN')
  assert.equal(normalizeEmbedLocale('   '), 'zh-CN')
})
