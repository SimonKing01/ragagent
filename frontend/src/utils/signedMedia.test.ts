import test from 'node:test';
import assert from 'node:assert/strict';
import { getSignedMediaUrl, resetSignedMediaCache } from './signedMedia.ts';

/**
 * 签名 URL 的去重口径：同一「知识库 + 引用」全页只请求一次。
 *
 * <p>分块渲染（80ms 防抖）与水合后会各触发一次，预览组件也会再要一次；
 * 不做在途合并时一个 20MB 视频会连发 2–3 条 `/files/signed-url`。</p>
 */
test('同一引用并发只请求一次，结果复用', async () => {
  resetSignedMediaCache();
  let calls = 0;
  const mint = async () => {
    calls += 1;
    await new Promise((r) => setTimeout(r, 10));
    return { url: 'https://cdn.example.com/r/token-1' };
  };

  const [a, b] = await Promise.all([
    getSignedMediaUrl('kb-1', 'local://1/exports/a.mp4', mint),
    getSignedMediaUrl('kb-1', 'local://1/exports/a.mp4', mint),
  ]);
  assert.equal(a, b);
  assert.equal(a, 'https://cdn.example.com/r/token-1');
  assert.equal(calls, 1, '并发同引用应只请求一次');
});

test('不同知识库 / 不同引用各自独立', async () => {
  resetSignedMediaCache();
  let calls = 0;
  const mint = async () => {
    calls += 1;
    return { url: `https://cdn.example.com/r/token-${calls}` };
  };
  await getSignedMediaUrl('kb-1', 'local://1/exports/a.mp4', mint);
  await getSignedMediaUrl('kb-1', 'local://1/exports/a.mp4', mint);
  assert.equal(calls, 1, '二次调用命中缓存');

  await getSignedMediaUrl('kb-2', 'local://1/exports/a.mp4', mint);
  assert.equal(calls, 2, '换知识库要重新签发');
});
