"""
========================================================================
Step 3 · 本地 BGE 嵌入模型适配器
------------------------------------------------------------------------
背景：本来想用官方的 `langchain-huggingface` 包，但它当前还锁死
      langchain-core<1.0，跟我们用的 langchain-core 1.x 冲突。
      所以这里直接用 sentence-transformers 自己包一个 Embeddings 子类。

好处其实更多：
  - 没有第三方版本兼容性束缚
  - 看得见模型加载和向量计算每一步在做什么
  - 想加缓存、想换设备、想做 batch，都改这一个文件
  - LangChain 的 Embeddings 接口稳定，未来兼容性反而比 wrapper 更好

ingest.py 和 main.py 都从这里 import，保证写库 / 读库用的是完全一致的嵌入逻辑。
========================================================================
"""

from typing import List

from langchain_core.embeddings import Embeddings
from sentence_transformers import SentenceTransformer


class BgeSmallZhEmbeddings(Embeddings):
    """
    BGE-small-zh-v1.5 嵌入模型，512 维。

    LangChain Embeddings 接口要实现两个方法：
      - embed_documents(texts) -> List[List[float]]   写入向量库时用
      - embed_query(text)      -> List[float]         查询时用
    "查询"和"文档"分开是有讲究的——某些模型（包括 BGE 系列）
    在查询时拼一段固定 prefix 能显著提升检索准确度，
    但文档侧拼这个 prefix 反而会损害效果。
    """

    DEFAULT_MODEL = "BAAI/bge-small-zh-v1.5"
    # BGE 训练时见过的"检索查询专用"前缀，只用在 embed_query 不用在 embed_documents
    DEFAULT_QUERY_INSTRUCTION = "为这个句子生成表示以用于检索相关文章："

    def __init__(
        self,
        model_name: str = DEFAULT_MODEL,
        device: str = "cpu",
        query_instruction: str = DEFAULT_QUERY_INSTRUCTION,
        normalize: bool = True,
    ):
        # SentenceTransformer 加载会做这些事：
        #   1) 第一次：从 HuggingFace 下模型文件到 ~/.cache/huggingface/
        #      （慢的话设 export HF_ENDPOINT=https://hf-mirror.com）
        #   2) 之后：直接从缓存读，秒载
        self._model = SentenceTransformer(model_name, device=device)
        self._query_instruction = query_instruction
        # normalize=True 让向量长度变成 1，此时点积 = 余弦相似度
        # Chroma 默认用 L2 距离，归一化后 L2 距离单调对应余弦距离，效果等同
        self._normalize = normalize

    def embed_documents(self, texts: List[str]) -> List[List[float]]:
        """批量嵌入文档块。不加任何 prefix。"""
        vectors = self._model.encode(
            texts,
            normalize_embeddings=self._normalize,
            show_progress_bar=False,
        )
        # numpy ndarray → 普通 list（Chroma / LangChain 接口要的是 List[List[float]]）
        return vectors.tolist()

    def embed_query(self, text: str) -> List[float]:
        """嵌入单个查询。会拼上 BGE 的检索专用 prefix。"""
        prefixed = self._query_instruction + text
        vector = self._model.encode(
            prefixed,
            normalize_embeddings=self._normalize,
            show_progress_bar=False,
        )
        return vector.tolist()
