import { onMounted, onUnmounted, ref, type Ref } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import {
  createEmbedSession,
  embedChatSessionStorageKey,
  exchangeEmbedSession,
  getEmbedConfig,
  getEmbedMessageList,
  getOrCreateEmbedVisitorId,
  isEmbedSessionToken,
  onEmbedHostContext,
  onEmbedHostLocale,
  onEmbedHostToken,
  parseEmbedTokenFromLocation,
  postEmbedBootstrapRequest,
  postEmbedReady,
  type EmbedChannelPublicConfig,
} from '@/api/embed'
import {
  applyDerivedEmbedLocale,
  applyEmbedLocale,
  clearStoredEmbedLocale,
  matchEmbedLocale,
  readEmbedLocaleFromUrl,
  readHostPageLocaleFromUrl,
  resolveBrowserEmbedLocale,
  syncEmbedLocaleFromUrl,
} from '@/i18n/embed'
import { registerEmbedTokenRefresher } from '@/utils/embedTokenRefresh'

// Persist the chat session id per channel so a page refresh resumes the same
// conversation (and its history) instead of silently starting a new session.
interface StoredSession {
  id: string
  sig: string
  /** Agent bound when the session was created; mismatch forces a new session. */
  agentId?: string
}

function readStoredSession(channelId: string): StoredSession | null {
  try {
    const raw = window.localStorage.getItem(embedChatSessionStorageKey(channelId))
    if (!raw) return null
    const parsed = JSON.parse(raw)
    if (parsed && typeof parsed.id === 'string' && typeof parsed.sig === 'string' && parsed.id) {
      const agentId = typeof parsed.agentId === 'string' ? parsed.agentId.trim() : ''
      return {
        id: parsed.id,
        sig: parsed.sig,
        ...(agentId ? { agentId } : {}),
      }
    }
  } catch {
    // Malformed / legacy (plain-string) entry: ignore so a fresh signed
    // session is created below.
  }
  return null
}

function writeStoredSession(channelId: string, session: StoredSession | null) {
  try {
    if (session?.id) {
      window.localStorage.setItem(embedChatSessionStorageKey(channelId), JSON.stringify(session))
    } else {
      window.localStorage.removeItem(embedChatSessionStorageKey(channelId))
    }
  } catch {
    // localStorage may be unavailable (private mode / disabled cookies).
    // Persistence is best-effort; the session still works for this load.
  }
}

// A stored session may have been deleted/expired server-side, or its signed
// handle invalidated by a channel token rotation. Probe it cheaply (limit=1)
// with the stored signature before reusing — the backend rejects stale/foreign
// ids and bad signatures with 4xx, which surfaces here as a thrown error.
async function isStoredSessionValid(
  channelId: string,
  apiToken: string,
  session: StoredSession,
): Promise<boolean> {
  try {
    await getEmbedMessageList(channelId, apiToken, session.id, 1, undefined, session.sig)
    return true
  } catch {
    return false
  }
}

export function useEmbedBridge(channelId: Ref<string>) {
  const { locale: activeLocale, t } = useI18n()
  const route = useRoute()

  const token = ref('')
  const config = ref<EmbedChannelPublicConfig | null>(null)
  const sessionId = ref('')
  const sessionSig = ref('')
  const visitorId = ref('')
  const loadError = ref('')
  const awaitingToken = ref(false)
  const bootstrapping = ref(false)
  const hostContext = ref<Record<string, unknown>>({})

  let removeHostListener: (() => void) | null = null
  let removeLocaleListener: (() => void) | null = null
  let removeTokenListener: (() => void) | null = null
  let bootstrapped = false
  // 宿主推来的发布票（长期有效）：换过会话票后仍要留存——会话票 30 分钟过期时靠它再换。
  let hostPublishToken = ''
  // 正在等待"向宿主重新要票"的回调（换票用，见 waitForHostToken）。
  let pendingHostToken: ((token: string) => void) | null = null
  // 发布票换会话票的单飞：宿主会同时用 iframe load 与 bootstrap_request 各推一次票，
  // 并发换两次会多占一份 Redis 会话票。
  let exchangeCall: { publishToken: string; promise: ReturnType<typeof exchangeEmbedSession> } | null = null
  // 已经换成会话票的发布票：宿主迟到重复推同一张票时不再换。
  let adoptedPublishToken = ''
  let hostLocalePinned = false
  if (typeof window !== 'undefined') {
    hostLocalePinned = Boolean(readEmbedLocaleFromUrl())
    if (hostLocalePinned) {
      syncEmbedLocaleFromUrl(activeLocale)
    }
  }

  /** 同一张发布票的并发换票共享一次请求（调用方各自处理异常语义）。 */
  const exchangeShared = (id: string, publishToken: string) => {
    if (exchangeCall?.publishToken === publishToken) return exchangeCall.promise
    let promise: ReturnType<typeof exchangeEmbedSession>
    promise = exchangeEmbedSession(id, publishToken).finally(() => {
      if (exchangeCall?.promise === promise) exchangeCall = null
    })
    exchangeCall = { publishToken, promise }
    return promise
  }

  const bootstrap = async (embedToken: string) => {
    const id = channelId.value
    if (!id || !embedToken) return
    if (bootstrapped) {
      // 已启动：宿主后续推来的票只做换票/更新，不重跑配置与会话装配
      // —— 安全模式（tokenEndpoint）的自动续期就落在这一支。
      await adoptHostToken(embedToken)
      return
    }
    bootstrapped = true
    awaitingToken.value = false
    bootstrapping.value = true
    token.value = embedToken
    visitorId.value = getOrCreateEmbedVisitorId(id)

    try {
      let apiToken = embedToken
      // 公开配置只校验渠道票（发布票 / 会话票都受理），所以可以和换票并行：
      // 首屏不再等 exchange 回来才发 config，少一次串行往返。
      let configRequest: ReturnType<typeof getEmbedConfig> | null = null
      // Secure mode: the host already handed us a short-lived session token
      // (minted server-side from the publish token). Use it directly — the
      // exchange endpoint only accepts publish tokens and would reject this.
      if (!isEmbedSessionToken(embedToken)) {
        // 留一份发布票：会话票过期时用它再换（不依赖宿主再推一次）。
        hostPublishToken = embedToken
        configRequest = getEmbedConfig(id, embedToken)
        // 换票失败会直接抛，此时没人 await 这个配置请求 —— 先挂空 catch 挡住
        // unhandled rejection（下面 await 的仍是原 promise）。
        configRequest.catch(() => {})
        try {
          const exchangeRes = await exchangeShared(id, embedToken)
          if (exchangeRes?.sessionToken) {
            apiToken = exchangeRes.sessionToken
            adoptedPublishToken = embedToken
          } else if (!import.meta.env.DEV) {
            // Fail closed in production: a missing session token must not silently
            // fall back to the long-lived publish token.
            throw new Error('embed session exchange returned no token')
          }
        } catch (exchangeErr) {
          // In production we refuse to downgrade to the publish token; only the
          // dev build keeps the convenience fallback for local testing.
          if (!import.meta.env.DEV) {
            throw exchangeErr
          }
        }
      }

      // 裸对象（§14.9m E1：无 {data,success} 信封）
      const res = await (configRequest ?? getEmbedConfig(id, apiToken))
      if (!res?.channelId) {
        loadError.value = t('embedPublish.invalidChannel')
        return
      }
      config.value = res

      // 语言优先级：宿主显式声明（URL ?locale= / set_locale）> 渠道默认语言
      // > 宿主页面声明的语言（<html lang>，widget 脚本经 ?hostLocale= 传入）
      // > 浏览器语言。
      // 两条派生路径都**不写持久值**：渠道改了默认语言或被设回「跟随浏览器/宿主」时，
      // 必须立刻对所有人生效；旧实现把渠道默认语言写进 localStorage，之后
      // `resolveInitialEmbedLocale()` 永远先读它 → 「跟随」永久失效。
      if (!hostLocalePinned) {
        if (res.defaultLocale) {
          applyDerivedEmbedLocale(res.defaultLocale, activeLocale)
        } else {
          // 跟随浏览器/宿主：先清掉可能的历史持久值（旧值会压过浏览器语言），
          // 再按宿主页面声明的语言渲染；宿主没声明（或声明了我们不支持的语言）时
          // 用浏览器语言。
          clearStoredEmbedLocale()
          const hostPageLocale = matchEmbedLocale(readHostPageLocaleFromUrl())
          applyDerivedEmbedLocale(hostPageLocale || resolveBrowserEmbedLocale(), activeLocale)
        }
      }

      // 懒创建：只恢复仍有效且仍绑同一 agent 的存量会话；没有（或已失效）就停在
      // "未创建"态——首条消息发送前由 ensureSession 落库，不再打开即建空会话。
      const configAgentId = String(res.agentId || '').trim()
      const stored = readStoredSession(id)
      const agentMatches = !stored?.agentId || !configAgentId || stored.agentId === configAgentId
      if (stored && agentMatches && (await isStoredSessionValid(id, apiToken, stored))) {
        sessionId.value = stored.id
        sessionSig.value = stored.sig
        writeStoredSession(id, { ...stored, agentId: configAgentId || stored.agentId })
      } else {
        writeStoredSession(id, null)
      }
      token.value = apiToken
      postEmbedReady(id)
    } catch (e: unknown) {
      bootstrapped = false
      const msg = String((e as { message?: string })?.message || '')
      if (msg.includes('disabled')) {
        loadError.value = t('embedPublish.channelDisabled')
      } else {
        loadError.value = msg || t('embedPublish.loadError')
      }
    } finally {
      bootstrapping.value = false
    }
  }

  // 宿主再次推票（安全模式自动续期）或我方主动换票都走这里：只更新票，
  // 不重跑配置与会话装配——会话签名绑的是发布票，换它会话不受影响。
  const adoptHostToken = async (provided: string) => {
    const id = channelId.value
    if (!id || !provided) return
    if (isEmbedSessionToken(provided)) {
      hostPublishToken = ''
      token.value = provided
      return
    }
    hostPublishToken = provided
    // 同一张发布票已换过会话票（宿主会推两次）：别再换，也别覆盖当前会话票。
    if (provided === adoptedPublishToken && isEmbedSessionToken(token.value)) return
    try {
      const res = await exchangeShared(id, provided)
      const next = String(res?.sessionToken || '').trim()
      if (next) {
        token.value = next
        adoptedPublishToken = provided
      }
    } catch {
      // 换票失败保持旧票：不打断当前会话，下一次 401 会再触发一轮。
    }
  }

  // 安全模式（宿主 tokenEndpoint）：票由宿主短票通道供给，向宿主重新要一张。
  const waitForHostToken = (timeoutMs = 8000): Promise<string | null> => {
    const id = channelId.value
    if (!id || typeof window === 'undefined' || window.parent === window) {
      return Promise.resolve(null)
    }
    return new Promise((resolve) => {
      let settled = false
      const finish = (value: string | null) => {
        if (settled) return
        settled = true
        pendingHostToken = null
        resolve(value)
      }
      pendingHostToken = (provided: string) => {
        void adoptHostToken(provided).then(() => finish(token.value || null))
      }
      window.setTimeout(() => finish(null), timeoutMs)
      postEmbedBootstrapRequest(id)
    })
  }

  // 会话票过期时的换票入口：axios 与 SSE 两条平面在 401 时调用（单飞在外层）。
  const refreshToken = async (): Promise<string | null> => {
    if (hostPublishToken) {
      try {
        const res = await exchangeEmbedSession(channelId.value, hostPublishToken)
        const next = String(res?.sessionToken || '').trim()
        if (!next) return null
        token.value = next
        return next
      } catch {
        return null
      }
    }
    return await waitForHostToken()
  }

  // 懒创建的会话句柄：首条消息发送前调用；并发调用共享同一次创建。
  let sessionCreating: Promise<boolean> | null = null
  const ensureSession = async (): Promise<boolean> => {
    if (sessionId.value) return true
    if (sessionCreating) return sessionCreating
    sessionCreating = (async () => {
      const id = channelId.value
      const apiToken = token.value
      if (!id || !apiToken) return false
      try {
        const res = await createEmbedSession(id, apiToken, visitorId.value)
        const newId = String(res?.id || '')
        if (!newId) return false
        const agentId = String(config.value?.agentId || '').trim()
        sessionId.value = newId
        sessionSig.value = res?.sig || ''
        writeStoredSession(id, { id: newId, sig: res?.sig || '', ...(agentId ? { agentId } : {}) })
        return true
      } catch {
        return false
      } finally {
        sessionCreating = null
      }
    })()
    return sessionCreating
  }

  /** 切到历史会话（抽屉点击）：换句柄并持久化为"当前会话"。 */
  const selectSession = (id: string, sig: string) => {
    const channel = channelId.value
    const nextId = String(id || '').trim()
    const nextSig = String(sig || '').trim()
    if (!channel || !nextId || !nextSig) return
    const agentId = String(config.value?.agentId || '').trim()
    sessionId.value = nextId
    sessionSig.value = nextSig
    writeStoredSession(channel, { id: nextId, sig: nextSig, ...(agentId ? { agentId } : {}) })
  }

  // 新建对话：回到"未创建"空白态（懒创建，首条消息才落库）；
  // 也是共用设备的隐私出口（localStorage 里的会话句柄一并清掉）。
  const startNewSession = () => {
    writeStoredSession(channelId.value, null)
    sessionId.value = ''
    sessionSig.value = ''
  }

  const start = async () => {
    // 会话票过期时，axios 与 SSE 两条平面都回调这里换票（单飞在外层）。
    registerEmbedTokenRefresher(refreshToken)

    removeHostListener = onEmbedHostContext((payload) => {
      hostContext.value = { ...hostContext.value, ...payload }
    })

    removeLocaleListener = onEmbedHostLocale((locale) => {
      hostLocalePinned = true
      applyEmbedLocale(locale, activeLocale)
    })

    removeTokenListener = onEmbedHostToken((providedToken, providedChannelId) => {
      if (providedChannelId && providedChannelId !== channelId.value) return
      const waiter = pendingHostToken
      if (waiter) {
        // 换票流程正等宿主回推：交给等待者，避免重复换票。
        waiter(providedToken)
        return
      }
      void bootstrap(providedToken)
    })

    if (!channelId.value) {
      loadError.value = t('embedPublish.missingChannel')
      return
    }

    const initialToken = String(route.query.token || '') || parseEmbedTokenFromLocation()
    if (initialToken) {
      await bootstrap(initialToken)
      return
    }

    if (window.parent !== window) {
      awaitingToken.value = true
      postEmbedBootstrapRequest(channelId.value)
      return
    }

    loadError.value = t('embedPublish.missingChannel')
  }

  onMounted(() => {
    start()
  })

  onUnmounted(() => {
    registerEmbedTokenRefresher(null)
    removeHostListener?.()
    removeLocaleListener?.()
    removeTokenListener?.()
  })

  return {
    token,
    config,
    sessionId,
    sessionSig,
    visitorId,
    loadError,
    awaitingToken,
    bootstrapping,
    hostContext,
    ensureSession,
    selectSession,
    startNewSession,
  }
}
