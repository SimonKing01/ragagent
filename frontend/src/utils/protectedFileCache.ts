/**
 * 受保护文件（图片 / 视频 / 下载卡片）的 blob 缓存。
 *
 * <p>聊天回答是流式的：每个 chunk 都会重新渲染一次 markdown，产生全新的
 * `<img>` / `<video>` 元素。渲染时若能从这份缓存直接拿到 blob URL，就能一次性
 * 输出「已加载好」的元素，避免出现「空白 → 加载 → 播放」的闪烁。</p>
 *
 * <p>缓存挂在 window 上：Vite 热更新会替换模块，但页面 document 还在，
 * 模块内的 Map 会丢，已经加载的 blob 就会被当成未加载。</p>
 */

import type { LoadedProtectedFile } from './protectedResource.ts';

export type ProtectedFileLoadResult =
  | ({ status: 'loaded' } & LoadedProtectedFile)
  | { status: 'missing' }
  | { status: 'failed' };

export type ProtectedFileCacheState = {
  blobByRequest: Map<string, LoadedProtectedFile>;
  fileBySource: Map<string, LoadedProtectedFile>;
  missingSources: Set<string>;
  failures: Map<string, number>;
  inflight: Map<string, Promise<ProtectedFileLoadResult>>;
};

export const protectedFileCacheState = ((): ProtectedFileCacheState => {
  const fresh = (): ProtectedFileCacheState => ({
    blobByRequest: new Map(),
    fileBySource: new Map(),
    missingSources: new Set(),
    failures: new Map(),
    inflight: new Map(),
  });
  if (typeof window === 'undefined') return fresh();
  const scope = window as typeof window & {
    __weknoraProtectedFileCacheV3__?: ProtectedFileCacheState;
  };
  scope.__weknoraProtectedFileCacheV3__ ||= fresh();
  return scope.__weknoraProtectedFileCacheV3__;
})();

export const protectedFileBlobCache = protectedFileCacheState.blobByRequest;
/** 按受保护引用（`resource://…` / `local://…`）索引的已加载文件。 */
export const protectedFileBySource = protectedFileCacheState.fileBySource;
export const protectedFileMissingSources = protectedFileCacheState.missingSources;
export const protectedFileFailureCache = protectedFileCacheState.failures;
export const protectedFileInflight = protectedFileCacheState.inflight;

/** 已经加载过的受保护文件（渲染期直接用它的 blobURL，避免闪一下）。 */
export function getCachedProtectedFile(sourceURL: string | null | undefined): LoadedProtectedFile | null {
  if (!sourceURL) {
    return null;
  }
  return protectedFileBySource.get(sourceURL) ?? null;
}
