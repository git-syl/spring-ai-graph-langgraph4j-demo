package com.example.refunddemo.assistant;

import com.example.refunddemo.refund.RefundWorkflow;
import com.example.refunddemo.refund.RefundWorkflowException;
import com.example.refunddemo.refund.schema.RefundCommand;
import com.example.refunddemo.refund.schema.RefundResult;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.UUID;

/** 模型只看到启动/查询退款能力；原始支付工具和审批方法不注册给模型。 */
public final class RefundWorkflowTools {
    private final RefundWorkflow workflow;

    public RefundWorkflowTools(RefundWorkflow workflow) { this.workflow = workflow; }

    @Tool(name = "start_refund_workflow", description = "用户明确要求执行退款且已提供订单号和退款原因时，启动退款工作流。"
            + "仅咨询政策、查询进度或信息缺失时不要调用。返回 APPROVAL_REQUIRED 表示等待用户审批，不能声称已退款。")
    public RefundResult startRefund(@ToolParam(description = "用户在当前会话提供的订单号") String orderId,
                                     @ToolParam(description = "用户提供的退款原因") String reason, ToolContext context) {
        var runtime = AssistantToolContext.from(context);
        var command = new RefundCommand(orderId, reason);
        if (!runtime.conversation.mentionedOrders.contains(command.orderId())) {
            throw new IllegalArgumentException("订单号必须由当前会话的用户提供，不能猜测");
        }
        runtime.recordTool("start_refund_workflow");
        RefundResult result;
        synchronized (runtime.conversation.refundThreads) {
            var existing = runtime.conversation.refundThreads.get(command.orderId());
            if (existing != null) {
                // 模型可能重复提议同一工具调用；复用原流程，即使它已拒绝或已结束也不新建。
                result = workflow.get(existing);
            }
            else {
                // 执行前预约“订单 → thread”关联，避免图已开始但工具响应失败后又新建一笔。
                String threadId = "refund-" + UUID.randomUUID();
                runtime.conversation.refundThreads.put(command.orderId(), threadId);
                try {
                    result = workflow.start(threadId, command, step -> runtime.emit("workflow_step", step));
                }
                catch (RuntimeException failure) {
                    // 只有明确查到无 checkpoint 才释放预约；查询失败属于结果未知，保留原关联。
                    try { workflow.get(threadId); }
                    catch (RefundWorkflowException lookup) {
                        if (lookup.kind() == RefundWorkflowException.Kind.NOT_FOUND) {
                            runtime.conversation.refundThreads.remove(command.orderId(), threadId);
                        }
                    }
                    catch (RuntimeException lookup) { failure.addSuppressed(lookup); }
                    throw failure;
                }
            }
        }
        publish(runtime, result);
        return result;
    }

    @Tool(name = "get_refund_status", description = "查询当前会话中已发起的退款流程的真实状态，包括是否等待审批或已完成。不会发起或批准退款。")
    public RefundResult getStatus(@ToolParam(description = "要查询的订单号") String orderId, ToolContext context) {
        var runtime = AssistantToolContext.from(context);
        runtime.recordTool("get_refund_status");
        String threadId;
        synchronized (runtime.conversation.refundThreads) { threadId = runtime.conversation.refundThreads.get(orderId); }
        if (threadId == null) throw new RefundWorkflowException(RefundWorkflowException.Kind.NOT_FOUND, "当前会话没有该订单的退款流程");
        var result = workflow.get(threadId);
        publish(runtime, result);
        return result;
    }

    public List<RefundResult> results(ConversationRegistry.Conversation conversation) {
        synchronized (conversation.refundThreads) {
            return conversation.refundThreads.values().stream().map(workflow::get).toList();
        }
    }

    public RefundResult approve(AssistantToolContext runtime, String threadId, boolean approved) {
        // 此方法没有 @Tool；只允许明确审批接口进入，并先验证当前会话与流程的关联。
        synchronized (runtime.conversation.refundThreads) {
            if (!runtime.conversation.refundThreads.containsValue(threadId)) {
                throw new RefundWorkflowException(RefundWorkflowException.Kind.NOT_FOUND, "当前会话没有该退款流程");
            }
        }
        var result = workflow.approve(threadId, approved, step -> runtime.emit("workflow_step", step));
        publish(runtime, result);
        return result;
    }

    private static void publish(AssistantToolContext runtime, RefundResult result) {
        runtime.emit("workflow_result", result);
        if (result.approvalRequired()) runtime.emit("approval_required", result);
    }
}
