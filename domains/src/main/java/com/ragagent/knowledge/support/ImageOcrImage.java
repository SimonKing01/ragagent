package com.ragagent.knowledge.support;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 送 OCR 前的图片预处理：大图等比缩小。
 *
 * <p>扫描件与 PPT 导出图常见 1920~4000 像素宽，原样送识别既慢又抬内存峰值
 * （OCR 服务按像素做检测/识别）。这里把最长边限制到阈值内：</p>
 *
 * <ul>
 *   <li>读取时用 {@link ImageReadParam#setSourceSubsampling} 降采样，避免先把整幅大图
 *       解进堆（8000×6000 的图解码后就要 ~190MB）；</li>
 *   <li>按原格式重编码（JPEG 质量 0.85，其余 PNG）；</li>
 *   <li>任何一步失败都原样返回——缩图只是优化，不能成为解析失败的理由。</li>
 * </ul>
 */
public final class ImageOcrImage {

    private static final Logger log = LoggerFactory.getLogger(ImageOcrImage.class);

    private ImageOcrImage() {
    }

    /**
     * 最长边超过 {@code maxSide} 时等比缩小；否则（或处理失败）返回原字节。
     */
    public static byte[] limitMaxSide(byte[] original, int maxSide) {
        if (original == null || original.length == 0 || maxSide <= 0) {
            return original;
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(original))) {
            if (input == null) {
                return original;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return original;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                int longest = Math.max(width, height);
                if (longest <= maxSide) {
                    return original;
                }
                // 读取阶段先按整数倍降采样，减少解码内存
                int subsample = Math.max(1, (int) Math.floor((double) longest / maxSide));
                ImageReadParam param = reader.getDefaultReadParam();
                if (subsample > 1) {
                    param.setSourceSubsampling(subsample, subsample, 0, 0);
                }
                BufferedImage decoded = reader.read(0, param);
                BufferedImage scaled = scaleToFit(decoded, maxSide);
                byte[] encoded = encode(scaled, formatOf(reader));
                return encoded == null ? original : encoded;
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            log.debug("ocr image downscale skipped", e);
            return original;
        }
    }

    private static BufferedImage scaleToFit(BufferedImage source, int maxSide) {
        int longest = Math.max(source.getWidth(), source.getHeight());
        if (longest <= maxSide) {
            return source;
        }
        double ratio = (double) maxSide / longest;
        int width = Math.max(1, (int) Math.round(source.getWidth() * ratio));
        int height = Math.max(1, (int) Math.round(source.getHeight() * ratio));
        // JPEG 无 alpha，统一画到 RGB 画布上，避免编码失败
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    private static byte[] encode(BufferedImage image, String format) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            boolean written = ImageIO.write(image, format, out);
            if (!written) {
                return null;
            }
            return out.toByteArray();
        } catch (Exception e) {
            log.debug("ocr image encode failed", e);
            return null;
        }
    }

    private static String formatOf(ImageReader reader) {
        String name;
        try {
            name = reader.getFormatName();
        } catch (IOException e) {
            return "jpg";
        }
        if (name == null) {
            return "jpg";
        }
        String lower = name.toLowerCase();
        if (lower.contains("png")) {
            return "png";
        }
        if (lower.contains("gif")) {
            return "gif";
        }
        if (lower.contains("bmp")) {
            return "bmp";
        }
        return "jpg";
    }
}
