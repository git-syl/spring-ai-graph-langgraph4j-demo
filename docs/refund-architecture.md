# 当前架构：Spring AI 聊天入口 + LangGraph4j 退款工具

当前版本使用 Spring AI 2.1.0-RC1、LangGraph4j 1.9.7。主入口是 `/api/chat`，自然语言理解和追问由 Spring AI 助手处理；图接收类型化 RefundCommand，不再通过 ParseIntentNode 调用模型。

完整讲解见[教程上篇](blog/spring-ai-2-langgraph4j-01.md)和[教程下篇](blog/spring-ai-2-langgraph4j-02.md)。

## 调用路径

```text
ChatController
  → ChatAssistant
    → ChatClient + SessionAdvisors + 原生工具循环
      → RefundWorkflowTools.startRefund/getStatus
        → RefundWorkflow.start/get
          → RefundGraph
            → 受控订单与支付工具

明确审批接口 → ChatAssistant.approve
            → RefundWorkflowTools 校验会话关联
            → RefundWorkflow.approve → 保存决定 → resume
```

图节点：`START → validate_request → lookup_order → policy_check → approval_gate → execute_refund → answer → END`。政策不允许时直接 answer；审批拒绝时跳过 execute_refund；需要审批时暂停在 gate 执行之前。

## 状态、依赖和协议

| 对象 | 保存什么 | 生命周期 |
| --- | --- | --- |
| SessionService | 聊天消息与工具交互 | conversationId |
| ConversationRegistry | 用户提到的订单、订单与流程关联、成功请求缓存 | 当前进程的会话 |
| RefundCommand | orderId、reason | 图的结构化输入 |
| RefundState | command、messages、order、policy、approval、refund、answer、status、trace | checkpoint |
| RefundContext | 工具执行器、策略、Clock、步骤 writer | 每次调用重新注入 |
| StepInfo | threadId、step、status、data、finish、timestamp | 对外步骤事件 |
| ChatTurnResult | 会话/请求 ID、模型回答、真实退款结果、工具名 | 一轮 API 响应 |

RefundContext 放在 RunnableConfig metadata 中，由节点显式读取。Context 不写入业务 State。messages 和 trace 使用保留重复项的追加 reducer，节点只返回新增项。

## 外层模型工具与内层业务工具

模型只看到 `start_refund_workflow(orderId, reason)` 和 `get_refund_status(orderId)`。ToolContext 由服务端注入，不属于模型生成参数。工具校验订单由用户提过，服务端生成 threadId；同会话同订单复用原流程。

启动前预留关联；失败时仅在明确查询到没有 checkpoint 后解除关联。已有 checkpoint 或状态查询异常时保留原关联，避免结果不确定时重复启动。

图内部通过 RefundToolExecutor 调用 `get_order/create_refund`。退款前复核规则、金额和审批，并注入服务端授权标记。原始支付工具没有注册给聊天模型。

## 审批与幂等

审批接口校验 conversationId 和 threadId 关联，要求明确 Boolean 决定，检查状态为 APPROVAL_REQUIRED、下一节点为 approval_gate 且没有旧决定。随后 `updateState(..., "approval_gate")` 保存审批，再 `GraphInput.resume()` 继续。

成功请求以 requestId 缓存，重试相同内容返回原结果，不同内容报 409。同一会话使用 turnLock 串行处理，同一工作流采用固定数量 lock stripe。它们只提供单进程保护；模拟支付另按订单幂等。

审批本身不需要模型调用。下一轮聊天附带最新业务快照，避免旧聊天记录中的待审批状态覆盖最新事实。前端金额、凭证和按钮状态使用 refunds 字段，模型文字只作解释。

## 事件与失败

SSE 有 token、workflow_step、workflow_result、approval_required、result、error。token 来自 ChatClient 原生流；图步骤由节点 hook 和审批门发出。finish 表示本次调用结束，等待审批同样可以结束调用。

事件 writer 的异常不改变已执行的业务结果。模型最终回复失败时，退款可能已经执行；API 返回错误后可查询会话退款状态，不应直接创建新申请。

## 存储与部署边界

默认 Session repository、ConversationRegistry、MemorySaver 和模拟账本都是内存实现，重启后会丢失。RefundWorkflowTest 使用全新的 FileSystemSaver/图/Context 验证业务 checkpoint 恢复，但不是整个聊天服务的跨实例恢复。

业务 record 的类型、BigDecimal 和 Instant 由 RefundStateSerializer 显式处理；Spring AI 消息使用上游序列化适配，保持默认 state clone。

真实接入需要持久化会话、业务关联、请求去重和支付记录，并加入身份/订单授权、审批权限及并发控制。同一会话同一订单在拒绝后也复用原流程；重新申请需要另外定义申请编号与规则。当前项目没有生产身份认证、分布式锁、会话 TTL 或持久化事件补发。

## 源码

- [助手入口](../src/main/java/com/example/refunddemo/assistant/ChatAssistant.java)
- [模型工具适配](../src/main/java/com/example/refunddemo/assistant/RefundWorkflowTools.java)
- [图生命周期](../src/main/java/com/example/refunddemo/refund/RefundWorkflow.java)
- [图与序列化](../src/main/java/com/example/refunddemo/refund/graph/)
- [独立节点](../src/main/java/com/example/refunddemo/refund/nodes/)
- [HTTP/SSE](../src/main/java/com/example/refunddemo/web/ChatController.java)
- [共享 HTTP 验收契约](../src/test/java/com/example/refunddemo/web/AssistantHttpContract.java)
