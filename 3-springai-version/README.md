# Spring AI 版

Spring AI + Spring Boot 实现的知识库助手。

## 当前进度

- ✅ Step 1：基础对话（POST /chat）
- ✅ Step 2：PromptTemplate + ChatMemory + 结构化输出（POST /extract）
- ✅ Step 3：文档加载 + Embedding + 向量库（POST /ingest + POST /search）
- ✅ Step 4：完整 RAG 问答（POST /ask，QuestionAnswerAdvisor + 来源溯源）
- ✅ Step 5：Agent + 工具调用（POST /agent，@Tool + ChatClient.tools()）
- ⏳ Step 6+：流式输出 + 前端 / 切换模型

> Embedding 用本地 **all-MiniLM-L6-v2**（ONNX，384 维；模型文件下到 `/home/matt/models/all-MiniLM-L6-v2/`，
> 因为默认下载地址走 GitHub LFS 国内会断，已改为本地文件离线加载）。中文召回不如另两版的 BGE，换 BGE 见 step 3 doc。
> 向量库连 Chroma 的 **default_tenant/default_database**（与另两版一致），集合 `knowledge_base_minilm`，启动时自动创建。

> ℹ️ 本版本用 **Spring AI 1.1.7 + Spring Boot 3.5.14**。早期 1.0.0 对 Chroma 1.x 有个坑：
> `getCollection` 遇不存在的集合会抛 404，导致 `initialize-schema` 自动建表失效，需手动建集合。
> 1.1.x 已修复（getCollection 返回 null + 自动建 tenant/database/collection），所以**启动即自动建集合，无需任何前置脚本**。

## 启动

**首次启动前**：在项目根目录配好统一 `.env`（三个版本共用），至少填入 DeepSeek Key：

```bash
# /mnt/d/code/myAIProject/.env
DEEPSEEK_API_KEY=sk-...        # 申请：https://platform.deepseek.com
DEEPSEEK_BASE_URL=https://api.deepseek.com
DEEPSEEK_MODEL=deepseek-chat   # 主对话模型（可填 deepseek-v4-pro 等）
```

### 方式 A：一键脚本（推荐）

```bash
cd /mnt/d/code/myAIProject
./scripts/start-springai.sh
```

脚本会自动加载根 `.env`、检查 Key、启动 Maven（首次启动会自动在 Chroma 建好集合）。

### 方式 B：手动启动

```bash
set -a && source /mnt/d/code/myAIProject/.env && set +a
cd /mnt/d/code/myAIProject/3-springai-version
mvn spring-boot:run
```

启动成功你会看到 `Tomcat started on port 8082 (http)`。

## 接口

### 1) 单轮对话

```bash
curl -X POST http://localhost:8082/chat \
     -H "Content-Type: application/json" \
     -d '{"message": "你好，用一句话介绍你自己"}'
```

### 2) 多轮对话（同一个 conversationId）

```bash
curl -X POST http://localhost:8082/chat \
     -H "Content-Type: application/json" \
     -d '{"message": "我叫小明", "conversationId": "demo-001"}'

curl -X POST http://localhost:8082/chat \
     -H "Content-Type: application/json" \
     -d '{"message": "我叫什么？", "conversationId": "demo-001"}'
```

### 3) 自定义角色

```bash
curl -X POST http://localhost:8082/chat \
     -H "Content-Type: application/json" \
     -d '{"message": "什么是 RAG？", "role": "严谨的 AI 研究员", "maxWords": 100}'
```

### 4) 结构化输出

```bash
curl -X POST http://localhost:8082/extract \
     -H "Content-Type: application/json" \
     -d '{"text": "张三今年 28 岁，是个程序员，喜欢登山摄影。"}'
```

### 5) 清空会话

```bash
curl -X DELETE http://localhost:8082/conversations/demo-001
```

### 6) 文档入库 + 检索（Step 3 新增）

先确保 Chroma 服务在跑：另一个终端 `./scripts/start-chroma.sh`。

```bash
# 入库
curl -X POST http://localhost:8082/ingest

# 检索 Top K
curl -X POST http://localhost:8082/search \
     -H "Content-Type: application/json" \
     -d '{"query":"What is RAG","k":3}'
```

注意：本版本默认用 all-MiniLM-L6-v2（英文为主），中文 query 召回质量不如 LangChain4j / Python 版的 BGE。换 BGE 见 step 3 doc。

### 7) 完整 RAG 问答（Step 4 新增）

`QuestionAnswerAdvisor` 自动检索 + 拼 prompt，从响应 metadata 取回引用来源。

```bash
curl -X POST http://localhost:8082/ask \
     -H "Content-Type: application/json" \
     -d '{"question": "RAG 是什么？"}'
```

返回 `answer` + `sources`。注意 `score` 是相似度（越大越相似）。

### 8) Agent + 工具调用（Step 5 新增）

`@Tool` + `ChatClient.tools()`，模型自己决定要不要查知识库；按请求把模型临时切到 `deepseek-chat`(V3)。

```bash
# 专业问题 → 自动调检索工具
curl -X POST http://localhost:8082/agent \
     -H "Content-Type: application/json" \
     -d '{"message": "RAG 是什么？"}'

# 闲聊 → 直接答，不检索
curl -X POST http://localhost:8082/agent \
     -H "Content-Type: application/json" \
     -d '{"message": "你好呀"}'
```

## 相关文档

- 用户系统（登录 / 历史）→ `../docs/用户系统-登录与历史.md`
- Embedding / 向量库选型 → `../docs/选型参考-Embedding与向量库.md`
