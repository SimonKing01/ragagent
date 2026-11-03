package com.ragagent.agentmanagement.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ragagent.agentmanagement.domain.CustomAgentEntity;
import com.ragagent.agentmanagement.mapper.CustomAgentMapper;
import com.ragagent.contract.json.JsonValues;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link AgentLookupAdapter} 的语义钉子（A③-⑰ im→agentmanagement 清零时新增的面）：
 * <ul>
 *   <li>{@code hasAgentRow} 与 {@code isAgentOwned} 的分道——内建 agent 无行时前者
 *       {@code false}、后者 {@code true}（im 换绑按"行存在"判存，内建 id 未落行不可绑定）；</li>
 *   <li>{@code defaultedConfig} 的边界：空 / 非法 JSON / 非对象 ⇒ {@code null}，
 *       合法对象 ⇒ 就地补缺省且保留原字段。</li>
 * </ul>
 */
class AgentLookupAdapterTest {

    private static CustomAgentEntity row(String id, long tenantId) {
        CustomAgentEntity e = new CustomAgentEntity();
        e.setId(id);
        e.setTenantId(tenantId);
        return e;
    }

    @Test
    @DisplayName("hasAgentRow：行在且租户匹配 ⇒ true；内建 id 无行 ⇒ false（与 isAgentOwned 分道）")
    void hasAgentRowSemantics() {
        CustomAgentMapper mapper = mock(CustomAgentMapper.class);
        AgentLookupAdapter adapter = new AgentLookupAdapter(mapper, null, null);

        when(mapper.getByIdAndTenant("a1", 13L)).thenReturn(row("a1", 13L));
        assertTrue(adapter.hasAgentRow(13L, "a1"));

        // 防御性第二道：行租户列与入参不一致 ⇒ false
        when(mapper.getByIdAndTenant("a2", 13L)).thenReturn(row("a2", 14L));
        assertFalse(adapter.hasAgentRow(13L, "a2"));

        // 内建 id 无行：hasAgentRow false；isAgentOwned 走注册表兜底 true
        assertFalse(adapter.hasAgentRow(13L, "builtin-quick-answer"));
        assertTrue(adapter.isAgentOwned(13L, "builtin-quick-answer"));

        // 空 id / 查询异常一律 false
        assertFalse(adapter.hasAgentRow(13L, "   "));
        when(mapper.getByIdAndTenant("a3", 13L)).thenThrow(new RuntimeException("db down"));
        assertFalse(adapter.hasAgentRow(13L, "a3"));
    }

    @Test
    @DisplayName("defaultedConfig：空/非法/非对象 ⇒ null；合法对象 ⇒ 补缺省且保留原字段")
    void defaultedConfigSemantics() {
        AgentLookupAdapter adapter = new AgentLookupAdapter(null, null, null);

        assertNull(adapter.defaultedConfig(null));
        assertNull(adapter.defaultedConfig(""));
        assertNull(adapter.defaultedConfig("{oops"));
        assertNull(adapter.defaultedConfig("[1,2]"));

        var cfg = adapter.defaultedConfig("{\"agentMode\":\"quick-answer\"}");
        assertNotNull(cfg);
        // G1-l：返回值已改纯 JDK 值 ⇒ 断言走契约侧读面 JsonValues（语义与 JsonNode 读面逐条对齐）
        assertEquals("quick-answer", JsonValues.asText(JsonValues.get(cfg, "agentMode"), ""), "原字段保留");
        assertEquals(10, JsonValues.asInt(JsonValues.get(cfg, "maxIterations"), 0), "缺省 maxIterations 已补");
        assertNotNull(JsonValues.get(cfg, "questionSuggestions"), "缺省 questionSuggestions 已补");
    }
}
