package com.example.lc4j;

import com.example.lc4j.ai.AgentAssistant;
import com.example.lc4j.ai.Assistant;
import com.example.lc4j.ai.Extractor;
import com.example.lc4j.ai.KnowledgeBaseTools;
import com.example.lc4j.ai.RagAssistant;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15.BgeSmallZhV15EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.chroma.ChromaApiVersion;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * ============================================================
 * Spring Boot 启动入口 + LangChain4j 装配中心
 * ------------------------------------------------------------
 * Step 2 相比 Step 1 多了两个 Bean：
 *   - Assistant（带对话记忆 + Prompt 模板的 AI Service）
 *   - Extractor（结构化输出的 AI Service）
 *
 * 注意 AiServices.builder() 的 .chatMemoryProvider —— 这是关键：
 *   它是个 lambda：memoryId -> ChatMemory
 *   同一个 memoryId 多次调用拿到的是同一个 memory 实例，
 *   不同 memoryId 拿到不同实例 → 实现"按会话隔离"的多轮对话。
 * ============================================================
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    // @Primary：现在有两个 ChatModel Bean（主模型 + 工具模型），其它按类型注入
    // ChatModel 的地方（assistant/extractor/ragAssistant）默认拿这个主模型。
    @Bean
    @Primary
    public ChatModel chatModel(
            @Value("${deepseek.api-key}") String apiKey,
            @Value("${deepseek.base-url}") String baseUrl,
            @Value("${deepseek.model}") String modelName,
            @Value("${deepseek.temperature:0.7}") Double temperature,
            @Value("${deepseek.max-tokens:1024}") Integer maxTokens
    ) {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .build();
    }

    /**
     * Step 5 新增：工具调用专用模型，固定 deepseek-chat (V3)。
     * <p>
     * 为什么不能复用主模型：主模型可能被配成 deepseek-v4-pro / reasoner（思维链模型），
     * 而 DeepSeek 的思维链模型【不支持 tool_choice / function calling】——Agent 调工具
     * 底层就是 function calling，所以必须用 V3。这跟 /extract 用 deepseek-chat 是同一个原因。
     */
    @Bean
    public ChatModel toolChatModel(
            @Value("${deepseek.api-key}") String apiKey,
            @Value("${deepseek.base-url}") String baseUrl
    ) {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName("deepseek-chat")
                .temperature(0.3)
                .maxTokens(1024)
                .build();
    }

    /**
     * Step 6 新增：流式对话模型。
     * <p>
     * 跟 chatModel 用同样的配置，只是类换成 OpenAiStreamingChatModel——它在底层用
     * SSE 接收 DeepSeek 一段段吐出来的 token。AiServices 里凡是返回 TokenStream 的
     * 方法（Assistant.chatStream）都会用这个流式模型。普通流式对话不涉及工具，
     * 所以用主模型（DEEPSEEK_MODEL）即可，不用像 Agent 那样强制 deepseek-chat。
     */
    @Bean
    public StreamingChatModel streamingChatModel(
            @Value("${deepseek.api-key}") String apiKey,
            @Value("${deepseek.base-url}") String baseUrl,
            @Value("${deepseek.model}") String modelName,
            @Value("${deepseek.temperature:0.7}") Double temperature,
            @Value("${deepseek.max-tokens:1024}") Integer maxTokens
    ) {
        return OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .build();
    }

    /**
     * 带记忆的对话助手。
     * <p>
     * chatMemoryProvider 的工作原理：
     *   AiServices 内部维护一个 Map&lt;memoryId, ChatMemory&gt;。
     *   每次调 assistant.chat(convId, ...) 时：
     *     - 如果 convId 没见过 → 调 lambda 新建一个 memory 存进去
     *     - 见过 → 直接复用
     *   memory 自己负责把每轮 user/ai 消息追加进去。
     * <p>
     * MessageWindowChatMemory.withMaxMessages(20)：保留最近 20 条消息，超出丢最早的。
     * 生产环境可以换成 TokenWindowChatMemory（按 token 数限制）+ 持久化存储。
     */
    @Bean
    public Assistant assistant(ChatModel chatModel, StreamingChatModel streamingChatModel) {
        return AiServices.builder(Assistant.class)
                .chatModel(chatModel)                      // chat() 用它（同步）
                .streamingChatModel(streamingChatModel)    // chatStream() 用它（Step 6 流式）
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(20))
                .build();
    }

    /**
     * 结构化输出 AI Service：返回 Person 而不是 String。
     * 不需要 memory，因为每次抽取是独立任务。
     */
    @Bean
    public Extractor extractor(ChatModel chatModel) {
        return AiServices.builder(Extractor.class)
                .chatModel(chatModel)
                .build();
    }

    // ============================================================
    // Step 3 新增：Embedding 模型 + 向量库
    // ============================================================

    /**
     * BGE-small-zh-v1.5 本地嵌入模型。
     * <p>
     * 这个类的实现就在 langchain4j-embeddings-bge-small-zh-v15 这个依赖里——
     * ONNX 模型文件直接打包在 jar 内部，new 出来时从 classpath 加载，
     * 整个过程不联网，启动几秒就绪。
     * <p>
     * 跟 Python 版的 HuggingFaceEmbeddings 对比：
     *   - Python：第一次 new 时从 HuggingFace 下载 ~95MB 到本地缓存
     *   - LangChain4j：模型已在 jar 内，零下载（首次 mvn install 会下载 jar，下载完缓存住）
     * <p>
     * 向量维度：512（跟 Python 版完全一致）
     */
    @Bean
    public EmbeddingModel embeddingModel() {
        return new BgeSmallZhV15EmbeddingModel();
    }

    /**
     * Chroma 向量库连接（HTTP 客户端模式）。
     * <p>
     * 三个版本（Python / LangChain4j / Spring AI）都连到同一个 localhost:8000
     * 的 chroma 服务，但各自用同一个 collection 名 —— "上一个跑 ingest 的版本"
     * 拥有这份数据，下一个版本跑 ingest 会清空重写。
     * <p>
     * 如果集合还不存在，ChromaEmbeddingStore 在第一次写入时自动创建。
     */
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore(
            @Value("${chroma.base-url}") String chromaBaseUrl,
            @Value("${chroma.collection-name}") String collectionName
    ) {
        return ChromaEmbeddingStore.builder()
                .baseUrl(chromaBaseUrl)
                .collectionName(collectionName)
                // ★ 必须显式声明 V2：Chroma 1.x 服务端只剩 v2 API，v1 已废弃（调 v1 报 405）。
                //   ChromaEmbeddingStore 默认仍是 V1（为兼容老 Chroma），所以这里要手动指定。
                .apiVersion(ChromaApiVersion.V2)
                // v2 引入了 tenant / database 两级命名空间。用 Chroma 的标准默认值即可，
                // 跟 Python 的 chromadb.HttpClient（默认也是 default_tenant/default_database）对齐。
                .tenantName("default_tenant")
                .databaseName("default_database")
                .build();
    }

    // ============================================================
    // Step 4 新增：ContentRetriever + RAG 问答 Ai Service
    // ============================================================

    /**
     * 内容检索器：RAG 的"检索"那一半，被声明式地挂到 RagAssistant 上。
     * <p>
     * 它包住 embeddingStore + embeddingModel：每次 RagAssistant.ask() 被调用，
     * 框架先用这个 retriever 把问题嵌入、检索 Top-K 个片段，再拼进系统提示。
     * <p>
     * maxResults(4)：取最相关的 4 块当参考资料，跟 Python 版 /ask 的 k 默认值对齐。
     * <p>
     * ⚠️ 一个跟 Step 3 /search 的差异：Step 3 我们手动给查询拼了 BGE 的检索前缀
     * "为这个句子生成表示以用于检索相关文章："，而 EmbeddingStoreContentRetriever
     * 内部直接 embed 原始问题、不加这个前缀。检索质量略降一点点但完全够用——
     * 这正是"框架自动化"和"手动控制"的取舍。
     */
    @Bean
    public ContentRetriever contentRetriever(
            EmbeddingStore<TextSegment> embeddingStore,
            EmbeddingModel embeddingModel
    ) {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(4)
                .build();
    }

    /**
     * RAG 问答助手。关键就这一行 .contentRetriever(...)——挂上去之后，
     * 检索 + 拼 prompt 全自动，业务代码（ChatController）里看不到任何检索逻辑。
     * <p>
     * 故意不挂 chatMemory：Step 4 的 RAG 问答是单轮的（每个问题独立、只看资料），
     * "带记忆的多轮 RAG"留到后续步骤再叠加。
     */
    @Bean
    public RagAssistant ragAssistant(ChatModel chatModel, ContentRetriever contentRetriever) {
        return AiServices.builder(RagAssistant.class)
                .chatModel(chatModel)
                .contentRetriever(contentRetriever)
                .build();
    }

    // ============================================================
    // Step 5 新增：Agent 助手（带工具调用）
    // ============================================================

    /**
     * Agent 助手：把 KnowledgeBaseTools 通过 .tools() 挂上去，模型就能自主调用其中的
     * @Tool 方法。AiServices 会自动处理"模型要求调工具 → 执行 → 回灌 → 再问模型"的整个循环。
     * <p>
     * 关键点：
     *   - 用 @Qualifier("toolChatModel") 指定 deepseek-chat（V3 才支持 function calling）
     *   - 不挂 contentRetriever（那是 Step 4 的"必检索"），改挂 tools（让模型自己决定检索）
     *   - 这里不挂 chatMemory，保持单轮，聚焦演示"工具调用"本身
     */
    @Bean
    public AgentAssistant agentAssistant(
            @Qualifier("toolChatModel") ChatModel toolChatModel,
            KnowledgeBaseTools knowledgeBaseTools
    ) {
        return AiServices.builder(AgentAssistant.class)
                .chatModel(toolChatModel)
                .tools(knowledgeBaseTools)
                .build();
    }
}
