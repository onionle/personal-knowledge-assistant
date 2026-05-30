# LangChain4j 简介

LangChain4j 是 LangChain 思想的 Java 移植版，由 Dmytro Liubarskyi 于 2023 年创建。
它不是 LangChain 官方项目，但社区认可度高，是目前 Java 生态最活跃的 LLM 框架之一。

## 与 LangChain (Python) 的关系

设计理念相同，但 API 完全独立：

- LangChain Python 用 LCEL 管道符 `|` 串联组件，强调函数式风格。
- LangChain4j 用 `AiServices` 接口 + 注解（`@SystemMessage`、`@UserMessage`、`@V`）
  生成代理对象，更贴近 Spring 的"声明式编程"习惯。

LangChain4j 1.0 于 2024 年发布，API 稳定，是生产可用的版本。

## 核心组件

- **ChatModel**：聊天模型接口，支持 OpenAI、Anthropic、Gemini、Ollama、本地模型等。
- **EmbeddingModel**：把文本变成向量。社区维护了几个开箱即用的本地模型，
  如 `langchain4j-embeddings-bge-small-zh-v15`（ONNX 打包进 jar，零下载）。
- **EmbeddingStore**：向量存储抽象。实现包括 Chroma、Milvus、Pinecone、PgVector、内存版等。
- **DocumentSplitter**：把长文档切成短块，常见策略是按字符数或 token 数切，带 overlap。
- **AiServices**：通过 Java 接口 + 注解声明你想要的 AI 行为，框架运行时生成代理实现。
- **ChatMemory**：消息历史管理，`MessageWindowChatMemory` 是常用的滑动窗口实现。
- **Tool**：用 `@Tool` 注解标记 Java 方法，模型可以选择性调用它们（Function Calling）。

## 与 Spring Boot 整合

LangChain4j 提供 `langchain4j-spring-boot-starter`，可以让 Spring 自动装配 ChatModel
等 Bean。但它不像 Spring AI 那样紧密集成——你仍然需要手写 `@Bean` 配置 AiService。

## 何时选 LangChain4j

适合：Java/Kotlin 团队、希望细粒度控制 LLM 调用细节、需要 LangChain 生态特性的项目。

不适合：纯 Spring 技术栈、希望完全声明式配置的团队——这种场景 Spring AI 更顺手。
