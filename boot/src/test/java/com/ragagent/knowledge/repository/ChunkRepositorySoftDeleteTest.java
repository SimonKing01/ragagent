package com.ragagent.knowledge.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.ragagent.TestSchema;
import com.ragagent.knowledge.domain.Chunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 软删守卫回归：chunk 计数必须排除已软删行。
 *
 * <p>现场：导入文档后 finalizing 阶段调 {@code countByKnowledgeIdAndChunkType}，
 * 生成的 SELECT 未带 {@code deleted_at} 谓词，被 {@code SoftDeleteFilterGuard} 拦下，
 * 表现为 Trace 里报 {@code SELECT chunks 未带 deleted_at 谓词}。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChunkRepositorySoftDeleteTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ChunkRepository repo;

    @BeforeEach
    void seed() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
    }

    @Test
    void countByKnowledgeIdAndChunkTypeExcludesSoftDeletedRows() {
        repo.insert(chunk("live"));
        repo.insert(chunk("deleted"));
        jdbc.update("UPDATE chunks SET deleted_at = now() WHERE id = ?", "deleted");

        assertThat(repo.countByKnowledgeIdAndChunkType("k1", "text")).isEqualTo(1);
    }

    private Chunk chunk(String id) {
        Chunk c = new Chunk();
        c.setId(id);
        c.setTenantId(10002L);
        c.setKnowledgeId("k1");
        c.setKnowledgeBaseId("kb1");
        c.setContent("x");
        c.setChunkType("text");
        return c;
    }
}
