# Spring AI 2 + LangGraph4j 学习导航

完整中文教程已整理为两篇 Markdown，按照“先运行小例子，再解释组件，然后逐步集成”的顺序展开。

1. [上篇：从零建项目到可恢复的 Java Graph](blog/spring-ai-2-langgraph4j-01.md)
   - 创建 Maven/Spring Boot 项目，配置 Spring AI 2.1.0-RC1。
   - ChatClient、原生工具与 Session 会话。
   - 第一张图、State、reducer、节点、边、Command 与动态任务分发。
   - checkpoint、时间旅行、长期记忆边界。
   - 磁盘保存、人工中断、恢复、失败重试和副作用幂等。
2. [下篇：流式、子图与完整退款助手](blog/spring-ai-2-langgraph4j-02.md)
   - 模型文本流、图节点事件、自定义 writer。
   - 共享状态子图与输入输出映射。
   - Spring AI 聊天入口通过工具调用退款图。
   - 审批、会话与流程关联、HTTP/SSE 示例。
   - 序列化、版本迁移、真实模型验收与实际业务接入。

两篇依次覆盖 Graph API、持久化、持续运行、流式输出和子图。可运行源码位于 [examples](../src/main/java/com/example/refunddemo/examples/)，完整命令见[示例运行手册](java-graph-examples.md)。

主服务使用 `/api/chat`，退款图接收 RefundCommand；早期 query → ParseIntentNode 的主入口已被替换。当前接口请以 [README](../README.md) 和[架构说明](refund-architecture.md)为准。
