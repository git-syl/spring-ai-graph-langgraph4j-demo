package com.example.refunddemo.config;

import com.example.refunddemo.assistant.ChatAssistant;
import com.example.refunddemo.assistant.ConversationRegistry;
import com.example.refunddemo.assistant.RefundWorkflowTools;
import com.example.refunddemo.refund.RefundWorkflow;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionAdvisors;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.nio.charset.StandardCharsets;

@Configuration(proxyBeanMethods = false)
public class AssistantConfig {
    @Bean
    SessionService sessionService() {
        // 聊天 Session 保存消息历史；此内存实现重启即丢失，不是 LangGraph4j 的 saver。
        return DefaultSessionService.builder().sessionRepository(InMemorySessionRepository.builder().build()).build();
    }

    @Bean
    ConversationRegistry conversationRegistry() { return new ConversationRegistry(); }

    @Bean
    RefundWorkflowTools refundWorkflowTools(RefundWorkflow workflow) { return new RefundWorkflowTools(workflow); }

    @Bean
    ChatClient assistantChatClient(ChatModel model, SessionService sessions, ObjectProvider<ObservationRegistry> registries) {
        var observations = registries.getIfAvailable(() -> ObservationRegistry.NOOP);
        var manager = DefaultToolCallingManager.builder().observationRegistry(observations)
                // 约束一轮模型工具循环；图的步骤限制与支付幂等仍需在各自层次实现。
                .maxTotalToolCalls(4).maxCallsPerTool(2)
                .toolExecutionExceptionProcessor(new DefaultToolExecutionExceptionProcessor(true)).build();
        // 2.1.0-RC1 的 ChatClient 自动注册工具 Advisor，这里定制它，不再叠加第二套循环。
        return ChatClient.builder(model, observations, null, null, ToolCallingAdvisor.builder().toolCallingManager(manager))
                .defaultAdvisors(SessionAdvisors.builder(sessions).build()).build();
    }

    @Bean
    ChatAssistant chatAssistant(ChatClient client, ConversationRegistry conversations, RefundWorkflowTools tools,
                                ObjectMapper json, @Value("classpath:prompts/chat-assistant.txt") Resource prompt) throws Exception {
        return new ChatAssistant(client, conversations, tools, json, prompt.getContentAsString(StandardCharsets.UTF_8));
    }
}
