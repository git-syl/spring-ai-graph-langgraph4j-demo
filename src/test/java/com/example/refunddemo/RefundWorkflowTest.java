package com.example.refunddemo;

import com.example.refunddemo.refund.RefundPolicy;
import com.example.refunddemo.refund.RefundToolExecutor;
import com.example.refunddemo.refund.RefundWorkflow;
import com.example.refunddemo.refund.RefundWorkflowException;
import com.example.refunddemo.refund.graph.RefundStateSerializer;
import com.example.refunddemo.refund.schema.RefundCommand;
import com.example.refunddemo.refund.schema.RefundContext;
import com.example.refunddemo.refund.schema.RefundStatus;
import com.example.refunddemo.refund.schema.StepInfo;
import com.example.refunddemo.tool.OrderRefundTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bsc.langgraph4j.checkpoint.FileSystemSaver;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.support.ToolCallbacks;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefundWorkflowTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC);
    @TempDir Path directory;

    @Test
    void typedCommandRunsWithoutAnyModelDependency() throws Exception {
        var workflow = workflow();
        var events = new ArrayList<StepInfo>();
        var result = workflow.start("auto", command("O-1001"), events::add);
        assertThat(result.status()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(result.refund().amount()).isEqualByComparingTo("99.00");
        assertThat(result.trace()).containsExactly("validate_request", "lookup_order", "policy_check", "approval_gate", "execute_refund", "answer");
        assertThat(workflow.get("auto")).isEqualTo(result);
        assertThat(events.getFirst().status()).isEqualTo(StepInfo.Status.RUNNING);
        assertThat(events.getLast().finish()).isTrue();
        assertThat(events).allMatch(event -> event.timestamp().equals(CLOCK.instant()));
    }

    @Test
    void approvalRestoresDiskCheckpointWithFreshRuntimeContext() throws Exception {
        var original = new RefundWorkflow(context(), new FileSystemSaver(directory, new RefundStateSerializer()));
        var events = new ArrayList<StepInfo>();
        var waiting = original.start("manual", command("O-2001"), events::add);
        assertThat(waiting.status()).isEqualTo(RefundStatus.APPROVAL_REQUIRED);
        assertThat(waiting.refund()).isNull();
        assertThat(events).anyMatch(event -> event.step().equals("approval_gate") && event.status() == StepInfo.Status.WAITING);
        assertThat(events).noneMatch(event -> event.step().equals("execute_refund"));
        try (var files = Files.list(directory)) {
            assertThat(Files.readString(files.findFirst().orElseThrow())).doesNotContain("refund.context", "ChatClient", "stepWriter", "api-key");
        }
        var reloaded = new RefundWorkflow(context(), new FileSystemSaver(directory, new RefundStateSerializer()));
        var result = reloaded.approve("manual", true);
        assertThat(result.status()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(result.trace()).containsExactly("validate_request", "lookup_order", "policy_check", "approval_gate", "execute_refund", "answer");
        assertThat(result.refund().amount()).isEqualByComparingTo("399.00");
    }

    @Test
    void rejectionDoesNotExecuteRefund() throws Exception {
        var workflow = workflow();
        workflow.start("decline", command("O-2001"));
        var result = workflow.approve("decline", false);
        assertThat(result.status()).isEqualTo(RefundStatus.DECLINED);
        assertThat(result.refund()).isNull();
        assertThat(result.trace()).doesNotContain("execute_refund");
    }

    @Test
    void expiredUnknownAndAlreadyRefundedOrdersNeverExecuteRefund() throws Exception {
        for (String order : List.of("O-3001", "O-9999", "O-4001")) {
            var result = workflow().start("order-" + order, command(order));
            assertThat(result.status()).isEqualTo(order.equals("O-4001") ? RefundStatus.ALREADY_REFUNDED : RefundStatus.REJECTED);
            assertThat(result.refund()).isNull();
            assertThat(result.trace()).doesNotContain("execute_refund");
        }
    }

    @Test
    void unknownThreadsDuplicateStartsAndRepeatedApprovalsAreRejected() throws Exception {
        var workflow = workflow();
        assertThatThrownBy(() -> workflow.approve("missing", true)).isInstanceOfSatisfying(RefundWorkflowException.class,
                error -> assertThat(error.kind()).isEqualTo(RefundWorkflowException.Kind.NOT_FOUND));
        workflow.start("once", command("O-2001"));
        assertThatThrownBy(() -> workflow.start("once", command("O-2001"))).hasMessageContaining("已存在");
        workflow.approve("once", true);
        assertThatThrownBy(() -> workflow.approve("once", true)).hasMessageContaining("不能重复");
        assertThatThrownBy(() -> workflow.start("bad/id", command("O-1001"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RefundCommand("", "质量问题")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RefundCommand("O-1001", " ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentApprovalsAllowExactlyOneExecution() throws Exception {
        var workflow = workflow();
        workflow.start("race", command("O-2001"));
        Callable<String> approve = () -> {
            try { return workflow.approve("race", true).status().name(); }
            catch (RefundWorkflowException error) { return error.kind().name(); }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var outcomes = executor.invokeAll(List.of(approve, approve));
            assertThat(List.of(outcomes.get(0).get(), outcomes.get(1).get())).containsExactlyInAnyOrder("COMPLETED", "CONFLICT");
        }
    }

    @Test
    void failingObserverCannotTurnSuccessfulRefundIntoRetryableFailure() throws Exception {
        var result = workflow().start("observer", command("O-1001"), event -> { throw new IllegalStateException("disconnected"); });
        assertThat(result.status()).isEqualTo(RefundStatus.COMPLETED);
    }

    private static RefundCommand command(String orderId) { return new RefundCommand(orderId, "质量问题"); }
    private static RefundWorkflow workflow() throws Exception { return new RefundWorkflow(context(), new MemorySaver()); }
    private static RefundContext context() {
        return new RefundContext(new RefundToolExecutor(Arrays.asList(ToolCallbacks.from(new OrderRefundTools(CLOCK))),
                new ObjectMapper().findAndRegisterModules()), new RefundPolicy(Duration.ofDays(30), new BigDecimal("300")), CLOCK, event -> { });
    }
}
