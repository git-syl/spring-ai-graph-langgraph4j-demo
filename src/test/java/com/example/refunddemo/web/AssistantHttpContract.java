package com.example.refunddemo.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Shared acceptance contract: deterministic native tool calls and real provider use the same HTTP flows. */
abstract class AssistantHttpContract {
    @LocalServerPort int port;
    final ObjectMapper json = new ObjectMapper();
    final HttpClient http = HttpClient.newHttpClient();
    @AfterEach void closeClient() { http.close(); }

    @Test
    void assistantRemembersConversationAndDoesNotRefundPolicyQuestions() throws Exception {
        String id = id();
        var greeting = chat(id, "你好，我的代号是蓝鲸42，请记住，简单打个招呼。");
        assertThat(greeting.path("refunds").isEmpty()).isTrue();
        var remembered = chat(id, "我的代号是什么？");
        assertThat(remembered.path("answer").asText()).contains("蓝鲸42");
        var policy = chat(id, "订单 O-1001 的退款规则是什么？只咨询，不执行退款。");
        assertThat(policy.path("refunds").isEmpty()).isTrue();
        assertThat(policy.path("toolsCalled").isEmpty()).isTrue();
        var isolated = chat(id(), "我的代号是什么？");
        assertThat(isolated.path("answer").asText()).doesNotContain("蓝鲸42");
    }

    @Test
    void multiTurnClarificationNativeToolApprovalAndStatusQuery() throws Exception {
        String id = id();
        var question = chat(id, "我想申请退款。");
        assertThat(question.path("refunds").isEmpty()).isTrue();
        assertThat(question.path("answer").asText()).contains("订单");
        var waiting = chat(id, "订单 O-2001，原因是商品质量问题，请帮我办理。");
        assertThat(waiting.path("toolsCalled").toString()).contains("start_refund_workflow");
        var refund = waiting.path("refunds").get(0);
        assertThat(refund.path("status").asText()).isEqualTo("APPROVAL_REQUIRED");
        assertThat(refund.path("trace").toString()).doesNotContain("execute_refund", "intent");
        String thread = refund.path("threadId").asText();

        var textConfirmation = chat(id, "我同意退款，确认。");
        assertThat(textConfirmation.path("refunds").get(0).path("status").asText()).isEqualTo("APPROVAL_REQUIRED");
        assertThat(post(approvalPath(id, thread), Map.of()).statusCode()).isEqualTo(400);
        String other = id();
        chat(other, "你好");
        assertThat(post(approvalPath(other, thread), Map.of("approved", true)).statusCode()).isEqualTo(404);
        var payload = Map.of("requestId", "approval-1", "approved", true);
        var approved = post(approvalPath(id, thread), payload);
        assertThat(approved.statusCode()).isEqualTo(200);
        var completed = json.readTree(approved.body());
        assertThat(completed.path("refunds").get(0).path("status").asText()).isEqualTo("COMPLETED");
        assertThat(completed.path("refunds").get(0).path("refund").path("amount").decimalValue()).isEqualByComparingTo("399");
        assertThat(post(approvalPath(id, thread), payload).body()).isEqualTo(approved.body());
        assertThat(post(approvalPath(id, thread), Map.of("approved", true)).statusCode()).isEqualTo(409);

        var status = chat(id, "请查询订单 O-2001 刚才那笔退款的当前进度。");
        assertThat(status.path("toolsCalled").toString()).contains("get_refund_status");
        assertThat(status.path("refunds").get(0).path("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void autoRefundIsIdempotentAcrossRequestRetriesAndRepeatedToolRequests() throws Exception {
        String id = id();
        var body = Map.of("conversationId", id, "requestId", "turn-1", "message", "订单 O-1001 因质量问题，请立即执行全额退款。");
        var first = post("/api/chat", body);
        assertThat(first.statusCode()).isEqualTo(200);
        var result = json.readTree(first.body());
        assertThat(result.path("toolsCalled").toString()).contains("start_refund_workflow");
        assertThat(result.path("refunds").get(0).path("status").asText()).isEqualTo("COMPLETED");
        assertThat(post("/api/chat", body).body()).isEqualTo(first.body());
        assertThat(post("/api/chat", Map.of("conversationId", id, "requestId", "turn-1", "message", "不同请求")).statusCode()).isEqualTo(409);
        var repeated = chat(id, "请再帮我办理订单 O-1001 的质量问题退款。");
        assertThat(repeated.path("refunds")).hasSize(1);
        assertThat(repeated.path("refunds").get(0).path("threadId")).isEqualTo(result.path("refunds").get(0).path("threadId"));
        assertThat(repeated.path("refunds").get(0).path("refund")).isEqualTo(result.path("refunds").get(0).path("refund"));
    }

    @Test
    void sseStreamsProviderTextAndGraphStepsThenCanDecline() throws Exception {
        String id = id();
        var response = post("/api/chat/stream", Map.of("conversationId", id, "message", "订单 O-2001，商品质量问题，请发起退款申请。"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElseThrow()).startsWith("text/event-stream");
        assertThat(response.body()).contains("event:token", "event:workflow_step", "event:approval_required", "event:result")
                .doesNotContain("event:error", "execute_refund");
        assertThat(response.body().split("event:token", -1).length).isGreaterThan(2);
        var refunds = json.readTree(get("/api/chat/" + id + "/refunds").body());
        String thread = refunds.get(0).path("threadId").asText();
        var declined = post(approvalPath(id, thread) + "/stream", Map.of("approved", false));
        assertThat(declined.body()).contains("event:workflow_step", "event:result", "DECLINED")
                .doesNotContain("execute_refund", "event:error");
    }

    @Test
    void expiredAndUnknownOrdersAreRejectedByGraph() throws Exception {
        for (String order : new String[]{"O-3001", "O-9999"}) {
            var result = chat(id(), "订单 " + order + " 因质量问题，请执行退款。");
            assertThat(result.path("toolsCalled").toString()).contains("start_refund_workflow");
            assertThat(result.path("refunds").get(0).path("status").asText()).isEqualTo("REJECTED");
            assertThat(result.path("refunds").get(0).path("trace").toString()).doesNotContain("execute_refund");
        }
    }

    @Test
    void validationAndRemovedDirectMutationRoutes() throws Exception {
        assertThat(post("/api/chat", Map.of("message", " ")).statusCode()).isEqualTo(400);
        assertThat(post("/api/chat", Map.of("conversationId", "bad/id", "message", "退款")).statusCode()).isEqualTo(400);
        assertThat(get("/api/chat/missing/refunds").statusCode()).isEqualTo(404);
        assertThat(post("/api/refunds", Map.of("query", "退款")).statusCode()).isEqualTo(404);
        assertThat(post("/api/refunds/missing/approval", Map.of("approved", true)).statusCode()).isEqualTo(404);
        assertThat(get("/api/rag/search").statusCode()).isEqualTo(404);
        assertThat(get("/api/refunds/graph").body()).contains("validate_request", "approval_gate");
    }

    JsonNode chat(String conversationId, String message) throws Exception {
        var response = post("/api/chat", Map.of("conversationId", conversationId, "message", message));
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    HttpResponse<String> post(String path, Map<String, ?> body) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofMinutes(4))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    static String id() { return "chat-" + UUID.randomUUID(); }
    static String approvalPath(String conversation, String thread) { return "/api/chat/" + conversation + "/refunds/" + thread + "/approval"; }
}
