/**
 * widget 新对话态右上角光晕的浓度计算。
 *
 * <p>同一个透明度，浅色（如品牌青）好看，黑色（#000）就是一团灰雾——所以按
 * 品牌色的相对亮度分档：深色压低到 7%，中间色 13%，浅色 18%。深色另外打开
 * 细弧线（`--embed-glow-arc`），避免只剩一片模糊灰。</p>
 */

/** 深色档：低于该亮度用最淡的浓度。 */
const DARK_LUMINANCE = 0.35;
/** 浅色档：高于该亮度用最浓的浓度。 */
const LIGHT_LUMINANCE = 0.7;

const ALPHA_DARK = 7;
const ALPHA_MID = 13;
const ALPHA_LIGHT = 18;
/** 无法解析颜色时的兜底（与中间档一致）。 */
const ALPHA_FALLBACK = 13;

/** `#rgb` / `#rrggbb` → [r, g, b]；解析失败返回 null。 */
function parseHex(color: string): [number, number, number] | null {
  const raw = color.trim()
  if (!raw.startsWith('#')) return null
  const hex = raw.slice(1)
  if (hex.length === 3) {
    const r = parseInt(hex[0] + hex[0], 16)
    const g = parseInt(hex[1] + hex[1], 16)
    const b = parseInt(hex[2] + hex[2], 16)
    return Number.isNaN(r + g + b) ? null : [r, g, b]
  }
  if (hex.length === 6) {
    const r = parseInt(hex.slice(0, 2), 16)
    const g = parseInt(hex.slice(2, 4), 16)
    const b = parseInt(hex.slice(4, 6), 16)
    return Number.isNaN(r + g + b) ? null : [r, g, b]
  }
  return null
}

/** 相对亮度（0~1，sRGB 近似）。非 HEX 颜色返回 null。 */
export function relativeLuminance(color: string | undefined | null): number | null {
  if (!color) return null
  const rgb = parseHex(color)
  if (!rgb) return null
  const [r, g, b] = rgb
  return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255
}

/** 光晕浓度（百分比数值）：深色 7 / 中间 13 / 浅色 18。 */
export function glowAlphaFor(color: string | undefined | null): number {
  const luminance = relativeLuminance(color)
  if (luminance == null) return ALPHA_FALLBACK
  if (luminance < DARK_LUMINANCE) return ALPHA_DARK
  if (luminance >= LIGHT_LUMINANCE) return ALPHA_LIGHT
  return ALPHA_MID
}

/** 深色品牌色需要额外的细弧线，否则只剩模糊灰。 */
export function glowNeedsArc(color: string | undefined | null): boolean {
  const luminance = relativeLuminance(color)
  return luminance != null && luminance < DARK_LUMINANCE
}

/** 直接产出写进 style 的 CSS 变量。 */
export function glowVars(color: string | undefined | null): Record<string, string> {
  if (!color) return {}
  const alpha = glowAlphaFor(color)
  return {
    '--embed-glow-alpha': `${alpha}%`,
    '--embed-glow-arc': glowNeedsArc(color) ? '1' : '0',
  }
}
