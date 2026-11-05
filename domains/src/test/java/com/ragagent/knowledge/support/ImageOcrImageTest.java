package com.ragagent.knowledge.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** 送 OCR 前的大图缩小：只缩大图，失败不阻断。 */
class ImageOcrImageTest {

    private static byte[] jpeg(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    private static BufferedImage decode(byte[] bytes) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    @Test
    void shrinksOversizedImageToMaxSide() throws Exception {
        byte[] original = jpeg(4000, 3000);

        byte[] shrunk = ImageOcrImage.limitMaxSide(original, 1600);

        BufferedImage image = decode(shrunk);
        assertThat(Math.max(image.getWidth(), image.getHeight())).isLessThanOrEqualTo(1600);
        assertThat(shrunk.length).isLessThan(original.length);
        // 等比：4000×3000 → 1600×1200
        assertThat(image.getWidth()).isEqualTo(1600);
        assertThat(image.getHeight()).isEqualTo(1200);
    }

    @Test
    void keepsImagesAlreadyWithinLimit() throws Exception {
        byte[] original = jpeg(800, 600);

        byte[] result = ImageOcrImage.limitMaxSide(original, 1600);

        assertThat(result).isSameAs(original);
    }

    @Test
    void treatsTallImagesByLongestSide() throws Exception {
        byte[] original = jpeg(600, 2400);

        BufferedImage image = decode(ImageOcrImage.limitMaxSide(original, 1600));

        assertThat(image.getHeight()).isEqualTo(1600);
        assertThat(image.getWidth()).isEqualTo(400);
    }

    @Test
    void returnsOriginalWhenBytesAreNotAnImage() {
        byte[] garbage = new byte[] {1, 2, 3, 4};

        assertThat(ImageOcrImage.limitMaxSide(garbage, 1600)).isSameAs(garbage);
        assertThat(ImageOcrImage.limitMaxSide(new byte[0], 1600)).isEmpty();
        assertThat(ImageOcrImage.limitMaxSide(null, 1600)).isNull();
    }
}
