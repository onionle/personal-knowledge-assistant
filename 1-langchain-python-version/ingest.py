"""
========================================================================
LangChain Python 版 · Step 3：文档入库脚本（standalone）
------------------------------------------------------------------------
作用：把 docs/knowledge/*.md 切块、用 BGE-small-zh 嵌入、写进 Chroma。

为啥单独一个脚本而不是塞进 FastAPI 接口里：
  - 入库是"离线任务"：跑一次写完就完事，不需要常驻服务。
  - 嵌入 + 写库可能很慢（大语料几分钟到几小时），不适合放 HTTP 请求里。
  - 生产环境里这种任务通常用 Airflow / 定时任务 / CI 触发。

跑法：
  1) 先开 Chroma 服务（另一个终端）：
       ./scripts/start-chroma.sh
  2) 跑入库：
       ./scripts/ingest-python.sh
  3) 完成后可以在 FastAPI 里调 /search 验证检索效果
========================================================================
"""

import os
from pathlib import Path

import chromadb
from langchain_chroma import Chroma
from langchain_text_splitters import RecursiveCharacterTextSplitter
from langchain_core.documents import Document

# 自己实现的 Embeddings 子类，包了 sentence-transformers。
# 为啥不用 langchain-huggingface：它锁死 langchain-core<1.0，跟我们冲突。
from embeddings import BgeSmallZhEmbeddings


# ============== 配置 ==============
# Path(__file__) 是脚本自身路径
# .resolve() 转绝对路径
# .parent 往上一级（脚本目录）
# .parent.parent 再往上一级（项目根目录）
PROJECT_ROOT = Path(__file__).resolve().parent.parent
KNOWLEDGE_DIR = PROJECT_ROOT / "docs" / "knowledge"

# Collection 名字里特意带模型标记：未来要升级到 bge-large 时新建一个集合
# 而不是污染老数据，应用端只需切配置不动数据。提前埋下"可演进"思路。
COLLECTION_NAME = "knowledge_base_bge_small_zh"

CHROMA_HOST = os.environ.get("CHROMA_HOST", "localhost")
CHROMA_PORT = int(os.environ.get("CHROMA_PORT", "8000"))

# 切块：中文一块 500 字符是 sweet spot——既能容纳 1-2 段完整内容，
# 又不会大到稀释关键信号。50 字符 overlap 让跨块的句子有上下文。
CHUNK_SIZE = 500
CHUNK_OVERLAP = 50

# BGE 模型来源：
#   - 不设环境变量时，用 HuggingFace 上的模型 ID，首次会联网下载（配 HF_ENDPOINT 走镜像）
#   - 设了 EMBED_MODEL_NAME（指向已下载好的本地目录）时，直接离线加载、零下载
#     例：.env 里写 EMBED_MODEL_NAME=/home/matt/models/bge-small-zh-v1.5
# 不管是 HF ID 还是本地路径，sentence-transformers 都能直接吃，代码无需区分。
EMBED_MODEL_NAME = os.environ.get("EMBED_MODEL_NAME", "BAAI/bge-small-zh-v1.5")


def build_embeddings() -> BgeSmallZhEmbeddings:
    """
    构造本地 BGE 嵌入模型。

    首次运行会从 HuggingFace 下载约 95MB 到 ~/.cache/huggingface/。
    国内网络下载慢时，先设镜像再跑：
        export HF_ENDPOINT=https://hf-mirror.com

    BgeSmallZhEmbeddings 的实现细节见 embeddings.py。
    它内部用 sentence-transformers，对外暴露 LangChain 的 Embeddings 接口。
    """
    return BgeSmallZhEmbeddings(model_name=EMBED_MODEL_NAME, device="cpu")


def load_documents() -> list[Document]:
    """读 docs/knowledge/*.md，每个文件先当成一个 Document"""
    docs = []
    for md_path in sorted(KNOWLEDGE_DIR.glob("*.md")):
        text = md_path.read_text(encoding="utf-8")
        docs.append(Document(
            page_content=text,
            metadata={"source": md_path.name},
        ))
        print(f"  📄 加载: {md_path.name} ({len(text)} 字符)")
    return docs


def main():
    print("=" * 64)
    print("Step 3 · LangChain Python 文档入库")
    print("=" * 64)

    print(f"\n[1/4] 加载文档（{KNOWLEDGE_DIR}）...")
    docs = load_documents()
    if not docs:
        print(f"⚠️  没找到任何 .md 文件")
        return

    print(f"\n[2/4] 切块（chunk_size={CHUNK_SIZE}, overlap={CHUNK_OVERLAP}）...")
    # RecursiveCharacterTextSplitter 会按 separators 列表"从粗到细"递归切分：
    # 先按双换行切段落 → 段落太大再按单换行切行 → 还太大按句号切句子...
    # 这种策略对中文友好，比 CharacterTextSplitter（只按一种分隔符切）效果好
    splitter = RecursiveCharacterTextSplitter(
        chunk_size=CHUNK_SIZE,
        chunk_overlap=CHUNK_OVERLAP,
        separators=["\n\n", "\n", "。", "！", "？", "，", " ", ""],
    )
    chunks = splitter.split_documents(docs)
    print(f"  ✂️  切成 {len(chunks)} 个块")

    print(f"\n[3/4] 加载 embedding 模型（{EMBED_MODEL_NAME}）...")
    if os.path.isdir(EMBED_MODEL_NAME):
        print(f"      检测到本地模型目录，离线加载（不联网）")
    else:
        print(f"      首次会从 HuggingFace 下载 ~95MB，慢的话设 HF_ENDPOINT=https://hf-mirror.com")
    embeddings = build_embeddings()

    print(f"\n[4/4] 写入 Chroma（{CHROMA_HOST}:{CHROMA_PORT} / collection={COLLECTION_NAME}）...")
    # HttpClient 连远程 Chroma 服务（项目里跑在 localhost:8000）。
    # 另一种是 PersistentClient（嵌入式直接读写本地文件），但三版本要共用同一个
    # 实例，所以必须 HTTP 模式。
    client = chromadb.HttpClient(host=CHROMA_HOST, port=CHROMA_PORT)

    # 入库前先清空旧 collection（演示时会反复运行，避免数据重复堆积）
    try:
        client.delete_collection(COLLECTION_NAME)
        print(f"  🗑️  已删除旧 collection: {COLLECTION_NAME}")
    except Exception:
        pass  # 第一次跑时不存在，正常

    Chroma.from_documents(
        documents=chunks,
        embedding=embeddings,
        client=client,
        collection_name=COLLECTION_NAME,
    )

    # 自检：拿 collection 信息确认数据真的写进去了
    collection = client.get_collection(COLLECTION_NAME)
    print(f"  ✅ collection 现有 {collection.count()} 条向量")

    print(f"\n🎉 Step 3 · Python 版入库完成")
    print(f"   下一步：启动 FastAPI（./scripts/start-python.sh），调 POST /search 试试")


if __name__ == "__main__":
    main()
