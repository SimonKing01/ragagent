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

/** 同一段里紧跟着的后一张图片（跳过 <br> 这类无文字节点）。 */
function nextMediaImage(element: Element): Element | null {
  let next = element.nextElementSibling;
  while (next) {
    if (next.tagName === 'IMG') {
      return next;
    }
    if ((next.textContent || '').trim()) {
      return null;
    }
    next = next.nextElementSibling;
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

  const inline = previousMediaImage(element) || nextMediaImage(element);
  if (inline) {
    const ref = imageRef(inline);
    if (ref) return ref;
  }

  const block = element.closest('p');
  for (const sibling of [block?.previousElementSibling, block?.nextElementSibling]) {
    if (sibling && !sibling.textContent?.trim()) {
      const img = sibling.querySelector('img');
      if (img) {
        const ref = imageRef(img);
        if (ref) return ref;
      }
    }
  }
  return null;
}

/**
 * 去掉与播放器封面重复的独立图片（同一张图不要在聊天里出现两次）。
 *
 * <p>模型可能把封面行写在播放链接<b>前面、后面，甚至隔了一段文字</b>；
 * 所以这里在整条消息（净化中的 DocumentFragment）里按引用找同一张图，
 * 而不是只看紧邻的兄弟节点——只看邻接会漏掉“视频 → 推荐语 → 封面”这种排布。</p>
 */
function dropDuplicatePoster(shell: Element, posterRef: string): void {
  const target = decodeProtectedRef(posterRef);
  const imageRefOf = (img: Element): string => img.getAttribute('data-protected-src')
    || img.getAttribute('src')
    || '';

  const root: ParentNode | null = shell.getRootNode() as ParentNode;
  const images: Element[] = root && typeof root.querySelectorAll === 'function'
    ? Array.from(root.querySelectorAll('img'))
    : [];

  for (const image of images) {
    const ref = imageRefOf(image);
    if (!ref || decodeProtectedRef(ref) !== target) {
      continue;
    }
    removeImageNode(image);
  }

  // 删掉图片后，播放器所在段落末尾可能只剩换行（原来分隔图文的那条），一并清掉
  const block = shell.closest('p');
  block?.querySelectorAll(':scope > br:last-child').forEach((br) => br.remove());
}

/** 移除封面图：独占一段就整段删掉，否则只删图片本身与它旁边的换行。 */
function removeImageNode(image: Element): void {
  const block = image.closest('p');
  // 只有「整段就是这一张图」才整段删；段里还有播放器/文字时只删图片本身
  const onlyImage = block
    && !Array.from(block.childNodes).some((node) => node.nodeType === Node.TEXT_NODE
        && (node.nodeValue || '').trim())
    && block.querySelectorAll('img').length === 1
    && block.querySelectorAll('video, audio, .chunk-video-shell, a, table, pre, code, ul, ol')
        .length === 0;
  if (block && onlyImage) {
    block.remove();
    return;
  }
  image.remove();
  const parent = image.parentElement;
  if (!parent) {
    return;
  }
  parent.querySelectorAll(':scope > br').forEach((br) => br.remove());
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
