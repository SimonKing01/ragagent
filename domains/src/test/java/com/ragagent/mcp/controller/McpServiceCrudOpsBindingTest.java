package com.ragagent.mcp.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.contract.error.BizException;
import org.junit.jupiter.api.Test;

/** updateMCPService 的请求体形态 400 文案钉子（原先 0 金片覆盖）。 */
class McpServiceCrudOpsBindingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void nonObjectBodyYieldsJsonTypeName() throws Exception {
        McpServiceCrudOps ops = new McpServiceCrudOps(null);
        assertThatThrownBy(() -> ops.updateMCPService("svc", MAPPER.readTree("[1]")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).appError().message())
                .isEqualTo("expected JSON object, got array");
    }
}
