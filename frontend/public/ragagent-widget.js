/**
 * RagAgent embed widget SDK — floating chat launcher.
 *
 * Programmatic:
 *   RagAgent.init({ channel, token, position, primaryColor, title, baseUrl, locale })
 *   RagAgent.open() | close() | toggle() | destroy()
 *   RagAgent.on('ready', fn) | off('ready', fn)
 *
 * Host context & actions (postMessage to iframe):
 *   RagAgent.setContext({ userId, page, ... })
 *     Inject visitor/page context merged into each chat query.
 *   RagAgent.openWithQuery('How do I reset my password?')
 *     Opens the panel (if closed) and sends the query when the iframe is ready.
 *   RagAgent.setLocale('en-US')
 *     Switch embed UI language (zh-CN | en-US | ko-KR | ja-JP | ru-RU).
 *     Declarative alternative: `locale` option / `data-locale` attribute. It is
 *     folded into the iframe URL, so the first paint is already in that language
 *     and the channel's default locale won't override it.
 *     When neither is set, the host page's own `<html lang>` is forwarded as a
 *     weaker hint — honored only when the channel follows browser/host.
 *
 * Secure mode (recommended): instead of `token`, pass `tokenEndpoint` — a URL on
 * your own backend that returns { token: "ems_...", expiresIn: 1800 }. Your
 * backend mints that short-lived session token by exchanging the publish token
 * (kept server-side) against POST /api/v1/embed/:channel/exchange. The publish
 * token then never reaches the browser; the widget auto-refreshes before expiry.
 *
 * Legacy script-tag auto-init via data-* attributes on the script element
 * (data-channel + data-token, or data-channel + data-token-endpoint,
 * optional data-locale / data-position / data-primary-color / data-title).
 */
(function (global) {
  'use strict';

  var HOST_SOURCE = 'weknora-host';
  var EMBED_SOURCE = 'weknora-embed';
  var POSITIONS = ['bottom-right', 'bottom-left', 'top-right', 'top-left'];
  var DEFAULT_POSITION = 'bottom-right';
  var DEFAULT_COLOR = '#07C05F';
  var DEFAULT_TITLE = 'AI Assistant';
  var DEFAULT_WIDTH = 420;
  var DEFAULT_HEIGHT = 720;
  // 未配置渠道 launcherIcon 时的默认图标：内联 SVG（白色对话气泡，随按钮主色）
  // —— 取代原先的 emoji，避免"先 emoji 再用户图片"的闪烁与平台字体差异。
  var DEFAULT_LAUNCHER_SVG =
    '<svg viewBox="0 0 1024 1024" width="26" height="26" aria-hidden="true" ' +
    'style="display:block;pointer-events:none"><path fill="#ffffff" d="M781.69183326 208.58123803H242.36749291c-9.3092649 0-17.22930884 3.29754663-23.84417772 9.88769507-6.57531762 6.59509253-9.87780761 14.52502465-9.87780761 23.79473901v370.83801222c0 9.37847924 3.30743408 17.30841065 9.87780761 23.90350389 6.61486816 6.59014916 14.53491211 9.88769508 23.84417772 9.88769508h101.13629151v105.07159448l146.9311521-105.07159448h291.27667236c9.31420898 0 17.23425293-3.29754663 23.85900855-9.88769508 6.57037354-6.59509253 9.87780761-14.52502465 9.87780763-23.8985598V242.26367211c0-9.27465844-3.31237817-17.20458984-9.87780763-23.79968309-6.62475562-6.59014916-14.54479957-9.88769508-23.85900855-9.88769508h-0.01977562zM242.34771729 141.21142578h539.32434106c27.89318824 0 51.74725342 9.78881836 71.50286865 29.56420922 19.74572778 19.77539086 29.61364722 43.57507324 29.61364722 71.48803711v370.83801222c0 27.91790796-9.86792016 51.81646704-29.61364722 71.49298119-19.75561523 19.77539086-43.60968041 29.66308594-71.50286865 29.66308594h-269.68688989L276.049927 882.78857422v-168.52587867h-33.70220971c-27.91296386 0-51.74725342-9.88769508-71.50286866-29.66308594C151.08923339 664.9181521 141.21142578 641.0146482 141.21142578 613.10168433V242.26367211C141.21142578 214.34576416 151.08923339 190.55102516 170.84484863 170.775635 190.60046386 151.00024414 214.43475342 141.21142578 242.34771729 141.21142578z"/></svg>';

  var instance = null;
  var listeners = {};

  function normalizePosition(pos) {
    if (!pos || POSITIONS.indexOf(pos) < 0) return DEFAULT_POSITION;
    return pos;
  }

  function positionStyles(position, kind) {
    var isLeft = position.indexOf('left') >= 0;
    var isTop = position.indexOf('top') >= 0;
    var horizontal = isLeft ? 'left:24px' : 'right:24px';
    if (kind === 'launcher') {
      return horizontal + ';' + (isTop ? 'top:24px' : 'bottom:24px');
    }
    return horizontal + ';' + (isTop ? 'top:88px' : 'bottom:88px');
  }

  // iframe 首屏前的占位骨架样式（注入宿主文档一次）。
  var SHIELD_STYLE_ID = 'weknora-widget-shield-style';
  function ensureShieldStyle() {
    if (document.getElementById(SHIELD_STYLE_ID)) return;
    var style = document.createElement('style');
    style.id = SHIELD_STYLE_ID;
    style.textContent =
      // 首屏只画一个转圈：面板形态（欢迎 / 会话）由嵌入页自己决定，
      // 宿主猜错反而多一次骨架形态切换，所以这里不画任何骨架块。
      '.weknora-shield-spinner{width:24px;height:24px;border-radius:50%;' +
      'border:2px solid rgba(0,0,0,.12);border-top-color:#0052d9;' +
      'animation:weknora-shield-spin .8s linear infinite;display:block}' +
      '@keyframes weknora-shield-spin{to{transform:rotate(360deg)}}' +
      '@media (prefers-reduced-motion: reduce){.weknora-shield-spinner{animation-duration:2.4s}}';
    document.head.appendChild(style);
  }

  // 面板内的加载遮罩：只画一个居中转圈。面板形态（欢迎 / 会话）由嵌入页按自己的
  // localStorage 决定，宿主跨域读不到，画骨架只会猜错并多一次形态切换。
  function buildBootShield() {
    ensureShieldStyle();
    var shield = document.createElement('div');
    shield.style.cssText = [
      'position:absolute',
      'top:0',
      'right:0',
      'bottom:0',
      'left:0',
      'z-index:1',
      'display:flex',
      'flex-direction:column',
      'background:#fff',
      'transition:opacity .2s ease',
    ].join(';');
    shield.style.alignItems = 'center';
    shield.style.justifyContent = 'center';
    shield.innerHTML = '<span class="weknora-shield-spinner" aria-hidden="true"></span>';
    return shield;
  }

  // 提前与 embed 源建连：首次展开面板时省掉 DNS + TLS 往返。
  function warmUpOrigin(origin) {
    if (!origin || !document.head) return;
    try {
      var preconnect = document.createElement('link');
      preconnect.rel = 'preconnect';
      preconnect.href = origin;
      preconnect.crossOrigin = 'anonymous';
      document.head.appendChild(preconnect);
      var dnsPrefetch = document.createElement('link');
      dnsPrefetch.rel = 'dns-prefetch';
      dnsPrefetch.href = origin;
      document.head.appendChild(dnsPrefetch);
    } catch (e) { /* 旧浏览器忽略 */ }
  }

  function emit(event, payload) {
    var handlers = listeners[event];
    if (!handlers) return;
    handlers.slice().forEach(function (fn) {
      try { fn(payload); } catch (e) { console.error('[RagAgent]', e); }
    });
  }

  function createWidget(opts) {
    var channelId = opts.channel || opts.channelId;
    // Insecure mode: a long-lived publish token is embedded in the page.
    var staticToken = opts.token;
    // Secure mode: the page never holds the publish token. Instead it points at
    // an endpoint on the integrator's own backend that mints a short-lived
    // session token (by server-side exchange of the publish token). The widget
    // fetches a fresh token here and refreshes it before expiry.
    var tokenEndpoint = opts.tokenEndpoint || opts.token_endpoint || '';
    if (!channelId || (!staticToken && !tokenEndpoint)) {
      console.warn('[RagAgent] channel and (token or tokenEndpoint) are required');
      return null;
    }

    var currentToken = staticToken || '';
    var tokenInFlight = null;
    var refreshTimer = null;

    function scheduleRefresh(expiresInSec) {
      if (!tokenEndpoint) return;
      if (refreshTimer) clearTimeout(refreshTimer);
      var ttl = Number(expiresInSec) > 0 ? Number(expiresInSec) : 1800;
      // Refresh at ~80% of the lifetime, never sooner than 30s.
      var delayMs = Math.max(Math.floor(ttl * 0.8), 30) * 1000;
      refreshTimer = setTimeout(function () {
        loadToken(true).then(function (tok) {
          if (tok) provideToken();
        }).catch(function () { /* keep last token; next interaction retries */ });
      }, delayMs);
    }

    // Returns a Promise resolving to a usable token. In static mode this is the
    // embedded publish token. In secure mode it fetches from tokenEndpoint.
    function loadToken(force) {
      if (staticToken) return Promise.resolve(staticToken);
      if (currentToken && !force) return Promise.resolve(currentToken);
      if (tokenInFlight) return tokenInFlight;
      tokenInFlight = fetch(tokenEndpoint, {
        method: 'GET',
        credentials: 'include',
        headers: { Accept: 'application/json' },
      })
        .then(function (res) {
          if (!res.ok) throw new Error('token endpoint HTTP ' + res.status);
          return res.json();
        })
        .then(function (data) {
          var d = data || {};
          var inner = d.data || d;
          // 兼容三种键名：集成方自建端点惯用 token / session_token；而本仓
          // POST /api/v1/embed/{channel}/exchange 的契约键是 **sessionToken**（在 data 内）
          // —— 集成方若直接转发本仓响应，少了这一段就会 throw 'no token'（2026-10-11 B227 补）。
          var tok = inner.sessionToken || inner.token || inner.session_token || '';
          var expiresIn = inner.expiresIn || inner.expires_in || 0;
          if (!tok) throw new Error('token endpoint returned no token');
          currentToken = tok;
          scheduleRefresh(expiresIn);
          return tok;
        })
        .catch(function (e) {
          console.error('[RagAgent] failed to load token', e);
          throw e;
        })
        .then(function (tok) { tokenInFlight = null; return tok; }, function (e) { tokenInFlight = null; throw e; });
      return tokenInFlight;
    }

    var position = normalizePosition(opts.position);
    var primaryColor = opts.primaryColor || opts.primary_color || DEFAULT_COLOR;
    var title = opts.title || DEFAULT_TITLE;
    var baseUrl = (opts.baseUrl || opts.base || '').replace(/\/$/, '');
    if (!baseUrl) {
      var script = document.currentScript;
      if (script && script.src) {
        baseUrl = script.src.replace(/\/ragagent-widget\.js.*$/, '');
      } else {
        baseUrl = global.location ? global.location.origin : '';
      }
    }

    var panelWidth = Number(opts.width) > 0 ? Number(opts.width) : DEFAULT_WIDTH;
    var panelHeight = Number(opts.height) > 0 ? Number(opts.height) : DEFAULT_HEIGHT;
    // 宿主**显式**声明的语言（init({locale}) / data-locale）：拼进 URL，embed 页首屏即用对语言，
    // 且被视为「宿主已 pin」——不会被渠道默认语言覆盖，也不写访客的持久值。
    var hostLocale = String(opts.locale || opts.lang || '').trim();
    // 宿主**页面**声明的语言（<html lang>）：弱信号，仅在渠道配「跟随浏览器/宿主」时生效，
    // 优先级低于渠道默认语言 —— 所以走独立参数 hostLocale，不占用 ?locale=。
    var hostPageLocale = '';
    if (!hostLocale) {
      try {
        var rootEl = document.documentElement;
        hostPageLocale = rootEl && rootEl.lang ? String(rootEl.lang).trim() : '';
      } catch (e) {
        hostPageLocale = '';
      }
    }
    var embedUrl = baseUrl + '/embed/' + encodeURIComponent(channelId);
    if (hostLocale) {
      embedUrl += '?locale=' + encodeURIComponent(hostLocale);
    } else if (hostPageLocale) {
      embedUrl += '?hostLocale=' + encodeURIComponent(hostPageLocale);
    }
    var embedOrigin = baseUrl;
    try {
      // Derive the exact origin (scheme + host + port) rather than trusting the
      // raw baseUrl string, so postMessage origin checks are precise.
      embedOrigin = new URL(embedUrl, global.location ? global.location.href : undefined).origin;
    } catch (e) {
      embedOrigin = baseUrl;
    }
    var destroyed = false;
    var panelOpen = false;
    var iframeReady = false;
    var iframeOrigin = '';
    warmUpOrigin(embedOrigin);

    var launcher = document.createElement('button');
    launcher.type = 'button';
    launcher.setAttribute('aria-label', title);
    launcher.style.cssText = [
      'position:fixed',
      'z-index:2147483000',
      'width:56px',
      'height:56px',
      'border-radius:50%',
      'border:none',
      'padding:0',
      'box-sizing:border-box',
      'cursor:pointer',
      'font-size:24px',
      'display:flex',
      'align-items:center',
      'justify-content:center',
      // 先隐藏：等渠道配置（主色 + 图标）解析完成再露面，消除"先默认浅色/先 emoji"的闪烁
      'visibility:hidden',
      'box-shadow:0 4px 16px rgba(0,0,0,.18)',
      'background:' + primaryColor,
      'color:#fff',
      'opacity:0.92',
      'transition:opacity .2s',
      positionStyles(position, 'launcher'),
    ].join(';');

    var launcherRevealed = false;
    var launcherRevealTimer = null;

    // 露出按钮：只在"最终外观已就绪"后调用一次（图标 onload/onerror、配置失败、兜底定时器）。
    function revealLauncher() {
      if (launcherRevealed) return;
      launcherRevealed = true;
      if (launcherRevealTimer) {
        clearTimeout(launcherRevealTimer);
        launcherRevealTimer = null;
      }
      launcher.style.visibility = 'visible';
    }
    // 兜底：配置请求异常挂住时也要露出（优先"不闪"，但不能永不出现）
    launcherRevealTimer = setTimeout(revealLauncher, 1500);

    var launcherIconUrl = '';
    var launcherImg = null;

    function renderLauncherContent() {
      launcher.textContent = '';
      if (panelOpen) {
        launcher.textContent = '✕';
        revealLauncher();
        return;
      }
      if (launcherIconUrl) {
        if (!launcherImg) {
          launcherImg = document.createElement('img');
          launcherImg.src = launcherIconUrl;
          launcherImg.alt = '';
          launcherImg.style.cssText =
            'width:100%;height:100%;object-fit:cover;border-radius:50%;' +
            'pointer-events:none;display:block';
          // 图片解码完成后才给按钮露面（渠道图标是 base64 data URL，通常瞬时）
          launcherImg.onload = revealLauncher;
          launcherImg.onerror = function () {
            launcherIconUrl = '';
            launcherImg = null;
            renderLauncherContent();
            revealLauncher();
          };
        }
        launcher.style.overflow = 'hidden';
        launcher.appendChild(launcherImg);
        return;
      }
      launcher.innerHTML = DEFAULT_LAUNCHER_SVG;
    }
    renderLauncherContent();

    var panel = document.createElement('div');
    panel.style.cssText = [
      'position:fixed',
      'z-index:2147482999',
      'width:' + panelWidth + 'px',
      'max-width:calc(100vw - 32px)',
      'height:' + panelHeight + 'px',
      'max-height:calc(100vh - 100px)',
      'border-radius:12px',
      'overflow:hidden',
      'box-shadow:0 8px 32px rgba(0,0,0,.2)',
      'display:none',
      'background:#fff',
      positionStyles(position, 'panel'),
    ].join(';');

    var iframe = document.createElement('iframe');
    iframe.src = embedUrl;
    iframe.style.cssText = 'width:100%;height:100%;border:none';
    iframe.setAttribute('allow', 'clipboard-write');
    var hostOrigin = '';
    try {
      hostOrigin = global.location ? global.location.origin : '';
    } catch (e) { hostOrigin = ''; }
    var crossOriginEmbed = hostOrigin && embedOrigin && hostOrigin !== embedOrigin;
    var sandboxAttr = String(opts.sandbox || opts.sandboxMode || '').trim();
    if (!sandboxAttr && opts.scriptEl) {
      sandboxAttr = String(opts.scriptEl.getAttribute('data-sandbox') || '').trim();
    }
    if (sandboxAttr === 'true' || sandboxAttr === '1') {
      sandboxAttr = 'allow-scripts allow-forms allow-popups allow-modals allow-same-origin';
    }
    if (!sandboxAttr && crossOriginEmbed) {
      sandboxAttr = 'allow-scripts allow-forms allow-popups allow-modals allow-same-origin';
    }
    if (sandboxAttr && sandboxAttr !== 'false' && sandboxAttr !== '0') {
      iframe.setAttribute('sandbox', sandboxAttr);
    }
    iframe.setAttribute('title', title);
    panel.appendChild(iframe);

    // 盖在 iframe 上的骨架：iframe 自身白屏期间也有结构；就绪后淡出。
    var bootShield = buildBootShield();
    panel.appendChild(bootShield);
    var shieldVisible = true;
    var shieldTimer = setTimeout(hideBootShield, 15000);

    function hideBootShield() {
      if (!shieldVisible) return;
      shieldVisible = false;
      if (shieldTimer) {
        clearTimeout(shieldTimer);
        shieldTimer = null;
      }
      bootShield.style.opacity = '0';
      setTimeout(function () {
        if (bootShield.parentNode) bootShield.parentNode.removeChild(bootShield);
      }, 220);
    }

    function isTrustedOrigin(origin) {
      if (!origin || origin === 'null') return false;
      return origin === embedOrigin;
    }

    function postToIframe(message) {
      if (!iframe.contentWindow) return;
      // Always target the known embed origin; never fall back to '*' so the
      // publish token can't leak to an unexpected document.
      iframe.contentWindow.postMessage(message, embedOrigin || '/');
    }

    function provideToken() {
      loadToken(false).then(function (tok) {
        if (!tok) return;
        postToIframe({
          source: HOST_SOURCE,
          type: 'provide_token',
          token: tok,
          channel_id: channelId,
        });
      }).catch(function () { /* already logged; iframe stays awaiting */ });
    }

    function postHostPayload(type, payload) {
      if (!iframe.contentWindow) {
        console.warn('[RagAgent] iframe not ready');
        return false;
      }
      postToIframe({ source: HOST_SOURCE, type: type, payload: payload || {} });
      return true;
    }

    function whenIframeReady(fn, attempt) {
      var tries = attempt || 0;
      if (iframeReady && iframe.contentWindow) {
        fn();
        return;
      }
      if (tries >= 20) {
        console.warn('[RagAgent] iframe not ready');
        return;
      }
      setTimeout(function () { whenIframeReady(fn, tries + 1); }, 100);
    }

    function setContext(ctx) {
      if (!ctx || typeof ctx !== 'object') {
        console.warn('[RagAgent] setContext expects an object');
        return;
      }
      postHostPayload('set_context', ctx);
    }

    function openWithQuery(query) {
      var text = String(query || '').trim();
      if (!text) {
        console.warn('[RagAgent] openWithQuery requires a non-empty query');
        return;
      }
      setOpen(true);
      whenIframeReady(function () {
        postHostPayload('open_with_query', { query: text });
      });
    }

    var pendingLocale = '';

    function setLocale(locale) {
      var loc = String(locale || '').trim();
      if (!loc) {
        console.warn('[RagAgent] setLocale requires a locale string');
        return;
      }
      if (!iframeReady) {
        // 面板打开前调用：记住，握手完成后再发（旧实现直接 post → 静默丢失）。
        pendingLocale = loc;
        return;
      }
      postHostPayload('set_locale', { locale: loc });
    }

    function onMessage(e) {
      // Only trust messages coming from our own iframe window and origin.
      if (e.source !== iframe.contentWindow) return;
      if (!isTrustedOrigin(e.origin)) return;
      if (!e.data || e.data.source !== EMBED_SOURCE) return;
      if (e.data.channel_id && e.data.channel_id !== channelId) return;

      if (!iframeOrigin) {
        iframeOrigin = e.origin;
      }

      switch (e.data.type) {
        case 'bootstrap_request':
          provideToken();
          break;
        case 'panel_state_request':
          // 嵌入页挂载后主动问一次：用户可能在 iframe 就绪前就点开了面板，
          // 那条 panel_open 会落空（收端尚未注册监听）
          if (panelOpen) {
            postToIframe({ source: HOST_SOURCE, type: 'panel_open', payload: {} });
          }
          break;
        case 'shell_ready':
          // 嵌入页的静态骨架已经进 DOM（比 load 早几百毫秒），可以交接了
          hideBootShield();
          break;
        case 'ready':
          iframeReady = true;
          hideBootShield();
          launcher.style.opacity = '1';
          // 面板比嵌入页先打开时，之前那条 panel_open 会落空（收端还没注册监听）：
          // 这里在 ready 后补发一次，保证「打开后空闲预取」一定被触发。
          if (panelOpen) {
            postToIframe({ source: HOST_SOURCE, type: 'panel_open', payload: {} });
          }
          if (pendingLocale) {
            postHostPayload('set_locale', { locale: pendingLocale });
            pendingLocale = '';
          }
          emit('ready', { channelId: channelId });
          break;
        case 'message_sent':
          emit('message_sent', {
            channelId: channelId,
            sessionId: e.data.session_id,
            query: e.data.query,
          });
          break;
        case 'message_received':
          emit('message_received', {
            channelId: channelId,
            sessionId: e.data.session_id,
            content: e.data.content,
          });
          break;
        default:
          break;
      }
    }

    function setOpen(next) {
      panelOpen = !!next;
      panel.style.display = panelOpen ? 'block' : 'none';
      renderLauncherContent();
      if (panelOpen) {
        emit('open', { channelId: channelId });
        // 通知 iframe「面板真的打开了」：嵌入页据此在空闲时预取回答渲染才用到的
        // 重资源（mermaid / highlight.js），既不抢首屏带宽，又能在用户提问前备好。
        postToIframe({ source: HOST_SOURCE, type: 'panel_open', payload: {} });
      } else {
        emit('close', { channelId: channelId });
      }
    }

    function open() { setOpen(true); }
    function close() { setOpen(false); }
    function toggle() { setOpen(!panelOpen); }

    function destroy() {
      if (destroyed) return;
      destroyed = true;
      if (refreshTimer) clearTimeout(refreshTimer);
      if (shieldTimer) clearTimeout(shieldTimer);
      global.removeEventListener('message', onMessage);
      if (launcher.parentNode) launcher.parentNode.removeChild(launcher);
      if (panel.parentNode) panel.parentNode.removeChild(panel);
      listeners = {};
      if (instance === api) instance = null;
    }

    launcher.addEventListener('click', toggle);
    iframe.addEventListener('load', function () {
      // The embed page lives at embedOrigin; we cannot (and need not) read the
      // cross-origin contentWindow.location. Just (re)provide the token.
      iframeOrigin = embedOrigin;
      provideToken();
      // embed 文档已就绪（它自己也有同形骨架），这层遮罩可以撤了。
      hideBootShield();
    });

    document.body.appendChild(launcher);
    document.body.appendChild(panel);
    global.addEventListener('message', onMessage);

    // Fetch the channel's public config for appearance (colour + launcher icon).
    // Runs after the token is available; failures keep the snippet-provided look.
    //
    // The channel config wins over data-primary-color: the snippet builder bakes
    // that attribute in when the code is copied, so without this an admin colour
    // change would never reach embeds that were pasted earlier. The attribute
    // still provides the initial paint (no flash while this request is in flight)
    // and the fallback when the request fails.
    function loadChannelAppearance() {
      loadToken().then(function (tok) {
        return fetch(baseUrl + '/api/v1/embed/' + encodeURIComponent(channelId) + '/config', {
          headers: { Authorization: 'Embed ' + tok, Accept: 'application/json' },
        });
      }).then(function (res) {
        if (!res || !res.ok) return null;
        return res.json();
      }).then(function (payload) {
        var cfg = payload && payload.data ? payload.data : payload;
        if (!cfg) {
          revealLauncher();
          return;
        }
        if (typeof cfg.primaryColor === 'string' && cfg.primaryColor) {
          primaryColor = cfg.primaryColor;
          launcher.style.background = primaryColor;
        }
        if (typeof cfg.launcherIcon === 'string' && cfg.launcherIcon) {
          launcherIconUrl = cfg.launcherIcon;
          launcherImg = null;
          renderLauncherContent(); // img.onload/onerror 里露出
          return;
        }
        revealLauncher();
      }).catch(function () {
        // 配置拉取失败：沿用片段里的外观（data-primary-color）并露出
        revealLauncher();
      });
    }
    loadChannelAppearance();

    return {
      open: open,
      close: close,
      toggle: toggle,
      destroy: destroy,
      isOpen: function () { return panelOpen; },
      isReady: function () { return iframeReady; },
      setContext: setContext,
      openWithQuery: openWithQuery,
      setLocale: setLocale,
    };
  }

  var api = {
    init: function (opts) {
      if (instance) instance.destroy();
      instance = createWidget(opts || {});
      return instance;
    },
    open: function () { if (instance) instance.open(); },
    close: function () { if (instance) instance.close(); },
    toggle: function () { if (instance) instance.toggle(); },
    destroy: function () { if (instance) instance.destroy(); },
    on: function (event, handler) {
      if (!handler || typeof handler !== 'function') return;
      if (!listeners[event]) listeners[event] = [];
      listeners[event].push(handler);
    },
    off: function (event, handler) {
      if (!listeners[event]) return;
      if (!handler) {
        delete listeners[event];
        return;
      }
      listeners[event] = listeners[event].filter(function (fn) { return fn !== handler; });
    },
    setContext: function (ctx) { if (instance) instance.setContext(ctx); },
    openWithQuery: function (query) { if (instance) instance.openWithQuery(query); },
    setLocale: function (locale) { if (instance) instance.setLocale(locale); },
  };

  global.RagAgent = api;

  var legacyScript = document.currentScript;
  if (legacyScript) {
    var legacyChannel = legacyScript.getAttribute('data-channel');
    var legacyToken = legacyScript.getAttribute('data-token');
    var legacyTokenEndpoint = legacyScript.getAttribute('data-token-endpoint');
    if (legacyChannel && (legacyToken || legacyTokenEndpoint)) {
      api.init({
        channel: legacyChannel,
        token: legacyToken,
        tokenEndpoint: legacyTokenEndpoint,
        scriptEl: legacyScript,
        position: legacyScript.getAttribute('data-position'),
        primaryColor: legacyScript.getAttribute('data-primary-color'),
        title: legacyScript.getAttribute('data-title'),
        locale: legacyScript.getAttribute('data-locale'),
        baseUrl: legacyScript.getAttribute('data-base-url'),
        width: legacyScript.getAttribute('data-width'),
        height: legacyScript.getAttribute('data-height'),
      });
    }
  }
})(typeof window !== 'undefined' ? window : this);
