package com.ragagent.platform.web;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.ragagent.contract.model.PromptCacheStatus;
import com.ragagent.contract.model.ResponseType;
import java.io.IOException;

/**
 * 契约载荷的 Jackson 装配：把线格式（键名/省略策略/枚举字面量）从契约类里剥离到 mixin 与
 * 显式 ser/deser，使 {@code contract.*} 保持纯 JDK。**所有**读写这些载荷的 mapper 都必须注册本模块，
 * 否则键名与省略策略会回退成 Jackson 默认（= 线格式静默变化）。
 *
 * <p>注册点：{@link JsonMappers#lenient()}（jsonb 读路径）、{@code platform.event.EventJson}（事件）、
 * {@code platform.stream.StreamJson}（SSE 流）、{@code modelcontext.JsonValues}（上下文预算）、
 * boot 的 Spring MVC mapper、Langfuse 上报 mapper。</p>
 */
public final class ContractJsonModule extends SimpleModule {

    private static final long serialVersionUID = 1L;

    /** 构造即完成全部注册（幂等：一次装配一份）。 */
    public ContractJsonModule() {
        super("ragagent-contract-json");
        ContractJsonMixins.mappings().forEach(this::setMixInAnnotation);

        addSerializer(ResponseType.class, new ResponseTypeSerializer());
        addDeserializer(ResponseType.class, new ResponseTypeDeserializer());
        addSerializer(PromptCacheStatus.class, new PromptCacheStatusSerializer());
        addDeserializer(PromptCacheStatus.class, new PromptCacheStatusDeserializer());
    }

    private static final class ResponseTypeSerializer extends JsonSerializer<ResponseType> {
        @Override
        public void serialize(ResponseType v, JsonGenerator g, SerializerProvider p) throws IOException {
            g.writeString(v.value());
        }
    }

    private static final class ResponseTypeDeserializer extends JsonDeserializer<ResponseType> {
        @Override
        public ResponseType deserialize(JsonParser p, DeserializationContext c) throws IOException {
            return ResponseType.fromValue(p.getText());
        }
    }

    private static final class PromptCacheStatusSerializer extends JsonSerializer<PromptCacheStatus> {
        @Override
        public void serialize(PromptCacheStatus v, JsonGenerator g, SerializerProvider p) throws IOException {
            g.writeString(v.value());
        }
    }

    private static final class PromptCacheStatusDeserializer extends JsonDeserializer<PromptCacheStatus> {
        @Override
        public PromptCacheStatus deserialize(JsonParser p, DeserializationContext c) throws IOException {
            return PromptCacheStatus.fromValue(p.getText());
        }
    }
}
