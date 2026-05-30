package com.example.lc4j.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * ============================================================
 * Step 3 · LangChain4j 版的 RAG 服务
 * ------------------------------------------------------------
 * 两个职责：
 *   1. ingest()  把 docs/knowledge/*.md 切块、嵌入、写进 Chroma
 *   2. search()  把问题嵌入，去 Chroma 检索 Top K 个相关文档块
 *
 * 跟 Python 版的差异：
 *   - Python 把入库做成 standalone 脚本（ingest.py），离线任务跑一次完事
 *   - LangChain4j（Spring Boot 风格）把入库做成 REST 端点 /ingest，按需触发
 *   两种风格都很常见，业界没有标准答案
 * ============================================================
 */
@Service
public class RagService {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final Path knowledgeDir;
    private final int chunkSize;
    private final int chunkOverlap;

    public RagService(
            EmbeddingModel embeddingModel,
            EmbeddingStore<TextSegment> embeddingStore,
            @Value("${knowledge.dir}") String knowledgeDirPath,
            @Value("${rag.chunk-size}") int chunkSize,
            @Value("${rag.chunk-overlap}") int chunkOverlap
    ) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        // 相对路径相对于"应用启动的工作目录"，即 2-langchain4j-version/
        // mvn spring-boot:run 会从模块目录启动，所以 "../docs/knowledge" 指向项目根的 docs
        this.knowledgeDir = Paths.get(knowledgeDirPath).toAbsolutePath().normalize();
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
    }

    /**
     * 入库：扫描 knowledge.dir 下所有 .md/.txt，切块嵌入后写进 Chroma。
     *
     * @return 实际写入的文档块数量
     */
    public IngestResult ingest() {
        // 1) 清空旧数据。removeAll() 删该 collection 里所有 entry 但保留 collection 本身。
        //    演示时反复运行，避免数据堆积。生产里通常按 ID 增量更新而不是全删。
        embeddingStore.removeAll();

        // 2) 加载文档
        // FileSystemDocumentLoader.loadDocuments(path, parser) 会递归读取目录下所有文件，
        // 每个文件解析成一个 Document（page_content + metadata）
        List<Document> documents = FileSystemDocumentLoader.loadDocuments(
                knowledgeDir, new TextDocumentParser());

        if (documents.isEmpty()) {
            throw new IllegalStateException("没在 " + knowledgeDir + " 找到任何文档");
        }

        // 3) 入库 = 切块 + 嵌入 + 写库，由 EmbeddingStoreIngestor 一条龙搞定
        // DocumentSplitters.recursive(maxChars, overlap) 跟 Python 的
        // RecursiveCharacterTextSplitter 类似——从大粒度（段落）到小粒度（字符）逐级切
        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                .documentSplitter(DocumentSplitters.recursive(chunkSize, chunkOverlap))
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .build();

        var result = ingestor.ingest(documents);
        // 返回里有 tokenUsage（嵌入消耗的 token）和被写入的 segment 列表大小
        return new IngestResult(
                documents.size(),
                result.tokenUsage() == null ? 0 : result.tokenUsage().inputTokenCount(),
                knowledgeDir.toString()
        );
    }

    /**
     * 检索：把 query 嵌入后找 Top K 个最相似的文档块。
     *
     * 注意：BGE 模型在训练时见过"检索查询专用前缀"，加上这个前缀能显著提升准确度。
     * Python 的 HuggingFaceEmbeddings 有 query_instruction 参数自动加，
     * LangChain4j 的 BgeSmallZhV15EmbeddingModel 没这个抽象，所以这里手动拼。
     */
    public List<EmbeddingMatch<TextSegment>> search(String query, int maxResults) {
        String prefixedQuery = "为这个句子生成表示以用于检索相关文章：" + query;
        Embedding queryEmbedding = embeddingModel.embed(prefixedQuery).content();

        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(maxResults)
                .build();

        return embeddingStore.search(request).matches();
    }

    /** 入库结果，给 REST 接口返回用 */
    public record IngestResult(int sourceDocumentCount, int totalTokensEmbedded, String knowledgeDir) {}
}
