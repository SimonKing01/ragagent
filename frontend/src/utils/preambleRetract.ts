/**
 * 工具调用前「前言」段落的撤回时机。
 *
 * 前言是模型在调用工具前先流出来的那段话。工具调用一到，这段内容会被撤回（折进思考
 * 卡片），若撤回太早，用户看到的是「话还没打完就没了」。打字机的节奏是既定的
 * （`useTypewriter`：不超过 100 字的积压在 0.42 秒内追平，更长时按最高 240 字/秒推进），
 * 因此撤回至少要留出一段与字数相称的时间。
 */

/** 与 useTypewriter 的 maxCps 对齐：超出 0.42 秒排水窗口后的推进速度（字/秒）。 */
const REVEAL_CHARS_PER_SECOND = 240;
/** 打字机的排水窗口：不超过这么多字的积压都在 0.42 秒内追平。 */
const DRAIN_SECONDS = 0.42;
const DRAIN_CHARS = REVEAL_CHARS_PER_SECOND * DRAIN_SECONDS;
/** 结尾留白，避免「最后一个字刚出现」与撤回发生在同一帧。 */
const SETTLE_MARGIN_MS = 250;
/** 下限：即使前言只有一个字，也让它停留一小会儿再消失。 */
const MIN_DELAY_MS = 800;

export function preambleRetractDelayMs(chars: number): number {
  const length = Number.isFinite(chars) && chars > 0 ? Math.floor(chars) : 0;
  if (length === 0) return MIN_DELAY_MS;
  const revealMs =
    length <= DRAIN_CHARS ? DRAIN_SECONDS * 1000 : (length / REVEAL_CHARS_PER_SECOND) * 1000;
  return Math.max(MIN_DELAY_MS, Math.round(revealMs + SETTLE_MARGIN_MS));
}
