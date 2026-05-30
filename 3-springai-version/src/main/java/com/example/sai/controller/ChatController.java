package com.example.sai.controller;

import com.example.sai.dto.Person;
import com.example.sai.rag.RagService;
import com.example.sai.tools.KnowledgeBaseTools;
import com.example.sai.user.AuthService;
import com.example.sai.user.ChatHistoryService;
import com.example.sai.user.User;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Map;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * ============================================================
 * 对话接口（Spring AI · Step 2 版）
 * ------------------------------------------------------------
 * 跟 LangChain4j 版的差异点：
 *   - 不用单独定义"Assistant"接口，所有调用都走 ChatClient fluent API
 *   - 对话记忆是通过 .advisors(...).param(ChatMemory.CONVERSATION_ID, id) 关联的
 *   - 结构化输出用 .call().entity(MyClass.class)，比 LangChain4j 的注解写法更"代码化"
 * ============================================================
 */
@RestController
public class ChatController {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final RagService ragService;              // Step 3 新增
    private final VectorStore vectorStore;            // Step 4 新增：给 QuestionAnswerAdvisor 用
    private final KnowledgeBaseTools knowledgeBaseTools;  // Step 5 新增：Agent 工具
    private final AuthService authService;            // 用户系统
    private final ChatHistoryService history;         // 用户系统

    // 多轮对话带入 LLM 的历史消息条数上限（控制 token）。知识库场景默认只带 10 条。
    @org.springframework.beans.factory.annotation.Value("${CHAT_HISTORY_MESSAGES:10}")
    private int historyMessages;

    public ChatController(ChatClient chatClient, ChatMemory chatMemory,
                          RagService ragService, VectorStore vectorStore,
                          KnowledgeBaseTools knowledgeBaseTools,
                          AuthService authService, ChatHistoryService history) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
        this.ragService = ragService;
        this.vectorStore = vectorStore;
        this.knowledgeBaseTools = knowledgeBaseTools;
        this.authService = authService;
        this.history = history;
    }

    @GetMapping("/")
    public ServiceInfo root() {
        return new ServiceInfo("ok", "springai", 6);
    }

    /**
     * Step 6 新增：流式对话（SSE）。
     * <p>
     * Spring AI 的流式就是把 .call() 换成 .stream().content()，返回 Flux&lt;String&gt;
     * （响应式的 token 流）。Spring MVC 看到方法返回 Flux + produces=text/event-stream，
     * 会自动把每个元素作为一条 SSE 事件推给浏览器。
     * <p>
     * 这里把原始 token 流包成跟另外两版一致的 JSON 协议（先 conversationId，
     * 再一串 {"token":...}，最后 {"done":true}），方便前端用同一套解析逻辑。
     * 记忆 Advisor 会在流结束时自动把完整回答写回 ChatMemory，所以流式也支持多轮。
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<Map<String, Object>> chatStream(
            @RequestBody ChatRequestDto req,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        String convId = req.conversationId() != null && !req.conversationId().isBlank()
                ? req.conversationId()
                : java.util.UUID.randomUUID().toString();
        String role = orDefault(req.role(), "简洁、友好的中文助手");
        String language = orDefault(req.language(), "中文");
        int maxWords = req.maxWords() != null ? req.maxWords() : 200;

        // 可选登录：带了有效 token 就拿到用户（登录后才落库存历史）
        User user = authService.optionalUser(authHeader);
        Long userId = user != null ? user.getId() : null;

        // 登录：把 DB 历史"回种"到进程内 ChatMemory（仅当该会话记忆为空，例如刚重启）。
        // 这样记忆 Advisor 接着用就带上了历史——重启/换机器也能续上下文。
        if (userId != null && chatMemory.get(convId).isEmpty()) {
            List<Message> seed = new ArrayList<>();
            for (com.example.sai.user.Message m : history.recentMessages(userId, convId, historyMessages)) {
                seed.add("user".equals(m.getRole())
                        ? new UserMessage(m.getContent())
                        : new AssistantMessage(m.getContent()));
            }
            if (!seed.isEmpty()) {
                chatMemory.add(convId, seed);
            }
        }

        // 边流边把 token 累加起来，流结束时拿到完整回答用于落库
        StringBuilder full = new StringBuilder();

        Flux<String> tokens = chatClient
                .prompt()
                .system(s -> s.text("你是一个{role}。使用{language}回答用户问题，回答控制在 {maxWords} 字以内。")
                              .param("role", role)
                              .param("language", language)
                              .param("maxWords", maxWords))
                .user(req.message())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, convId))
                .stream()
                .content()
                .doOnNext(full::append);

        // 末尾的 done 用 Flux.defer：concat 顺序订阅，跑到它时 tokens 已结束、full 已完整，
        // 正好在这里把这一轮问答落库（登录用户）。
        Flux<Map<String, Object>> tail = Flux.defer(() -> {
            if (userId != null) {
                try {
                    history.saveTurn(userId, convId, req.message(), full.toString());
                } catch (Exception ignored) {
                }
            }
            return Flux.just(Map.<String, Object>of("done", true, "conversationId", convId));
        });

        // 拼成统一协议：开头 conversationId → 中间一串 token → 末尾 done(+落库)
        return Flux.concat(
                Flux.just(Map.<String, Object>of("conversationId", convId)),
                tokens.map(t -> Map.<String, Object>of("token", t)),
                tail
        );
    }

    // ============================================================
    // 用户系统：历史查询（需登录）
    // ============================================================
    @GetMapping("/conversations")
    public List<ConversationDto> listConversations(
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        User user = authService.requireUser(authHeader);
        return history.listConversations(user.getId()).stream()
                .map(c -> new ConversationDto(
                        c.getId(), c.getTitle(), c.getBackend(),
                        c.getUpdatedAt() != null ? c.getUpdatedAt().toString() : null))
                .toList();
    }

    @GetMapping("/conversations/{convId}/messages")
    public List<HistoryMessageDto> conversationMessages(
            @PathVariable String convId,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        User user = authService.requireUser(authHeader);
        return history.listMessages(user.getId(), convId).stream()
                .map(m -> new HistoryMessageDto(m.getRole(), m.getContent()))
                .toList();
    }

    @PostMapping("/chat")
    public ChatReplyDto chat(@RequestBody ChatRequestDto req) {
        String convId = req.conversationId() != null && !req.conversationId().isBlank()
                ? req.conversationId()
                : UUID.randomUUID().toString();

        String role = orDefault(req.role(), "简洁、友好的中文助手");
        String language = orDefault(req.language(), "中文");
        int maxWords = req.maxWords() != null ? req.maxWords() : 200;

        String reply = chatClient
                .prompt()
                // 1) 参数化的 system prompt——Spring AI 的模板语法是 {var}
                .system(s -> s.text("你是一个{role}。使用{language}回答用户问题，回答控制在 {maxWords} 字以内。")
                              .param("role", role)
                              .param("language", language)
                              .param("maxWords", maxWords))
                // 2) 本轮 user 消息
                .user(req.message())
                // 3) 把 conversationId 传给 MessageChatMemoryAdvisor（Application.java 注册的）
                //    Advisor 看到这个 param 后会按 convId 取历史 + 写回历史
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, convId))
                .call()
                .content();

        return new ChatReplyDto(reply, convId);
    }

    /**
     * 结构化输出：返回 typed Person。
     * <p>
     * .entity(Person.class) 干了三件事：
     *   1) 反射 Person 字段生成 JSON Schema
     *   2) 把 schema 注入 prompt（让模型按格式回答）
     *   3) 用 Jackson 把回复反序列化成 Person
     * <p>
     * 故意不带 advisors —— 抽取任务不需要历史，每次独立调用。
     */
    @PostMapping("/extract")
    public Person extract(@RequestBody ExtractRequestDto req) {
        return chatClient
                .prompt()
                .system("你是一个信息抽取助手。从用户输入中抽取人物信息，未提及的字段留空。")
                .user(req.text())
                .call()
                .entity(Person.class);
    }

    /**
     * 删除会话：登录用户逻辑删除 DB 里这段会话（校验归属，置 status=1），
     * 同时清掉 Spring AI 的对话记忆。
     */
    @DeleteMapping("/conversations/{convId}")
    public Map<String, String> deleteConversation(
            @PathVariable String convId,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        User user = authService.requireUser(authHeader);
        history.softDelete(user.getId(), convId);
        chatMemory.clear(convId);
        return Map.of("status", "deleted", "conversationId", convId);
    }

    /** 重命名会话（需登录、仅本人）。 */
    @PatchMapping("/conversations/{convId}")
    public Map<String, String> renameConversation(
            @PathVariable String convId,
            @RequestBody RenameDto req,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        User user = authService.requireUser(authHeader);
        history.rename(user.getId(), convId, req.title());
        return Map.of("id", convId, "title", req.title());
    }

    // ============================================================
    // Step 3 新增：/ingest（入库）+ /search（检索）
    // ============================================================

    /** 把 docs/knowledge/ 下文档切块嵌入并写进 Chroma */
    @PostMapping("/ingest")
    public RagService.IngestResult ingest() throws IOException {
        return ragService.ingest();
    }

    /** Top K 检索 */
    @PostMapping("/search")
    public SearchResponseDto search(@RequestBody SearchRequestDto req) {
        int k = req.k() != null ? req.k() : 3;
        List<Document> hits = ragService.search(req.query(), k);

        List<SearchHitDto> dtos = hits.stream()
                .map(d -> new SearchHitDto(
                        d.getText(),
                        // metadata 里 source 是我们 ingest 时塞进去的
                        d.getMetadata().getOrDefault("source", "unknown").toString(),
                        // Spring AI 返回的 score 是相似度（0~1，越大越相似），跟 LangChain Python
                        // 的 distance 含义相反——文档要讲清楚避免误解
                        d.getScore() == null ? null : d.getScore().doubleValue()
                ))
                .toList();

        return new SearchResponseDto(req.query(), dtos);
    }

    // ============================================================
    // Step 4 新增：/ask —— 完整 RAG 问答（QuestionAnswerAdvisor 版）
    // ------------------------------------------------------------
    // Spring AI 的地道 RAG 写法：不手动检索、不手动拼 prompt，而是挂一个
    // QuestionAnswerAdvisor。它是个"对话切面"，在调模型前自动：
    //   1. 用问题去 vectorStore 检索 Top-K
    //   2. 把检索结果按内置模板拼进 prompt
    //   3. 调模型后，把检索到的文档塞进 ChatResponse 的 metadata
    //      （key = QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS），方便我们溯源
    //
    //   curl -X POST http://localhost:8082/ask -H "Content-Type: application/json" \
    //        -d '{"question":"RAG 是什么"}'
    // ============================================================
    @PostMapping("/ask")
    public AskResponseDto ask(@RequestBody AskRequestDto req) {
        int k = req.k() != null ? req.k() : 4;

        // 每次按需创建 advisor，绑定本次的 topK。advisor 很轻，不必做成单例 Bean。
        QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder().topK(k).build())
                .build();

        // 用 .chatResponse() 而不是 .content()——因为我们还要从 metadata 里
        // 把检索到的来源文档掏出来，光要答案字符串的话 .content() 就够了。
        ChatResponse resp = chatClient
                .prompt()
                // 角色 + 防幻觉约束。advisor 注入的资料是英文内置模板包着的，
                // 这条 system 负责把回答压成"中文、简洁、不编造"。
                .system("你是一个严谨的知识库问答助手。只能根据检索到的资料回答，"
                        + "资料里没有就直说\"根据已有资料无法回答\"，不要编造。"
                        + "用中文回答，控制在 300 字以内。")
                .user(req.question())
                .advisors(qaAdvisor)
                .call()
                .chatResponse();

        String answer = resp.getResult().getOutput().getText();

        // 从 metadata 里取回 advisor 检索到的文档（就是这次回答的依据）
        @SuppressWarnings("unchecked")
        List<Document> retrieved = (List<Document>) resp.getMetadata()
                .get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);

        List<SearchHitDto> sources = retrieved == null ? List.of() : retrieved.stream()
                .map(d -> new SearchHitDto(
                        d.getText(),
                        d.getMetadata().getOrDefault("source", "unknown").toString(),
                        d.getScore() == null ? null : d.getScore().doubleValue()
                ))
                .toList();

        return new AskResponseDto(answer, sources);
    }

    // ============================================================
    // Step 5 新增：/agent —— Agent + 工具调用
    // ------------------------------------------------------------
    // 对比 Step 4 /ask（QuestionAnswerAdvisor，"必定先检索"）：
    // /agent 用 .tools(knowledgeBaseTools) 把检索做成工具，让模型自己决定要不要查。
    // Spring AI 会自动跑完"模型决定调工具 → 执行 → 回灌 → 再问模型"的循环。
    //
    // ⚠️ .options(model=deepseek-chat)：必须临时把模型切到 deepseek-chat (V3)，
    //    因为 application.yml 里默认模型可能是 deepseek-v4-pro（思维链模型不支持
    //    function calling）。Spring AI 允许按请求覆盖模型选项，正好用上。
    //   curl -X POST http://localhost:8082/agent -H "Content-Type: application/json" \
    //        -d '{"message":"RAG 是什么"}'   # 触发检索
    //   curl -X POST http://localhost:8082/agent -H "Content-Type: application/json" \
    //        -d '{"message":"你好"}'         # 不检索，直接答
    // ============================================================
    @PostMapping("/agent")
    public AgentReplyDto agent(@RequestBody AgentRequestDto req) {
        String reply = chatClient
                .prompt()
                .system("你是一个知识库智能助手，可以调用 searchKnowledgeBase 工具检索专业知识库。"
                        + "用户问到 RAG、LangChain、向量库等专业内容时，先调工具检索再基于结果回答；"
                        + "普通闲聊、打招呼、常识问题直接回答，不要调工具；"
                        + "不要编造工具没给的内容。用中文简洁回答。")
                .user(req.message())
                // 把工具交给模型；模型决定调用时 Spring AI 自动执行 + 回灌
                .tools(knowledgeBaseTools)
                // 临时覆盖模型为 deepseek-chat（V3 才支持 function calling）
                .options(OpenAiChatOptions.builder().model("deepseek-chat").build())
                .call()
                .content();
        return new AgentReplyDto(reply);
    }

    private static String orDefault(String s, String dflt) {
        return s != null && !s.isBlank() ? s : dflt;
    }

    public record ChatRequestDto(
            String message,
            String conversationId,
            String role,
            String language,
            Integer maxWords
    ) {}
    public record ChatReplyDto(String reply, String conversationId) {}
    public record ExtractRequestDto(String text) {}
    public record ServiceInfo(String status, String service, int step) {}

    // Step 3 新增 DTO
    public record SearchRequestDto(String query, Integer k) {}
    public record SearchHitDto(String content, String source, Double score) {}
    public record SearchResponseDto(String query, List<SearchHitDto> hits) {}

    // Step 4 新增 DTO
    public record AskRequestDto(String question, Integer k) {}
    public record AskResponseDto(String answer, List<SearchHitDto> sources) {}

    // Step 5 新增 DTO
    public record AgentRequestDto(String message) {}
    public record AgentReplyDto(String reply) {}

    // 用户系统 DTO
    public record ConversationDto(String id, String title, String backend, String updatedAt) {}
    public record HistoryMessageDto(String role, String content) {}
    public record RenameDto(String title) {}
}
