package com.example.sai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * ============================================================
 * Spring Boot 启动入口 + Spring AI 装配中心
 * ------------------------------------------------------------
 * Step 2 相比 Step 1 多了两个 Bean：
 *   - ChatMemory          消息历史的存储（默认内存版）
 *   - ChatClient          全局共用，预装 MessageChatMemoryAdvisor
 *
 * 注意跟 LangChain4j 版的差异：
 *   LangChain4j：自己写 AiServices 接口 + chatMemoryProvider
 *   Spring AI： 用"Advisor"机制——一个责任链，对话前/后切面式地注入历史
 *               官方理念是不让你自己拼消息，所有"对话增强能力"都做成 Advisor
 *               （后面 Step 4 RAG 也是用 QuestionAnswerAdvisor 加进来的）
 * ============================================================
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    /**
     * 对话记忆存储：保留最近 20 条消息的滑动窗口。
     * <p>
     * 底层默认用 InMemoryChatMemoryRepository（进程内 Map，重启即清空）。
     * 生产可换成 JDBC / Redis 实现，注册同名 Bean 即可覆盖。
     */
    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .maxMessages(20)
                .build();
    }

    /**
     * 全局共享的 ChatClient，挂上记忆 Advisor。
     * <p>
     * MessageChatMemoryAdvisor 做的事：
     *   - 调模型前：从 ChatMemory 里把这个 conversationId 的历史拿出来塞进 prompt
     *   - 调模型后：把本轮 user/ai 消息存回 ChatMemory
     * <p>
     * 我们不在这里 .defaultSystem(...) 是因为想在每次请求时按 role 动态生成系统提示，
     * 见 ChatController#chat 里的 .system(s -> s.text(...).param(...))
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, ChatMemory chatMemory) {
        return builder
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    // ============================================================
    // Step 3 新增：本地嵌入模型 Bean
    // ------------------------------------------------------------
    // Spring AI 1.0 的 TransformersEmbeddingModel 默认下载
    // sentence-transformers/all-MiniLM-L6-v2 (~80MB, 384 维, 英文为主)。
    //
    // 默认 all-MiniLM-L6-v2 出厂可用、无需额外联网下载，但对中文检索效果不如 BGE-small-zh-v1.5。
    //   - 想换 BGE：配置 spring.ai.embedding.transformer.{onnx,tokenizer}.uri，
    //     指向社区维护的 ONNX 导出版本（如 HuggingFace 上的 Xenova/bge-small-zh-v1.5）
    //
    // 必须手动 @Bean——Spring AI 1.0 没给 TransformersEmbeddingModel 自动装配
    // （只有 OpenAI / Anthropic / Ollama 等 API 类模型有 starter 自动装配）。
    //
    // afterPropertiesSet() 触发模型下载 + ONNX 加载。同步执行，应用启动耗时 +几秒。
    // ============================================================
    @Bean
    public EmbeddingModel embeddingModel(
            @Value("${embedding.onnx.model-uri}") String modelUri,
            @Value("${embedding.onnx.tokenizer-uri}") String tokenizerUri
    ) {
        TransformersEmbeddingModel model = new TransformersEmbeddingModel();
        // ★ 关键修复（2026-05-30）：
        //   TransformersEmbeddingModel 默认的 model.onnx 地址是 GitHub raw
        //   (github.com/spring-projects/spring-ai/.../model.onnx)，而且那文件是用 Git LFS
        //   存的——国内连 GitHub LFS 经常被掐断（SocketException: Unexpected end of file），
        //   导致启动时这个 Bean 创建失败、整个应用起不来。
        //   解决：显式把模型/分词器指向已下载好的本地文件（见 application.yml 的 embedding.onnx.*）。
        //   tokenizer.json 其实 jar 里就自带真文件，用 classpath: 也行；model.onnx 在 jar 里只是
        //   133 字节的 LFS 指针，所以必须用外部真文件。
        model.setModelResource(modelUri);
        model.setTokenizerResource(tokenizerUri);
        try {
            model.afterPropertiesSet();
        } catch (Exception e) {
            throw new IllegalStateException("初始化 TransformersEmbeddingModel 失败（确认 "
                    + modelUri + " 存在且为完整的 ONNX 模型，不是 LFS 指针）", e);
        }
        return model;
    }
}
