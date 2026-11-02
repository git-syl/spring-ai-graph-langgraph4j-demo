# 聊天助手与 Graph 示例验收记录

验证日期：**2026-11-02（Asia/Shanghai）**。本记录对应 Spring AI 聊天入口、原生退款工具和 LangGraph4j 工作流的当前实现。

## 环境与命令

- Java 25、Maven 3.9.3、Spring Boot 4.1.0。
- Spring AI **2.1.0-RC1**、LangGraph4j **1.9.7**。
- 模型：`deepseek-v4-flash-ga-260731`。
- 地址：`https://ark.cn-beijing.volces.com/api/v3`；key 仅通过进程环境注入。
- 订单与支付为本地 mock；真实网络调用的是模型服务。

最终执行 `mvn -B -Plive-graph clean verify`，结果 **BUILD SUCCESS**，完成于 2026-11-02 00:11:14 +08:00，耗时约 1 分 13 秒。

之后执行五个示例的 `all -Offline`、runtime dependency tree、JAR 内容检查，以及从博客提取的独立 Maven 项目编译和 HelloGraph 运行。

## 测试结果

| 测试类 | 数量 | 验证内容 |
| --- | ---: | --- |
| LangGraphBasicsTest | 2 | 图基础行为 |
| GraphExamplesTest | 11 | 路由、reducer、历史分支、磁盘恢复、重试、流式、子图 |
| OrderRefundToolsTest | 2 | 服务端退款授权、金额和幂等 |
| RefundPolicyTest | 3 | 期限、金额、审批规则 |
| RefundSerializationTest | 2 | 业务 record、消息、时间与金额精度 |
| RefundWorkflowTest | 7 | 结构化命令、恢复、拒绝、并发审批、观察者异常 |
| RefundWorkflowToolsTest | 3 | 无 checkpoint 的启动失败、已有流程保留、查询结果不确定时防止重复启动 |
| AssistantHttpTest | 7 | 固定模型 + 真实 Spring AI 工具循环 + 实际 Tomcat HTTP/SSE |
| 默认测试小计 | **37** | **0 failures / 0 errors / 0 skipped** |
| GraphExamplesLiveIT | 5 | 真实模型的五个 Graph 示例 |
| AssistantHttpLiveIT | 6 | 真实模型 + 生产装配 + 聊天 HTTP/SSE |
| 真实集成测试小计 | **11** | **0 failures / 0 errors / 0 skipped** |
| 合计 | **48** | **全部通过** |

离线 HTTP 测试仅替换 ChatModel。生产的 ChatClient、SessionAdvisors、ToolCallingAdvisor、模型工具、图与控制器仍实际执行；假模型返回原生 ToolCall，由框架负责调用工具。

## 真实模型验收范围

| 场景 | 已验证行为 |
| --- | --- |
| 普通聊天与记忆 | 同会话记得“蓝鲸42”，其他会话不泄漏；咨询政策不启动退款 |
| 多轮追问 | 信息不足先询问订单，补齐 O-2001 与原因后调用 start_refund_workflow |
| 待审批 | APPROVAL_REQUIRED，没有退款凭证，未执行 execute_refund |
| 审批隔离 | 聊天文字确认不批准；缺少 approved 返回 400；其他会话审批返回 404 |
| 审批恢复 | 明确同意后 COMPLETED，399 元；原请求重试复用结果，新请求重复审批为 409 |
| 最新进度 | 审批后聊天调用 get_refund_status，看到已完成状态 |
| 自动退款与去重 | O-1001 自动处理，99 元；同一 requestId 回放成功结果；冲突输入 409；重复工具不新建图 |
| SSE | 多个真实文本片段、图步骤、业务结果、审批事件及最终响应；审批拒绝流返回 DECLINED |
| 拒绝分支 | 超期 O-3001 和未知 O-9999 不执行退款 |
| API 契约 | 非法请求校验，旧退款写入路由 404，图可视化可读 |

固定模型另验证虚构订单参数被拒绝，以及模型异常得到安全错误响应且没有退款副作用。

## Graph 真实调用采样

| 示例 | 本次结果 |
| --- | --- |
| Graph API | attempts=1，64 字符，ACCEPTED |
| Persistence | 3 个 checkpoint，原回答与修改后的分支同时保留 |
| Durable execution | 新图/磁盘 saver 恢复，234 字符草稿发布成功 |
| Streaming | 166 个片段、276 字符，片段拼接与最终消息一致 |
| Subgraphs | 共享状态输出 105 字符，映射状态输出 96 字符 |

字符数和片段数是本次采样，真实生成内容可变化。测试断言行为与一致性，不依赖固定模型文案。

## 文档与打包检查

- 两篇中文系列文章包含项目创建、聊天工具、五项 Graph 能力、完整退款案例、HTTP/SSE、持久化和验证；读者只需文章与本项目源码。
- 将上篇的完整 POM、启动类和 HelloGraph 原样提取到独立临时 Maven 工程；编译通过，运行输出“程序员给猫写了一个接口。”。
- 五个 CLI 示例以 `all -Offline` 实际运行并正常退出；离线标记明确，不作为模型连通性证据。
- clean 构建的 JAR 包含新 ChatAssistant/ChatController、Spring AI Session 模块与 LangGraph4j 1.9.7。
- runtime dependency tree 和 JAR 无旧 RAG、文档读取、PgVector、PostgreSQL、JDBC 模块；无旧 domain/workflow 包、RefundController、ParseIntentNode、旧意图提示词或私有配置。
- 参考克隆分别在 v1.9.7 与 v2.1.0-RC1，工作树干净；版本与提交见 [ref-source](../ref-source/README.md)。
- 源码、文档及测试报告中的提供方凭据扫描没有匹配项。

## 本地复查产物

以下文件由验证命令产生，位于 Git 忽略的 target 中，不作为开源仓库的文档链接依赖：

- `target/assistant-acceptance.log`：最终 clean verify 日志。
- `target/surefire-reports/`：37 项默认测试报告。
- `target/failsafe-reports/`：11 项真实模型测试报告。
- `target/graph-dependencies.txt`：runtime dependency tree。
- `target/graph-offline-final.log`：CLI 离线运行日志。
- `target/tutorial-compile.log`：博客完整示例的独立编译日志。
- `target/tutorial-check/`：从博客代码块提取的临时工程。

## 验证边界

默认会话、流程关联、请求缓存、MemorySaver 和模拟支付账本均在内存中。磁盘 saver 的恢复测试证明业务 checkpoint 可以重新加载，不证明完整聊天服务已具备重启恢复或跨实例一致性。此 demo 没有生产身份认证、订单归属授权、分布式锁、持久化支付幂等或真实支付渠道接入。

实际接口与运行方式见 [README](../README.md)，实现说明见[当前架构](refund-architecture.md)。

## 系列讲解与代码注释补充复查（2026-11-02）

本次围绕两篇系列文章补充新手讲解、状态变化、逐步实验、官方版本对照及核心源码中文注释。Java 文件的词法对比确认只修改注释。

| 检查 | 本次结果 |
| --- | --- |
| JDK 25，`mvn -B clean verify` | BUILD SUCCESS；37 项默认测试，0 failures / 0 errors / 0 skipped |
| 两篇文章内的本地链接与代码围栏 | 链接目标存在，代码围栏配对 |
| 固定版本官方文件链接 | 21 个 GitHub 文件链接对应的原始文件均可访问 |
| 文章完整示例独立编译 | 原样提取 POM、启动类、HelloGraph、StateMergeDemo；独立 Maven compile 成功 |
| 独立示例运行 | HelloGraph 输出预期正文；StateMergeDemo 输出猫、李四、`[visited, visited]` |
| `all -Offline` | 五项 Graph 示例全部运行成功 |
| 独立进程审批恢复 | 分别启动两个 Java 进程；批准后 PUBLISHED 且生成文件，拒绝后 REJECTED 且不生成文件 |

本次未重新执行真实提供方集成测试；上文 11 项真实模型测试仍是前一轮验收记录。离线验证不作为外部模型连通性的证据。

本次日志和临时工程位于被 Git 忽略的 `target/tutorial-verify.log`、`target/tutorial-snippets-compile.log`、`target/tutorial-cli-offline.log`、`target/tutorial-check/`。第一次非 clean 构建曾误执行覆盖代码前遗留的 SimpleGraphTest class，清理后已排除该影响。
