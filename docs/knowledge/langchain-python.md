# LangChain (Python) 简介

LangChain 是一个用 Python 编写的开源框架，专门用来构建基于大语言模型（LLM）的应用。
它于 2022 年由 Harrison Chase 创建，是目前 Python 生态里最流行的 LLM 应用框架。

## 核心组件

LangChain 的核心抽象包括：

- **Chat Model**：对 LLM 的统一封装。换模型（OpenAI、Anthropic、DeepSeek、Qwen 等）
  只需要换初始化参数，业务代码不动。
- **Prompt Template**：参数化的提示词模板。用 `{变量}` 占位，调用时 fill 进去。
- **Messages**：HumanMessage、AIMessage、SystemMessage、ToolMessage 等结构化消息类型。
- **Output Parser**：把模型返回的字符串转成结构化数据（Pydantic 对象、JSON 等）。
- **Memory**：多轮对话的历史管理，支持滑动窗口、token 限制、向量回忆等多种策略。
- **Retriever**：从向量库或外部数据源召回相关文档，是 RAG 的核心。
- **Agent**：让模型自主选择并调用工具完成任务的 ReAct / Function Calling 框架。

## 版本演进

- LangChain 0.0.x：早期混乱期，API 频繁破坏式升级。
- LangChain 0.1.x：模块化拆分，分出 `langchain-core`、`langchain-community` 等子包。
- LangChain 1.x（2025）：稳定 API，引入 LangGraph 作为复杂工作流编排的官方推荐。

## 与 LCEL（LangChain Expression Language）

LangChain 推荐用管道符 `|` 把 Prompt、Model、Parser 串成链：

```python
chain = prompt | llm | parser
result = chain.invoke({"input": "你好"})
```

LCEL 内部基于 Runnable 协议，自动支持 batch、stream、async 三种调用方式，
不用写额外代码就能切换。

## 何时选 LangChain

适合：原型快速验证、个人项目、Python 主导的数据/AI 团队、研究场景。

不适合：高性能并发服务（Python GIL 限制）、严格类型安全的企业级 Java 系统。
后者推荐用 LangChain4j（Java 移植版）或 Spring AI。
