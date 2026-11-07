/**
 * 受保护媒体引用（local://…/xxx.mp4 等）的共用判定与元素构造。
 *
 * <p>聊天回答走 markdown 净化钩子，知识库分块预览走另一条 `sanitizeHTML` 路径；
 * 两条路都要把「播放链接」升级成播放器，所以判定与构造收在这里，避免两边规则漂移。</p>
 */

/** 受保护音视频引用：local://…/exports/xxx.mp4（含 storage://backend/local://…）。 */
import { getCachedProtectedFile } from './protectedFileCache';
import { tText } from './activeI18n';

export const PROTECTED_VIDEO_REF =
  /^(?:local|resource|storage|minio|cos|tos|s3|oss|ks3|obs):\/\/.*\.(?:mp4|mov|m4v|webm|mkv)(?:[?#].*)?$/i;

/** markdown 渲染会把中文等字符做百分号编码，文件代理需要原始引用。 */
export function decodeProtectedRef(href: string): string {
  if (!/%[0-9A-Fa-f]{2}/.test(href)) {
    return href;
  }
  try {
    return decodeURIComponent(href);
  } catch {
    return href;
  }
}

export function isProtectedVideoHref(href: string | null | undefined): boolean {
  if (!href) {
    return false;
  }
  return PROTECTED_VIDEO_REF.test(href) || PROTECTED_VIDEO_REF.test(decodeProtectedRef(href));
}

/** markdown 链接的 title 里带着封面：`[play video](local://…mp4 "poster=local://…jpg")`。 */
export function posterRefFromTitle(title: string | null | undefined): string | null {
  if (!title) {
    return null;
  }
  const match = /(?:^|\s)poster=(\S+)/i.exec(title);
  return match ? match[1] : null;
}

/** 已经渲染过的 blob（渲染期直接复用，避免流式重渲染时闪一下）。 */
export interface CachedMediaSources {
  src?: string;
  poster?: string;
}

/** 构造播放器元素：没有缓存时 src 由后续的鉴权水合填（data-protected-src）。 */
export function createProtectedVideo(
  doc: Document,
  href: string,
  posterRef?: string | null,
  cached?: CachedMediaSources,
): HTMLElement {
  const video = doc.createElement('video');
  video.setAttribute('controls', '');
  video.setAttribute('preload', 'metadata');
  video.setAttribute('playsinline', '');
  video.setAttribute('class', 'chunk-video');
  // 分块预览不加载聊天的 markdown 样式表，这里给内联兜底
  video.setAttribute('style', 'max-width:100%');
  video.setAttribute('data-protected-src', decodeProtectedRef(href));
  if (posterRef) {
    video.setAttribute('data-poster-src', decodeProtectedRef(posterRef));
  }
  if (cached?.src) {
    // 命中缓存：一次性给出完整播放器，流式重渲染不会再出现「空白→加载」
    video.setAttribute('src', cached.src);
    video.dataset.authHydrated = '1';
  }
  if (cached?.poster) {
    video.setAttribute('poster', cached.poster);
  }
  // 首帧就带上外壳与遮罩：水合时不再二次包裹，避免「裸播放器 → 包壳」闪一下
  const shell = doc.createElement('span');
  shell.className = 'chunk-video-shell';
  if (cached?.src) {
    shell.setAttribute('data-media-state', 'ready');
  } else {
    shell.setAttribute('data-media-state', 'loading');
    const overlay = doc.createElement('span');
    overlay.className = 'chunk-video-overlay';
    overlay.setAttribute('role', 'status');
    overlay.textContent = tText('chat.videoLoadingHint');
    shell.appendChild(video);
    shell.appendChild(overlay);
    return shell;
  }
  shell.appendChild(video);
  return shell;
}

/**
 * 把容器里「指向受保护媒体的 <a>」就地升级成播放器。
 *
 * <p>幂等：已经升级过的节点不再是 `<a>`，重复调用无副作用；可在每次渲染/水合前调用。</p>
 */
/**
 * 视频的封面：正文结构是 `![poster](local://…/cover-xxx.jpg)` 紧跟 `[play video](local://…mp4)`，
 * 所以往前找最近的 <img> 当 poster；找不到就不设，不阻塞播放。
 */
export function findPosterImage(video: Element): HTMLImageElement | null {
  let scope: Element | null = video;
  for (let depth = 0; scope && depth < 4; depth += 1) {
    let prev: Element | null = scope.previousElementSibling;
    while (prev) {
      const img = prev.matches('img') ? (prev as HTMLImageElement) : prev.querySelector('img');
      if (img) {
        return img as HTMLImageElement;
      }
      prev = prev.previousElementSibling;
    }
    scope = scope.parentElement;
  }
  return null;
}

export function upgradeProtectedMediaRefs(root: ParentNode | null | undefined): void {
  if (!root) {
    return;
  }
  root.querySelectorAll<HTMLAnchorElement>('a[href]').forEach((anchor) => {
    const href = anchor.getAttribute('href') || '';
    if (!isProtectedVideoHref(href)) {
      return;
    }
    const posterRef = posterRefFromTitle(anchor.getAttribute('title'));
    const video = createProtectedVideo(
      anchor.ownerDocument,
      href,
      posterRef,
      {
        src: getCachedProtectedFile(decodeProtectedRef(href))?.blobURL,
        poster: posterRef ? getCachedProtectedFile(decodeProtectedRef(posterRef))?.blobURL : undefined,
      },
    );
    anchor.replaceWith(video);
    // 封面 ref 只为当 poster 用：播放器自带封面，不再重复显示一张图
    if (video.getAttribute('data-poster-src')) {
      return;
    }
    const poster = findPosterImage(video);
    const posterSource = poster
      ? poster.getAttribute('data-protected-src') || poster.getAttribute('src') || ''
      : '';
    if (poster && /cover-/i.test(posterSource)) {
      poster.setAttribute('data-media-poster', '1');
      poster.style.display = 'none';
    }
  });
}
