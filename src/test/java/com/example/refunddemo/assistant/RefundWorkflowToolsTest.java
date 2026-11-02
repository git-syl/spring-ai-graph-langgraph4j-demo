package com.example.refunddemo.assistant;

import com.example.refunddemo.refund.RefundWorkflow;
import com.example.refunddemo.refund.RefundWorkflowException;
import com.example.refunddemo.refund.schema.RefundCommand;
import com.example.refunddemo.refund.schema.RefundResult;
import com.example.refunddemo.refund.schema.RefundStatus;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RefundWorkflowToolsTest {
    @Test
    void failureBeforeAnyCheckpointDoesNotPoisonTheConversation() {
        var workflow = mock(RefundWorkflow.class);
        var tools = new RefundWorkflowTools(workflow);
        var conversation = new ConversationRegistry().getOrCreate("before-start");
        conversation.observeUser("请退款 O-2001，买错了");
        var context = context(conversation);
        when(workflow.start(anyString(), any(RefundCommand.class), any()))
                .thenThrow(new IllegalStateException("Cannot create checkpoint"))
                .thenAnswer(invocation -> waiting(invocation.getArgument(0)));
        when(workflow.get(anyString())).thenThrow(new RefundWorkflowException(
                RefundWorkflowException.Kind.NOT_FOUND, "No checkpoint"));

        assertThrows(IllegalStateException.class, () -> tools.startRefund("O-2001", "买错了", context));
        assertTrue(tools.results(conversation).isEmpty());
        assertEquals(RefundStatus.APPROVAL_REQUIRED, tools.startRefund("O-2001", "买错了", context).status());
        verify(workflow, times(2)).start(anyString(), any(RefundCommand.class), any());
    }

    @Test
    void failureWithAnExistingCheckpointReusesTheOriginalWorkflow() {
        var workflow = mock(RefundWorkflow.class);
        var tools = new RefundWorkflowTools(workflow);
        var conversation = new ConversationRegistry().getOrCreate("after-start");
        conversation.observeUser("请退款 O-2001，买错了");
        var context = context(conversation);
        when(workflow.start(anyString(), any(RefundCommand.class), any()))
                .thenThrow(new IllegalStateException("Response failed after checkpoint"));
        when(workflow.get(anyString())).thenAnswer(invocation -> waiting(invocation.getArgument(0)));

        assertThrows(IllegalStateException.class, () -> tools.startRefund("O-2001", "买错了", context));
        var result = tools.startRefund("O-2001", "买错了", context);
        assertEquals(RefundStatus.APPROVAL_REQUIRED, result.status());
        assertEquals(result, tools.results(conversation).getFirst());
        verify(workflow, times(1)).start(anyString(), any(RefundCommand.class), any());
    }

    @Test
    void uncertainCheckpointLookupNeverStartsASecondWorkflow() {
        var workflow = mock(RefundWorkflow.class);
        var tools = new RefundWorkflowTools(workflow);
        var conversation = new ConversationRegistry().getOrCreate("uncertain");
        conversation.observeUser("请退款 O-2001，买错了");
        var context = context(conversation);
        when(workflow.start(anyString(), any(RefundCommand.class), any()))
                .thenThrow(new IllegalStateException("Uncertain start"));
        when(workflow.get(anyString())).thenThrow(new RefundWorkflowException(
                RefundWorkflowException.Kind.FAILED, "Checkpoint store unavailable"))
                .thenAnswer(invocation -> waiting(invocation.getArgument(0)));

        assertThrows(IllegalStateException.class, () -> tools.startRefund("O-2001", "买错了", context));
        assertEquals(RefundStatus.APPROVAL_REQUIRED, tools.startRefund("O-2001", "买错了", context).status());
        verify(workflow, times(1)).start(anyString(), any(RefundCommand.class), any());
    }

    private static ToolContext context(ConversationRegistry.Conversation conversation) {
        return new ToolContext(Map.of(AssistantToolContext.KEY,
                new AssistantToolContext("test", conversation, event -> { })));
    }

    private static RefundResult waiting(String threadId) {
        return new RefundResult(threadId, RefundStatus.APPROVAL_REQUIRED, "等待审批", true,
                new RefundCommand("O-2001", "买错了"), null, null, null, List.of());
    }
}
