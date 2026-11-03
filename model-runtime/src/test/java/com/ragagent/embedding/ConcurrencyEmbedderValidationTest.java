package com.ragagent.embedding;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ragagent.contract.model.Embedder;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 统一出口的校验：任何 provider 返回的条数/维度不对，都必须在最内层包装处失败。
 */
class ConcurrencyEmbedderValidationTest {

    /** 故意少返回一条。 */
    private static Embedder shortEmbedder() {
        return new Embedder() {
            @Override
            public float[] embed(String text) {
                return new float[] {1.0f, 2.0f};
            }

            @Override
            public List<float[]> batchEmbed(List<String> texts) {
                return List.of(new float[] {1.0f, 2.0f});
            }

            @Override
            public String getModelName() {
                return "fake";
            }

            @Override
            public int getDimensions() {
                return 2;
            }

            @Override
            public String getModelId() {
                return "fake-1";
            }
        };
    }

    @Test
    @DisplayName("批量返回条数不足 → 校验失败（不再按位置静默配行）")
    void rejectsShortBatch() {
        ConcurrencyEmbedder embedder = new ConcurrencyEmbedder(shortEmbedder(), 0, null);
        assertThrows(IllegalStateException.class,
                () -> embedder.batchEmbed(List.of("a", "b")));
    }

    @Test
    @DisplayName("单条返回空向量 → 校验失败")
    void rejectsEmptySingleVector() {
        Embedder empty = new Embedder() {
            @Override
            public float[] embed(String text) {
                return new float[0];
            }

            @Override
            public List<float[]> batchEmbed(List<String> texts) {
                return List.of();
            }

            @Override
            public String getModelName() {
                return "fake";
            }

            @Override
            public int getDimensions() {
                return 2;
            }

            @Override
            public String getModelId() {
                return "fake-1";
            }
        };
        ConcurrencyEmbedder embedder = new ConcurrencyEmbedder(empty, 0, null);
        assertThrows(IllegalStateException.class, () -> embedder.embed("a"));
    }
}
