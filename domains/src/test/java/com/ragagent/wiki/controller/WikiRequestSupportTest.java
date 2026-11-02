package com.ragagent.wiki.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.contract.error.BizException;
import org.junit.jupiter.api.Test;

/** 请求体绑定的 400 文案钉子（非对象 body / EOF）。 */
class WikiRequestSupportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void nonObjectBodyYieldsJsonTypeName() {
        assertThatThrownBy(() -> WikiRequestSupport.readJsonBody(MAPPER, "[1,2]"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).appError().message())
                        .isEqualTo("Invalid request body: expected JSON object, got array"));
        assertThatThrownBy(() -> WikiRequestSupport.readJsonBody(MAPPER, "\"s\""))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).appError().message())
                        .isEqualTo("Invalid request body: expected JSON object, got string"));
    }

    @Test
    void emptyBodyKeepsEofMessage() {
        assertThatThrownBy(() -> WikiRequestSupport.readJsonBody(MAPPER, " "))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).appError().message())
                        .isEqualTo("Invalid request body: EOF"));
    }
}
