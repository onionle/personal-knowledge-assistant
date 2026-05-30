# LangChain4j 版

LangChain4j + Spring Boot 实现的知识库助手。

## 当前进度

- ✅ Step 1：基础对话（POST /chat）
- ✅ Step 2：PromptTemplate + ChatMemory + 结构化输出（POST /extract）
- ✅ Step 3：文档加载 + Embedding + 向量库（POST /ingest + POST /search）
- ✅ Step 4：完整 RAG 问答（POST /ask，contentRetriever 自动检索 + 来源溯源）
- ✅ Step 5：Agent + 工具调用（POST /agent，@Tool + AiServices.tools()）
- ⏳ Step 6+：流式输出 + 前端 / 切换模型

> 说明：本版本 Embedding 用 **BGE-small-zh-v1.5**（ONNX 打进 jar，零下载）；
> 向量库连 Chroma 的 **default_tenant/default_database**，集合 `knowledge_base_bge_small_zh`（与 Python 版共用）。

## 启动

**首次启动前**：在项目根目录配好统一 `.env`（三个版本共用），至少填入 DeepSeek Key：

```bash
# /mnt/d/code/myAIProject/.env
DEEPSEEK_API_KEY=sk-...        # 申请：https://platform.deepseek.com
DEEPSEEK_BASE_URL=https://api.deepseek.com
DEEPSEEK_MODEL=deepseek-chat   # 主对话模型（可填 deepseek-v4-pro 等思维链模型）
```

> ⚠️ 依赖说明：本版本用 **langchain4j 1.14.1**（chroma 模块 `1.14.1-beta24` 支持 Chroma v2 API）。
> 早期 1.0.0 的 chroma 模块只会调已废弃的 v1 API，对 Chroma 1.x 启动会报 405。

### 方式 A：一键脚本（推荐）

```bash
cd /mnt/d/code/myAIProject
./scripts/start-langchain4j.sh
```

脚本会自动加载根 `.env`、检查 Key、启动 Maven。首次会下载几百兆依赖，1-3 分钟。

### 方式 B：手动启动

```bash
set -a && source /mnt/d/code/myAIProject/.env && set +a
cd /mnt/d/code/myAIProject/2-langchain4j-version
mvn spring-boot:run
```

启动成功你会看到 `Tomcat started on port 8080 (http)`。

## 接口

### 1) 单轮对话

```bash
curl -X POST http://localhost:8080/chat \
     -H "Content-Type: application/json" \
     -d '{"message": "你好，用一句话介绍你自己"}'
```

返回里有 `conversationId`，下一轮带上能继续。

### 2) 多轮对话

```bash
curl -X POST http://localhost:8080/chat \
     -H "Content-Type: application/json" \
     -d '{"message": "我叫小明", "conversationId": "demo-001"}'

curl -X POST http://localhost:8080/chat \
     -H "Content-Type: application/json" \
     -d '{"message": "我叫什么？", "conversationId": "demo-001"}'
```

### 3) 自定义角色

```bash
curl -X POST http://localhost:8080/chat \
     -H "Content-Type: application/json" \
     -d '{"message": "什么是 RAG？", "role": "严谨的 AI 研究员", "maxWords": 100}'
```

### 4) 结构化输出

```bash
curl -X POST http://localhost:8080/extract \
     -H "Content-Type: application/json" \
     -d '{"text": "张三今年 28 岁，是个程序员，喜欢登山摄影。"}'
```

预期：
```json
{"name":"张三","age":28,"occupation":"程序员","summary":"..."}
```

### 5) 文档入库 + 检索（Step 3 新增）

先确保 Chroma 服务在跑：另一个终端 `./scripts/start-chroma.sh`。

```bash
# 入库（首次启动时 BGE 模型从 jar 内加载，需要几秒）
curl -X POST http://localhost:8080/ingest

# 检索 Top K
curl -X POST http://localhost:8080/search \
     -H "Content-Type: application/json" \
     -d '{"query":"什么是 LangChain4j","k":3}'
```

注：返回的 `score` 是相似度（越大越相似），跟 Python 版的 `distance`（越小越相似）含义相反。

### 6) 完整 RAG 问答（Step 4 新增）

`contentRetriever` 自动检索 + 拼 prompt，`Result.sources()` 返回引用来源。

```bash
curl -X POST http://localhost:8080/ask \
     -H "Content-Type: application/json" \
     -d '{"question": "RAG 是什么？"}'
```

返回 `answer` + `sources`（引用到的文档块）。

### 7) Agent + 工具调用（Step 5 新增）

`@Tool` 把检索做成工具，`AiServices.tools()` 自动跑 Agent 循环，模型自己决定要不要查。

```bash
# 专业问题 → 自动调用检索工具
curl -X POST http://localhost:8080/agent \
     -H "Content-Type: application/json" \
     -d '{"message": "RAG 是什么？"}'

# 闲聊 → 直接回答，不检索
curl -X POST http://localhost:8080/agent \
     -H "Content-Type: application/json" \
     -d '{"message": "你好呀"}'
```

> Agent 内部固定用 `deepseek-chat`(V3)——思维链模型不支持 function calling。

## 相关文档

- 用户系统（登录 / 历史）→ `../docs/用户系统-登录与历史.md`
- Embedding / 向量库选型 → `../docs/选型参考-Embedding与向量库.md`
