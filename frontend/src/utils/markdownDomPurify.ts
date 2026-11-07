import { createProtectedVideo, decodeProtectedRef, isProtectedVideoHref, posterRefFromTitle } from './protectedMedia';
import { getCachedProtectedFile } from './protectedFileCache';

export const domPurifyForbidTags = ['script', 'style', 'object', 'embed', 'form', 'input'] as const;
export const domPurifyForbidAttr = ['onerror', 'onload', 'onclick', 'onmouseover', 'onfocus', 'onblur'] as const;

export const domPurifyAllowedUriRegexp =
  /^(?:(?:(?:f|ht)tps?|mailto|tel|callto|cid|xmpp|blob):|data:image\/|(?:resource|storage|local|minio|cos|tos|s3|oss|ks3|obs):|[^a-z]|[a-z+.\-]+(?:[^a-z+.\-:]|$))/i;

/** Shared DOMPurify security options (FORBID_*, URI scheme, DOM flags). */
export const domPurifySecurityOptions = {
  ALLOWED_URI_REGEXP: domPurifyAllowedUriRegexp,
  FORBID_TAGS: [...domPurifyForbidTags],
  FORBID_ATTR: [...domPurifyForbidAttr],
  KEEP_CONTENT: true,
  RETURN_DOM: false,
  RETURN_DOM_FRAGMENT: false,
  RETURN_DOM_IMPORT: false,
  SANITIZE_DOM: true,
  SANITIZE_NAMED_PROPS: true,
  WHOLE_DOCUMENT: false,
} as const;

/** Shared DOMPurify hooks: strip scripts/event attrs; noopener links; alt on images. */
export const domPurifySecurityHooks = {
  beforeSanitizeElements: (currentNode: Node) => {
    if (!('tagName' in currentNode) || !('hasAttribute' in currentNode)) return;
    const element = currentNode as Element;
    if (element.tagName === 'SCRIPT') {
      element.remove();
      return;
    }
    domPurifyForbidAttr.forEach((attr) => {
      if (element.hasAttribute(attr)) {
        element.removeAttribute(attr);
      }
    });
  },
  afterSanitizeElements: (currentNode: Node) => {
    if (!('tagName' in currentNode) || !('getAttribute' in currentNode)) return;
    const element = currentNode as Element;
    if (element.tagName === 'A') {
      const href = element.getAttribute('href');
      if (href && href.startsWith('http')) {
        element.setAttribute('rel', 'noopener noreferrer');
        element.setAttribute('target', '_blank');
      }
    }
    if (element.tagName === 'IMG' && !element.getAttribute('alt')) {
      element.setAttribute('alt', '');
    }
  },
};

/** 同一段里紧挨着的前一张图片（跳过 <br> 这类无文字节点）。 */
function previousMediaImage(element: Element): Element | null {
  let prev = element.previousElementSibling;
  while (prev) {
    if (prev.tagName === 'IMG') {
      return prev;
    }
    if ((prev.textContent || '').trim()) {
      return null;
    }
    prev = prev.previousElementSibling;
  }
  return null;
}

/** 紧邻播放链接的受保护图片引用（分块格式：封面行 + 播放行）。 */
function adjacentProtectedImageRef(element: Element): string | null {
  const imageRef = (img: Element): string | null => {
    const ref = img.getAttribute('data-protected-src') || img.getAttribute('src') || '';
    return ref && /^(?:local|resource|storage|minio|s3|cos|tos|oss|ks3|obs):\/\//i.test(ref)
      ? ref
      : null;
  };

  const inline = previousMediaImage(element);
  if (inline) {
    const ref = imageRef(inline);
    if (ref) return ref;
  }

  const block = element.closest('p');
  const prevBlock = block?.previousElementSibling;
  if (prevBlock && !prevBlock.textContent?.trim()) {
    const img = prevBlock.querySelector('img');
    if (img) {
      const ref = imageRef(img);
      if (ref) return ref;
    }
  }
  return null;
}

/** 去掉与播放器封面重复的独立图片（同一张图不要在聊天里出现两次）。 */
function dropDuplicatePoster(shell: Element, posterRef: string): void {
  const target = decodeProtectedRef(posterRef);
  const matches = (img: Element): boolean => {
    const ref = img.getAttribute('data-protected-src')
      || img.getAttribute('src')
      || '';
    return Boolean(ref) && decodeProtectedRef(ref) === target;
  };

  // 情况一：图片与播放链接在同一段（中间可能夹着 <br>）
  const inline = previousMediaImage(shell);
  if (inline && matches(inline)) {
    inline.remove();
    // 图片删掉后，紧贴播放器前面的 <br> 会留下一行空白，顺手清掉
    let prev = shell.previousElementSibling;
    while (prev && prev.tagName === 'BR') {
      const before = prev.previousElementSibling;
      prev.remove();
      prev = before;
    }
    return;
  }

  // 情况二：图片是上一段（通常只有这一张图）
  const block = shell.closest('p') || shell;
  const prevBlock = block.previousElementSibling;
  if (!prevBlock || prevBlock.textContent?.trim()) {
    return;
  }
  const image = prevBlock.querySelector('img');
  if (image && matches(image)) {
    prevBlock.remove();
  }
}

/** Chat markdown links must never replace the conversation page. */
export const markdownDomPurifySecurityHooks = {
  ...domPurifySecurityHooks,
  afterSanitizeElements: (currentNode: Node) => {
    domPurifySecurityHooks.afterSanitizeElements(currentNode);
    if (!('tagName' in currentNode) || !('getAttribute' in currentNode)) return;
    const element = currentNode as Element;
    if (element.tagName === 'A' && element.getAttribute('href')) {
      const href = element.getAttribute('href') || '';
      // 受保护的音视频引用（local://…/xxx.mp4）：渲染成播放器，而不是普通链接
      if (isProtectedVideoHref(href)) {
        // 模型不一定保留链接 title 里的 poster=…：拿不到时退回“紧邻的封面图”
        const posterRef = posterRefFromTitle(element.getAttribute('title'))
          || adjacentProtectedImageRef(element);
        const shell = createProtectedVideo(
          element.ownerDocument,
          href,
          posterRef,
          {
            src: getCachedProtectedFile(decodeProtectedRef(href))?.blobURL,
            poster: posterRef
              ? getCachedProtectedFile(decodeProtectedRef(posterRef))?.blobURL
              : undefined,
          },
        );
        element.replaceWith(shell);
        // 分块正文里封面图是独立一行，播放器又自带 poster：
        // 同一张封面不要在聊天里再单独渲染一次
        if (posterRef) {
          dropDuplicatePoster(shell, posterRef);
        }
        return;
      }
      const className = element.getAttribute('class') || '';
      if (/\bprotected-resource-card\b/.test(className) || element.hasAttribute('download')) {
        element.removeAttribute('target');
        return;
      }
      element.setAttribute('rel', 'noopener noreferrer');
      element.setAttribute('target', '_blank');
    }
  },
};

/** Shared DOMPurify config for chat / dev markdown (KaTeX + Mermaid SVG). */
export const markdownDomPurifyConfig = {
  ALLOWED_TAGS: [
    'p', 'br', 'strong', 'em', 'u', 'code', 'pre', 'ul', 'ol', 'li', 'blockquote',
    'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'a', 'span', 'table', 'thead', 'tbody',
    'tr', 'th', 'td', 'img', 'figure', 'figcaption', 'div',
    'svg', 'g', 'path', 'rect', 'circle', 'ellipse', 'line', 'polygon',
    'polyline', 'text', 'tspan', 'defs', 'marker', 'filter', 'use',
    'clippath', 'lineargradient', 'radialgradient', 'stop', 'pattern',
    'image', 'foreignobject', 'desc', 'title', 'switch', 'symbol', 'mask',
    'math', 'annotation', 'semantics', 'mo', 'mi', 'mn', 'msup', 'mrow', 'mfrac', 'msqrt', 'mroot', 'mstyle',
    'button', 'video',
  ],
  ALLOWED_ATTR: [
    'href', 'title', 'target', 'rel', 'data-tooltip', 'data-url', 'data-kb-id',
    'data-chunk-id', 'data-doc', 'data-slug', 'class', 'role', 'tabindex', 'src', 'alt', 'data-protected-src', 'data-img-loading',
    'data-artifact-index', 'data-protected-resource', 'download',
    'controls', 'preload', 'playsinline', 'poster',
    'width', 'height', 'style', 'id', 'type', 'aria-label', 'data-mermaid', 'disabled',
    'd', 'fill', 'stroke', 'stroke-width', 'stroke-linecap', 'stroke-linejoin',
    'stroke-dasharray', 'stroke-dashoffset', 'stroke-miterlimit', 'stroke-opacity',
    'fill-opacity', 'opacity', 'transform', 'viewbox', 'preserveaspectratio',
    'x', 'y', 'x1', 'y1', 'x2', 'y2', 'cx', 'cy', 'rx', 'ry', 'r',
    'dx', 'dy', 'text-anchor', 'dominant-baseline', 'font-family', 'font-size',
    'font-weight', 'font-style', 'letter-spacing', 'word-spacing',
    'marker-start', 'marker-mid', 'marker-end', 'markerunits', 'markerwidth',
    'markerheight', 'refx', 'refy', 'orient', 'points', 'offset',
    'gradientunits', 'gradienttransform', 'spreadmethod', 'stop-color', 'stop-opacity',
    'patternunits', 'patterntransform', 'clippathunits', 'maskunits',
    'filterunits', 'primitiveunits', 'xmlns', 'xmlns:xlink', 'xlink:href',
    'version', 'baseprofile', 'enable-background', 'overflow', 'visibility',
    'display', 'pointer-events', 'cursor', 'data-emit', 'direction',
    'mathvariant', 'encoding', 'aria-hidden',
  ],
  USE_PROFILES: { html: true, svg: true, mathMl: true },
  // USE_PROFILES 会让 DOMPurify 忽略 ALLOWED_ATTR（改用 profile 内置属性表），
  // 而 html profile 不含 target —— 回复里的链接会被剥成 rel 但没有 target，
  // 点击就在 iframe 内跳走了。ADD_ATTR 在 profile 模式下依然生效。
  ADD_ATTR: ['target'],
  ...domPurifySecurityOptions,
};
