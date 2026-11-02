package com.example.refunddemo.config;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.refunddemo.tool.OrderRefundTools;
import com.example.refunddemo.refund.RefundPolicy;
import com.example.refunddemo.refund.RefundToolExecutor;
import com.example.refunddemo.refund.RefundWorkflow;
import com.example.refunddemo.refund.schema.RefundContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.springframework.context.annotation.Scope;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.http.HttpClient;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class RefundDemoConfig {

    @Bean
    Clock refundClock() {
        return Clock.systemUTC();
    }

    @Bean
    ObjectMapper refundToolJson() {
        return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    RefundContext refundContext(List<ToolCallback> refundToolCallbacks, ObjectMapper json, Clock clock) {
        return new RefundContext(new RefundToolExecutor(refundToolCallbacks, json),
                new RefundPolicy(Duration.ofDays(30), new BigDecimal("300.00")), clock, event -> { });
    }

    @Bean
    RefundWorkflow refundWorkflow(RefundContext context) throws Exception {
        return new RefundWorkflow(context, new MemorySaver());
    }

    @Bean(destroyMethod = "close")
    ExecutorService refundEventExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean(destroyMethod = "close")
    HttpClient modelHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30))
                .version(HttpClient.Version.HTTP_1_1).build();
    }

    @Bean
    @Scope("prototype")
    RestClient.Builder modelRestClientBuilder(HttpClient http) {
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMinutes(2));
        return RestClient.builder().requestFactory(factory);
    }

    @Bean
    @Scope("prototype")
    WebClient.Builder modelWebClientBuilder(HttpClient http) {
        return WebClient.builder().clientConnector(new JdkClientHttpConnector(http));
    }

    /** The same callback list can be assembled from existing application tools. */
    @Bean(name = "refundToolCallbacks")
    List<ToolCallback> refundToolCallbacks(OrderRefundTools tools) {
        return Arrays.asList(ToolCallbacks.from(tools));
    }
}
