# Spring AI 2.1 与 LangGraph4j 的实现对照

本仓库验证的组合为 Spring AI **2.1.0-RC1**、LangGraph4j **1.9.7**、Spring Boot **4.1.0** 和 Java **25**。候选版身份不省略；依赖由应用 BOM 管理，上游适配模块使用的基线可能不同，最终以应用构建与行为测试为准。

## 当前设计

主应用采用“Spring AI 聊天 → 原生工具 → LangGraph4j 业务图”。普通聊天和多轮追问在助手层；退款图接收明确命令，所有金额、政策、审批和支付结果由业务代码处理。五个独立例子展示“图节点调用 Spring AI”这一种组合方式，两者可以共存。

## 上游 API 与落点

| 能力 | API | 项目位置 |
| --- | --- | --- |
| 对话与原生工具循环 | ChatClient、ToolCallingAdvisor、@Tool | assistant/、AssistantConfig |
| 多轮会话 | SessionService、SessionAdvisors | AssistantConfig、ChatAssistant |
| 可信工具上下文 | ToolContext | AssistantToolContext |
| 类型化状态与 reducer | AgentState、MessagesState、Channels | RefundState、LearningState |
| 图与条件路由 | StateGraph、addConditionalEdges | RefundGraph、GraphApiExample |
| 运行依赖 | RunnableConfig metadata | RefundContext |
| 业务工具执行 | ToolCallbacks、SpringAIToolService | RefundToolExecutor |
| 图步骤事件 | addWrapCallNodeHook、Consumer | RefundGraph、StepInfo |
| 人工中断 | InterruptibleAction | ApprovalGateNode、ReviewGate |
| 保存与恢复 | saver、updateState、GraphInput.resume | RefundWorkflow、DurableExecutionExample |
| 模型片段与图流 | StreamingChatGenerator、StreamingOutput | StreamingExample |
| 子图 | addNode(name, child)、child.invoke | SubgraphExample |

Spring AI 2.1 的工具确认发生在工具执行前；退款业务审批发生在查单和政策判断后，本 demo 采用图内审批。详见[完整教程下篇](blog/spring-ai-2-langgraph4j-02.md)。

## Graph 实现的关键约束

- Graph API、持久化、持续运行、流式输出和子图各有一个可运行实例，详见[示例手册](java-graph-examples.md)。
- 动态任务分发需要控制并发与恢复边界；副作用端独立实现幂等。
- Command 支持路由和状态更新；外部审批保存后通过 GraphInput.resume 恢复。
- 1.9.7 的 updateState 会替换选中的 checkpoint 槽位；时间旅行示例先复制到新 thread，保护原历史。
- State 保存业务数据，Context 提供运行依赖，StepInfo 定义进度协议；运行依赖通过配置 metadata 传入。

## 核对与验证

本地参考克隆位于 ref-source，版本和提交见[参考源码说明](../ref-source/README.md)。应用配置使用 Session API；测试模型覆盖 getOptions；类型化图快照保留 Jackson 2 适配，未关闭 clone。

实际验证分别覆盖纯业务图、真实 Spring AI 工具循环的离线 HTTP 测试，以及真实提供方的聊天/工具/SSE。支付仍是 mock。测试范围和最新结果见[验证记录](graph-examples-verification.md)。

进一步阅读：[教程上篇](blog/spring-ai-2-langgraph4j-01.md)、[教程下篇](blog/spring-ai-2-langgraph4j-02.md)、[当前架构](refund-architecture.md)。
