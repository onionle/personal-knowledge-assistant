package com.example.sai.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * ============================================================
 * Step 3 · Spring AI 版的 RAG 服务
 * ------------------------------------------------------------
 * 跟 LangChain4j 版的差异：
 *   - LangChain4j 用 FileSystemDocumentLoader 一次性加载整个目录
 *     Spring AI 没有完全等价的 API，自己用 Files.walk + TextReader 拼一下
 *   - LangChain4j 用 DocumentSplitters.recursive(maxChars, overlap)（按字符）
 *     Spring AI 用 TokenTextSplitter（按 token 数）—— 两种切法各有优势
 *   - VectorStore 是 Spring AI 的统一抽象，底层是 Chroma 还是 Pinecone 业务码不变
 * ============================================================
 */
@Service
public class RagService {

    private final VectorStore vectorStore;
    private final Path knowledgeDir;
    private final int chunkSize;
    // 注意：Spring AI 1.0 的 TokenTextSplitter 不支持 chunk overlap，
    // 所以我们不注入 rag.chunk-overlap。yml 里那个配置项是为了三版本对齐保留的，
    // Spring AI 这边读了也用不上。

    public RagService(
            VectorStore vectorStore,
            @Value("${knowledge.dir}") String knowledgeDirPath,
            @Value("${rag.chunk-size}") int chunkSize
    ) {
        this.vectorStore = vectorStore;
        this.knowledgeDir = Paths.get(knowledgeDirPath).toAbsolutePath().normalize();
        this.chunkSize = chunkSize;
    }

    /**
     * 把 knowledgeDir 下所有 .md/.txt 加载、切块、嵌入并写入 VectorStore。
     */
    public IngestResult ingest() throws IOException {
        // 1) 清空旧数据。Spring AI 的 VectorStore 没有 removeAll()，
        //    但演示时下我们走"先把所有现存 ID 删一遍"的近似做法：
        //    简单起见这里直接靠"重启 chroma 服务清数据"。如果想代码里清，
        //    需要用 ChromaApi 直接删 collection 再让 starter 重新创建。
        //    生产场景通常按 doc ID 增量更新，不需要全删。

        // 2) 扫描目录、读文件、变成 Document
        List<Document> documents = loadDocuments();
        if (documents.isEmpty()) {
            throw new IllegalStateException("没在 " + knowledgeDir + " 找到任何文档");
        }

        // 3) 切块。TokenTextSplitter 5 参数构造器：
        //    - defaultChunkSize       目标每块 token 数（不是字符数！）
        //    - minChunkSizeChars      切完后每块至少多少字符（防止切得过碎）
        //    - minChunkLengthToEmbed  低于此长度的块不嵌入（防止空块）
        //    - maxNumChunks           上限保护
        //    - keepSeparator          是否保留分隔符
        //
        // ⚠️ 重要：Spring AI 1.0 的 TokenTextSplitter 不支持 chunk overlap（重叠）！
        // 这是它跟 Python (RecursiveCharacterTextSplitter) / LangChain4j
        // (DocumentSplitters.recursive) 的一个明显能力差异。yml 里我们仍然保留
        // rag.chunk-overlap 配置以保持三版本接口对齐，但 Spring AI 这边只能空挂着。
        // 想要 overlap 得自己实现 TextSplitter 或借用第三方库。
        //
        // 另一个差异：token 切法跟字符切法不完全等价——chunkSize=500 token 切出来
        // 实际字符数比 500 多（中文 1 字符 ≈ 1.5-2 token）。
        // Spring AI 1.1.x 起构造器加了 punctuationMarks 参数，改用 builder 更稳（标点用默认）
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(chunkSize)
                .withMinChunkSizeChars(50)
                .withMinChunkLengthToEmbed(5)
                .withMaxNumChunks(10000)
                .withKeepSeparator(true)
                .build();
        List<Document> chunks = splitter.apply(documents);

        // 4) 写库 = 嵌入 + 上传。VectorStore.add() 内部会调 EmbeddingModel
        // 把每个 Document 的内容嵌入成向量，再写进 Chroma collection
        vectorStore.add(chunks);

        return new IngestResult(documents.size(), chunks.size(), knowledgeDir.toString());
    }

    /**
     * 检索：返回 Top K 个相关文档块。
     */
    public List<Document> search(String query, int topK) {
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .build();
        return vectorStore.similaritySearch(request);
    }

    /** 扫描知识库目录读所有 .md/.txt */
    private List<Document> loadDocuments() throws IOException {
        List<Document> result = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(knowledgeDir)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> {
                      String name = p.getFileName().toString().toLowerCase();
                      return name.endsWith(".md") || name.endsWith(".txt");
                  })
                  .sorted()
                  .forEach(p -> {
                      // TextReader 把整个文件读成一个 Document，metadata 里塞文件名
                      TextReader reader = new TextReader(new FileSystemResource(p));
                      reader.getCustomMetadata().put("source", p.getFileName().toString());
                      result.addAll(reader.get());
                  });
        }
        return result;
    }

    /** 入库结果给接口返回用 */
    public record IngestResult(int sourceDocumentCount, int chunkCount, String knowledgeDir) {}
}
