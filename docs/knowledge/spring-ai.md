# Spring AI 简介

Spring AI 是 Spring 官方团队推出的 LLM 应用框架，2024 年发布 1.0 正式版。
它由 Mark Pollack（Spring 团队成员）主导开发，目标是把 LLM 能力做成"Spring 风格"
的一等公民——自动配置、约定优于配置、和 Spring Boot 生态深度整合。

## 与 LangChain4j 的关系

两者都是 Java 框架，但定位不同：

- **LangChain4j**：移植 LangChain 思想，给你一套独立的抽象层。你显式控制装配。
- **Spring AI**：原生 Spring 风格，starter 一加就自动注入 ChatClient.Builder，
  几乎不用写配置代码。

技术选型时通常二选一，团队偏 Spring 选 Spring AI，偏框架灵活选 LangChain4j。

## 核心组件

- **ChatClient**：流式 API（`.prompt().user(...).call().content()`），
  接近 OpenAI 官方 SDK 的使用习惯。
- **ChatModel**：底层模型接口，支持 OpenAI、Anthropic、Azure OpenAI、Ollama、
  Bedrock、VertexAI 等十几家。
- **Advisor**：责任链式的增强机制。比如 `MessageChatMemoryAdvisor` 给对话加记忆，
  `QuestionAnswerAdvisor` 给对话加 RAG，都是同一套 Advisor 接口。
- **VectorStore**：向量库统一接口，实现包括 Chroma、Milvus、PgVector、Redis、
  Pinecone、Weaviate 等近 20 种。
- **EmbeddingModel**：嵌入模型接口，支持 OpenAI、Transformers（本地 ONNX）、
  Bedrock、VertexAI 等。
- **ChatMemory**：消息历史抽象，默认 `InMemoryChatMemoryRepository`，可换成
  JDBC、Cassandra、Redis 等持久化实现。

## Starter 自动装配的威力

只要 pom 加：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
```

再在 `application.yml` 写 `spring.ai.openai.api-key`，启动后就能直接
`@Autowired ChatClient.Builder`——无需任何 Java 配置代码。这是 Spring AI
相对 LangChain4j 最大的人体工学优势。

## 何时选 Spring AI

适合：Spring Boot 主导的企业级项目、希望最少胶水代码、需要广泛向量库/模型选项的团队。

不适合：Spring 体系外的 Java 项目（如 Quarkus、Micronaut），LangChain4j 是更中立的选择。
