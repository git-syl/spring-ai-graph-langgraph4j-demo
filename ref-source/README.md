# 上游源码参考

这个目录专门放其他系统的源码，只供阅读与对照，不参加本项目 Maven 构建。
子目录由主项目 `.gitignore` 排除；只有本说明进入主项目版本管理。
本地开源副本保留了两个上游仓库的工作区源码快照，但不复制其 `.git` 元数据。
从 GitHub 获取本项目后，可按下方命令另行下载参考源码；构建和测试不依赖这些目录。

2026-11-01 核对并使用的版本：

| 本地目录 | 上游 | 固定版本 | commit |
| --- | --- | --- | --- |
| `langgraph4j/` | <https://github.com/langgraph4j/langgraph4j> | `v1.9.7`，当日最新正式版 | `d8216be0b66918b17d19049c817d163138fb7a67` |
| `spring-ai/` | <https://github.com/spring-projects/spring-ai> | `v2.1.0-RC1`，与应用指定版本一致 | `7782e72d4b8e4394e0a5cb6ee17315f24386f5aa` |

LangGraph4j 1.9.7 上游 POM 的 Spring AI 基线是 2.0.1；应用通过自己的 BOM 统一升级到 2.1.0-RC1。
应用中的退款流程、HTTP/SSE 测试及五节 Graph 示例用于验证此次组合。

## 在其他机器还原

在项目根目录执行，目录已存在时不要重复 clone：

```powershell
New-Item -ItemType Directory -Force ref-source
git clone --depth 1 --branch v1.9.7 https://github.com/langgraph4j/langgraph4j.git ref-source/langgraph4j
git clone --depth 1 --branch v2.1.0-RC1 https://github.com/spring-projects/spring-ai.git ref-source/spring-ai
```

通过上述 clone 命令创建的参考仓库包含独立 `.git`，历史为浅克隆；需要完整历史时，在对应仓库执行 `git fetch --unshallow`。
仅含源码快照的目录不能直接 fetch；需要 Git 操作时，请在另一个空目录 clone，保留现有快照。
需要跟踪某次新发布时，先确认参考仓库没有本地修改，再 fetch 对应 tag 并 checkout；升级应用依赖后重新运行测试。

## 阅读位置

| 内容 | 相对本目录的位置 |
| --- | --- |
| Graph 定义、执行和配置 | `langgraph4j/langgraph4j-core/src/main/java/org/bsc/langgraph4j/` |
| reducer / channel | `langgraph4j/langgraph4j-core/src/main/java/org/bsc/langgraph4j/state/` |
| MemorySaver / FileSystemSaver | `langgraph4j/langgraph4j-core/src/main/java/org/bsc/langgraph4j/checkpoint/` |
| Spring AI 消息序列化、流式适配和工具执行 | `langgraph4j/spring-ai/spring-ai-core/src/main/java/org/bsc/langgraph4j/spring/ai/` |
| Spring AI ChatClient | `spring-ai/spring-ai-client-chat/` |
| DeepSeek 兼容接口客户端 | `spring-ai/models/spring-ai-deepseek/` |
| Spring AI 文档 | `spring-ai/spring-ai-docs/` |

本项目的图能力介绍及运行方法见 [Java Graph 五个示例](../docs/java-graph-examples.md)。
