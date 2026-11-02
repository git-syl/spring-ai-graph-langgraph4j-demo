package com.example.refunddemo.refund.graph;

import com.example.refunddemo.refund.schema.Order;
import com.example.refunddemo.refund.schema.PolicyDecision;
import com.example.refunddemo.refund.schema.RefundExecution;
import com.example.refunddemo.refund.schema.RefundCommand;
import com.example.refunddemo.refund.schema.RefundState;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import org.bsc.langgraph4j.serializer.plain_text.jackson.TypeMapper;
import org.bsc.langgraph4j.spring.ai.serializer.jackson.SpringAIJacksonStateSerializer;

import java.io.IOException;

/** 保留上游 Spring AI 消息适配器，并显式登记允许恢复的业务 record 类型。 */
public final class RefundStateSerializer extends SpringAIJacksonStateSerializer<RefundState> {
    private final ObjectMapper domainJson = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    public RefundStateSerializer() {
        super(RefundState::new);
        objectMapper.findAndRegisterModules();
        objectMapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        register(Order.class, "order");
        register(RefundCommand.class, "command");
        register(PolicyDecision.class, "policy");
        register(RefundExecution.class, "refund");
        // JSON 只携带字段，Map<String,Object> 会丢失具体 Java 类型；通过稳定别名恢复类型。
        typeMapper.register(new TypeMapper.Reference<Order>("order") { });
        typeMapper.register(new TypeMapper.Reference<RefundCommand>("command") { });
        typeMapper.register(new TypeMapper.Reference<PolicyDecision>("policy") { });
        typeMapper.register(new TypeMapper.Reference<RefundExecution>("refund") { });
    }

    private <T> void register(Class<T> type, String name) {
        // 给声明为 Object 的值写入类型标签；只处理明确登记的 DTO，不开放任意类恢复。
        // 单独的 domainJson 负责 record 字段，避免适配器调用自身造成无限递归。
        var module = new SimpleModule("refund-" + name);
        module.addSerializer(type, new StdSerializer<T>(type) {
            @Override
            public void serialize(T value, JsonGenerator generator, SerializerProvider provider) throws IOException {
                ObjectNode node = domainJson.valueToTree(value);
                node.put(TypeMapper.TYPE_PROPERTY, name);
                generator.writeTree(node);
            }
        });
        module.addDeserializer(type, new StdDeserializer<T>(type) {
            @Override
            public T deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                ObjectNode node = parser.readValueAsTree();
                node.remove(TypeMapper.TYPE_PROPERTY);
                return domainJson.treeToValue(node, type);
            }
        });
        objectMapper.registerModule(module);
    }
}
