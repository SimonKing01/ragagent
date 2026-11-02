package com.ragagent.platform.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** 类型名与 400 文案的钉子（消息原文会透给前端）。 */
class SystemSettingRegistryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void jsonTypeLabelUsesJsonTypeNames() throws Exception {
        assertThat(SystemSettingRegistry.jsonTypeLabel(MAPPER.nullNode())).isEqualTo("null");
        assertThat(SystemSettingRegistry.jsonTypeLabel(MAPPER.readTree("true"))).isEqualTo("boolean");
        assertThat(SystemSettingRegistry.jsonTypeLabel(MAPPER.readTree("1"))).isEqualTo("number");
        assertThat(SystemSettingRegistry.jsonTypeLabel(MAPPER.readTree("\"s\""))).isEqualTo("string");
        assertThat(SystemSettingRegistry.jsonTypeLabel(MAPPER.readTree("[1]"))).isEqualTo("array");
        assertThat(SystemSettingRegistry.jsonTypeLabel(MAPPER.readTree("{}"))).isEqualTo("object");
    }

    @Test
    void validationMessagesUseJsonTypeNames() throws Exception {
        assertThatThrownBy(() -> SystemSettingRegistry.encodeForType("int", MAPPER.readTree("true")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("expected integer, got boolean");
        assertThatThrownBy(() -> SystemSettingRegistry.encodeForType("string", MAPPER.readTree("[1]")))
                .hasMessage("expected string, got array");
        assertThatThrownBy(() -> SystemSettingRegistry.encodeForType("bool", MAPPER.readTree("{}")))
                .hasMessage("expected boolean, got object");
        assertThatThrownBy(() -> SystemSettingRegistry.encodeForType("string_list", MAPPER.readTree("3")))
                .hasMessage("expected string array, got number");
        assertThatThrownBy(() -> SystemSettingRegistry.encodeForType("string_list", MAPPER.readTree("[1]")))
                .hasMessage("expected string at index 0, got number");
    }
}
