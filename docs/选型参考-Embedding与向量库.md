# 选型参考 · Embedding 模型 与 向量库

> 本文档回答两个"长期会反复纠结"的问题：
> 1. Embedding（把文字变向量）该用**本地模型**还是**云端 API**？
> 2. 向量库除了 **Chroma** 还能用啥，什么场景选什么？
>
> 本项目当前的选择是**本地优先**，但生产环境往往是另一套权衡。下面把多种方案、
> 各自优劣、以及"什么时候选哪个"都列清楚，方便你以后做真实项目时直接套用。

---

## 一、Embedding 模型：本地 vs 云端 API

### 本项目现在用的是什么？

| 版本 | 当前用的 Embedding | 形态 |
|------|-------------------|------|
| LangChain (Python) | BGE-small-zh-v1.5 | **本地**（sentence-transformers，模型文件下到 `/home/matt/models/`）|
| LangChain4j | BGE-small-zh-v1.5 | **本地**（ONNX 打进 jar）|
| Spring AI | all-MiniLM-L6-v2 | **本地**（ONNX，模型文件下到 `/home/matt/models/all-MiniLM-L6-v2/`）|

> ⚠️ **这是"省钱/能离线/数据不出本机"的选择，不是生产唯一解。** 用本地模型是因为：
> 不想再多管一个 API Key、不依赖外部账号余额、断网也能跑。
> 代价是：要下载模型文件（首次联网，国内还得配镜像）、占本地内存/CPU、模型能力不如顶级商用 embedding。

### 方案全景

#### 方案 A：本地开源模型（本项目当前方案）

代表：`BAAI/bge-*`（中文强）、`all-MiniLM-L6-v2`（英文轻量）、`bge-m3`（多语言/长文本）、`gte`、`e5` 系列。
跑法：Python 用 `sentence-transformers`；Java 用 DJL/ONNX Runtime。

- ✅ 永久免费、无调用次数限制、数据不出本机（**隐私/合规友好**）、可离线
- ✅ 同一模型可锁定版本，检索结果可复现
- ❌ 要下载模型（几十 MB ~ 几 GB）、吃本地 CPU/内存、首次部署麻烦
- ❌ 中小模型效果一般；要好效果就得上大模型 → 要 GPU
- **适合**：学习、内网/隐私敏感、量不大、想省钱、要离线

#### 方案 B：云端 Embedding API（生产最常用）

代表（国内直连优先）：
- **阿里云百炼 `text-embedding-v3/v4`**：OpenAI 兼容、中文好、国内直连，生产首选之一
- **硅基流动 SiliconFlow**：托管 BGE/GTE 等开源模型，OpenAI 兼容、注册送额度，**迁移成本最低**
- **智谱 `embedding-3`**：中文好、国内直连
- **Jina `jina-embeddings-v3`**、**Voyage `voyage-3`**：多语言/检索质量很强（海外）
- **OpenAI `text-embedding-3-small/large`**：英文与综合质量标杆（需海外网络）

- ✅ **零本地资源**、免运维、随时用最新最强模型、弹性扩容
- ✅ 接入就是改个 `base-url + api-key + model`，三个框架都支持
- ❌ 按量付费、要管 Key 和余额、**数据要发给第三方**（合规需评估）、依赖网络与厂商可用性
- **适合**：正式产品、追求检索质量、不想运维模型、数据可外发

#### 方案 C：自建模型服务（中大型团队）

把开源 embedding 用 **Xinference / Ollama / TEI(Text-Embeddings-Inference) / vLLM** 起一个内部 HTTP 服务（通常 OpenAI 兼容），各应用统一调用。

- ✅ 数据不出公司内网 + 集中运维 + 多应用共享一份 GPU 资源 + 可上大模型
- ✅ 对应用端来说"长得像云 API"，但跑在自己机房
- ❌ 要有 GPU/运维能力，最重
- **适合**：中大型团队、强隐私要求又要好效果、多个应用复用

### 一句话建议

- **学习/个人/离线/隐私** → 方案 A 本地（本项目就是）
- **正式产品、想省心、数据可外发** → 方案 B 云端 API（国内首选**阿里百炼**或**硅基流动**）
- **公司级、强隐私 + 要好效果 + 有运维** → 方案 C 自建服务

### 切换有多简单？（本项目的可迁移性）

三个框架接 embedding 都是"换实现/换配置"的事，业务代码基本不动：
- **Python**：把 `BgeSmallZhEmbeddings` 换成 `OpenAIEmbeddings(base_url=..., model=...)` 即可
- **LangChain4j**：把 `BgeSmallZhV15EmbeddingModel` Bean 换成 `OpenAiEmbeddingModel.builder()...`
- **Spring AI**：去掉 `spring-ai-transformers`，加 `spring-ai-starter-model-openai` 的 embedding 配置，`spring.ai.openai.embedding.options.model=...`

> 注意：**换 embedding 必须重新入库（re-ingest）**。不同模型/不同维度的向量不在一个语义空间，
> 旧向量和新查询对不上。本项目集合名特意带了模型标记（`knowledge_base_bge_small_zh` /
> `knowledge_base_minilm`），换模型时新建集合即可，老数据不污染。

---

## 二、向量库：Chroma 还能换成啥？

### 本项目为什么用 Chroma？

轻量、`pip`/Docker 起一个就能用、本地文件持久化、API 简单——**非常适合学习和原型**。
但它不是为超大规模、高并发生产设计的。下面是常见替代，按"什么场景选什么"排。

### 方案对比

| 向量库 | 形态 | 一句话定位 | 适合场景 |
|--------|------|-----------|---------|
| **Chroma**（当前） | 嵌入式/单机服务 | 最易上手的原型级向量库 | 学习、Demo、小数据量(<百万) |
| **pgvector** | PostgreSQL 插件 | 在你已有的 Postgres 上加向量列 | **已用 Postgres 的团队首选**；想一套库同时存业务数据+向量 |
| **Qdrant** | 独立服务(Rust) | 性能/过滤强、好部署、生产口碑好 | 中大型生产、需要按 metadata 复杂过滤 |
| **Milvus** | 分布式集群 | 为**超大规模**(十亿级)而生 | 海量向量、高并发、专门的向量平台团队 |
| **Weaviate** | 独立服务 | 自带混合检索/模块化 | 想要开箱即用的混合搜索 |
| **Elasticsearch / OpenSearch** | 搜索引擎 | 全文检索 + 向量 一体 | 已用 ES 做搜索，想加语义检索 |
| **Redis (RediSearch)** | 内存数据库 | 低延迟、已用 Redis 就顺手 | 对延迟敏感、数据量适中 |
| **Pinecone** | 全托管云服务 | 免运维、按量付费 | 不想自己运维、快速上线(海外) |
| **阿里云/腾讯云 向量检索** | 国内托管云 | 国内直连的托管方案 | 国内生产、不想自建 |
| **FAISS** | 库(非数据库) | Facebook 的向量检索库 | 纯算法/批处理、自己管存储 |

### 按场景给建议

- **学习 / 原型 / 小项目** → **Chroma**（本项目）或 **Qdrant**（想顺手摸生产级）
- **已经在用 PostgreSQL** → **pgvector**，强烈推荐：少引入一个组件，事务/备份/权限都复用 Postgres
- **要上生产、数据中等(百万~千万)、要复杂过滤** → **Qdrant**（综合最均衡）
- **超大规模(亿级以上)、专门团队** → **Milvus**
- **已经在用 Elasticsearch 做搜索** → 直接用 **ES 的向量能力**，别再单独搭一个
- **完全不想运维 + 数据可外发** → **Pinecone**（海外）/ **阿里云·腾讯云向量库**（国内）

### 切换有多简单？

两个 Java 框架对向量库做了**统一抽象**，换库基本只换依赖 + 配置：
- **Spring AI**：`VectorStore` 是统一接口，把 `spring-ai-starter-vector-store-chroma` 换成
  `...-pgvector` / `...-qdrant` / `...-milvus` 等 starter，业务代码（`vectorStore.add/similaritySearch`）不变
- **LangChain4j**：`EmbeddingStore<TextSegment>` 是统一接口，把 `langchain4j-chroma` 换成
  `langchain4j-pgvector` / `langchain4j-qdrant` 等，注入处不变
- **Python LangChain**：把 `langchain_chroma.Chroma` 换成 `langchain_postgres.PGVector` /
  `langchain_qdrant.Qdrant` 等，检索调用一致

> 同样：**换库也要重新入库**，把向量灌进新库。

### 本项目踩过的 Chroma 版本坑（提醒）

Chroma 1.x 只保留 **v2 API**（v1 已废弃，调用返回 405）。对接客户端要注意版本：
- LangChain4j：`langchain4j-chroma` 需 ≥ `1.8.0-beta15` 且显式 `.apiVersion(ChromaApiVersion.V2)`
- Spring AI：**1.1.x**（本项目用 1.1.7）支持 v2 且 `initialize-schema` 启动时自动建
  tenant/database/collection，开箱即用。注意 1.0.0 有坑（`getCollection` 遇缺失集合抛 404 →
  自动建表失效），升到 1.1.x 即解决
- Python：`chromadb` 客户端与服务端版本匹配即可

---

## 三、总结（拿走就能用的决策表）

| 你的情况 | Embedding 选 | 向量库 选 |
|----------|-------------|-----------|
| 学习 / 个人项目 / 要离线 | 本地 BGE | Chroma |
| 已有 PostgreSQL 的业务系统 | 云端 API（百炼/硅基流动） | pgvector |
| 正式产品、追求质量、数据可外发 | 云端 API | Qdrant / Pinecone |
| 公司级、强隐私、有 GPU 运维 | 自建(TEI/Xinference) | Qdrant / Milvus |
| 超大规模(亿级) | 自建大模型 | Milvus |
| 已用 ES 搜索 | 视情况 | Elasticsearch 向量 |
