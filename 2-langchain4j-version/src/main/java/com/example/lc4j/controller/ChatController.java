package com.example.lc4j.controller;

import com.example.lc4j.ai.AgentAssistant;
import com.example.lc4j.ai.Assistant;
import com.example.lc4j.ai.Extractor;
import com.example.lc4j.ai.Person;
import com.example.lc4j.ai.RagAssistant;
import com.example.lc4j.rag.RagService;
import com.example.lc4j.user.AuthService;
import com.example.lc4j.user.ChatHistoryService;
import com.example.lc4j.user.User;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import com.example.lc4j.user.Message;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ============================================================
 * 对话接口（Step 2 版）
 * ------------------------------------------------------------
 * 跟 Step 1 比变化：
 *   - 不再直接持有 ChatModel，而是注入两个 AiService（Assistant / Extractor）
 *   - /chat 支持 conversationId（多轮记忆）+ 可定制 role/language/maxWords
 *   - 新增 /extract（结构化输出演示）
 *
 * 控制器代码变得非常薄——因为脏活累活都被 AiServices 接管了。
 * 这就是声明式编程的好处：业务代码只描述"要什么"，不描述"怎么做"。
 * ============================================================
 */
@RestController
public class ChatController {

    private final Assistant assistant;
    private final Extractor extractor;
    private final RagService ragService;          // Step 3 新增
    private final RagAssistant ragAssistant;      // Step 4 新增
    private final AgentAssistant agentAssistant;  // Step 5 新增
    private final AuthService authService;        // 用户系统
    private final ChatHistoryService history;     // 用户系统
    private final StreamingChatModel streamingChatModel;  // 登录态流式：喂 DB 上下文

    // 多轮对话带入 LLM 的历史消息条数上限（控制 token）。知识库场景默认只带 10 条。
    @org.springframework.beans.factory.annotation.Value("${CHAT_HISTORY_MESSAGES:10}")
    private int historyMessages;

    public ChatController(Assistant assistant, Extractor extractor,
                          RagService ragService, RagAssistant ragAssistant,
                          AgentAssistant agentAssistant,
                          AuthService authService, ChatHistoryService history,
                          StreamingChatModel streamingChatModel) {
        this.assistant = assistant;
        this.extractor = extractor;
        this.ragService = ragService;
        this.ragAssistant = ragAssistant;
        this.agentAssistant = agentAssistant;
        this.authService = authService;
        this.history = history;
        this.streamingChatModel = streamingChatModel;
    }

    @GetMapping("/")
    public ServiceInfo root() {
        return new ServiceInfo("ok", "langchain4j", 6);
    }

    /**
     * 多轮对话接口。
     * <p>
     * 不传 conversationId → 当场生成 UUID，响应里带回去。客户端下一轮带上这个 ID。
     * 同一个 ID 下连续问"我刚才问了什么？"，模型能正确回答。
     */
    @PostMapping("/chat")
    public ChatReplyDto chat(@RequestBody ChatRequestDto req) {
        String convId = req.conversationId() != null && !req.conversationId().isBlank()
                ? req.conversationId()
                : UUID.randomUUID().toString();

        // 三个参数都有默认值，让前端可以只传 message
        String role = orDefault(req.role(), "简洁、友好的中文助手");
        String language = orDefault(req.language(), "中文");
        int maxWords = req.maxWords() != null ? req.maxWords() : 200;

        // 注意：assistant.chat() 在底层会自动：
        //   1) 用 memoryId 从 ChatMemory 取出本会话历史
        //   2) 拼成完整的消息列表（system + history + 本轮 user）
        //   3) 调模型
        //   4) 把本轮 user + ai 追加回 memory
        // 这些 Step 1 都得我们自己写，现在一行 API 调用搞定。
        String reply = assistant.chat(convId, role, language, maxWords, req.message());

        return new ChatReplyDto(reply, convId);
    }

    /**
     * Step 6 新增：流式对话（SSE）。
     * <p>
     * 返回 {@link SseEmitter}——Spring MVC 的"服务端推送"对象。我们把
     * assistant.chatStream() 返回的 TokenStream 的三个回调桥接到 emitter：
     *   onPartialResponse(token) → emitter.send 一段 token
     *   onCompleteResponse       → emitter.complete() 收尾
     *   onError                  → emitter.completeWithError()
     * TokenStream.start() 在后台线程异步跑，方法本身立刻返回 emitter，
     * 之后 token 才陆续推给浏览器。
     * <p>
     * 用 Map 当 send 的载荷，Spring 会自动用 Jackson 序列化成 JSON
     * （如 data:{"token":"你"}），前端按 JSON 解析，省去手动转义。
     */
    @PostMapping("/chat/stream")
    public SseEmitter chatStream(
            @RequestBody ChatRequestDto req,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        String convId = req.conversationId() != null && !req.conversationId().isBlank()
                ? req.conversationId()
                : UUID.randomUUID().toString();
        String role = orDefault(req.role(), "简洁、友好的中文助手");
        String language = orDefault(req.language(), "中文");
        int maxWords = req.maxWords() != null ? req.maxWords() : 200;

        // 可选登录：带了有效 token 就拿到用户（登录后才落库存历史），否则匿名
        User user = authService.optionalUser(authHeader);
        Long userId = user != null ? user.getId() : null;

        // 超时设长一点（DeepSeek 长回答可能要几十秒）
        SseEmitter emitter = new SseEmitter(120_000L);

        // 先把 conversationId 发给前端，方便它续聊
        try {
            emitter.send(Map.of("conversationId", convId));
        } catch (IOException e) {
            emitter.completeWithError(e);
            return emitter;
        }

        // token 推送回调（两条路径共用）
        java.util.function.Consumer<String> onToken = token -> {
            try {
                emitter.send(Map.of("token", token));
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        };

        if (userId != null) {
            // ===== 登录：上下文从 DB 取，直接调底层 streamingChatModel =====
            // 这样重启/换机器也能续历史（AiService 的进程内记忆做不到）。
            List<ChatMessage> msgs = new ArrayList<>();
            msgs.add(SystemMessage.from(
                    "你是一个" + role + "。使用" + language + "回答用户问题，回答控制在 " + maxWords + " 字以内。"));
            for (Message m : history.recentMessages(userId, convId, historyMessages)) {
                msgs.add("user".equals(m.getRole())
                        ? UserMessage.from(m.getContent())
                        : AiMessage.from(m.getContent()));
            }
            msgs.add(UserMessage.from(req.message()));

            streamingChatModel.chat(msgs, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String token) {
                    onToken.accept(token);
                }
                @Override
                public void onCompleteResponse(ChatResponse resp) {
                    try {
                        history.saveTurn(userId, convId, req.message(), resp.aiMessage().text());
                    } catch (Exception ignored) {
                    }
                    try {
                        emitter.send(Map.of("done", true, "conversationId", convId));
                    } catch (IOException ignored) {
                    }
                    emitter.complete();
                }
                @Override
                public void onError(Throwable t) {
                    emitter.completeWithError(t);
                }
            });
        } else {
            // ===== 匿名：用 AiService 的进程内记忆 =====
            assistant.chatStream(convId, role, language, maxWords, req.message())
                    .onPartialResponse(onToken)
                    .onCompleteResponse(resp -> {
                        try {
                            emitter.send(Map.of("done", true, "conversationId", convId));
                        } catch (IOException ignored) {
                        }
                        emitter.complete();
                    })
                    .onError(emitter::completeWithError)
                    .start();
        }

        return emitter;
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

    /** 逻辑删除某段会话（需登录、仅本人）。 */
    @DeleteMapping("/conversations/{convId}")
    public Map<String, String> deleteConversation(
            @PathVariable String convId,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        User user = authService.requireUser(authHeader);
        history.softDelete(user.getId(), convId);
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

    /**
     * 结构化输出：人物信息抽取。
     * 返回值就是 typed 的 Person record，自动序列化成 JSON。
     */
    @PostMapping("/extract")
    public Person extract(@RequestBody ExtractRequestDto req) {
        return extractor.extract(req.text());
    }


    // ============================================================
    // Step 3 新增：/ingest（入库）+ /search（检索）
    // ------------------------------------------------------------
    // Python 版把入库做成 standalone 脚本，Java 这边做成 REST 接口。
    // 触发方式：
    //   curl -X POST http://localhost:8080/ingest
    //   curl -X POST http://localhost:8080/search -H "Content-Type: application/json" \
    //        -d '{"query":"RAG 是什么", "k":3}'
    // ============================================================

    /**
     * 把 docs/knowledge/ 下的文档切块、嵌入并写进 Chroma。
     * <p>
     * 首次跑会等几秒——主要是 BGE 模型 ONNX 第一次加载 + 嵌入计算。
     * 第二次跑因为模型已经加载在内存里，会快很多。
     */
    @PostMapping("/ingest")
    public RagService.IngestResult ingest() {
        return ragService.ingest();
    }

    /**
     * 检索接口：给一个问题，返回 Top K 个相关文档块。
     * 用来单独验证 RAG 的"前半段"——确认检索准确，Step 4 才把检索结果喂给 LLM。
     */
    @PostMapping("/search")
    public SearchResponseDto search(@RequestBody SearchRequestDto req) {
        int k = req.k() != null ? req.k() : 3;
        List<EmbeddingMatch<TextSegment>> matches = ragService.search(req.query(), k);

        List<SearchHitDto> hits = matches.stream()
                .map(m -> new SearchHitDto(
                        m.embedded().text(),
                        m.embedded().metadata().getString("file_name"),  // langchain4j 把文件名放这个 key
                        m.score()  // 注意：langchain4j 用的是相似度分数（越大越相似），跟 Python 的 distance 含义相反
                ))
                .toList();

        return new SearchResponseDto(req.query(), hits);
    }

    // ============================================================
    // Step 4 新增：/ask —— 完整 RAG 问答（检索 + 生成）
    // ------------------------------------------------------------
    // 对比 Step 3 的 /search（只检索、原样返回文档块），
    // /ask 把检索到的资料喂给 LLM 生成自然语言回答，并附上引用来源。
    //
    // 注意控制器代码有多薄：检索、拼 prompt、调 LLM 全被 ragAssistant 吞了，
    // 这里只负责"调一下 + 把 Result 拆成 DTO"——这就是 LangChain4j 声明式 RAG 的封装程度。
    //   curl -X POST http://localhost:8080/ask -H "Content-Type: application/json" \
    //        -d '{"question":"RAG 是什么"}'
    // ============================================================
    @PostMapping("/ask")
    public AskResponseDto ask(@RequestBody AskRequestDto req) {
        // Result<String>：.content() 是答案，.sources() 是这次引用到的文档块
        Result<String> result = ragAssistant.ask(req.question());

        List<SearchHitDto> sources = result.sources().stream()
                .map(this::toHit)
                .toList();

        return new AskResponseDto(result.content(), sources);
    }

    /** 把检索到的一块 Content 转成对外的 DTO（取原文 + 来源文件名） */
    private SearchHitDto toHit(Content content) {
        TextSegment seg = content.textSegment();
        // file_name 是 FileSystemDocumentLoader 入库时自动塞进 metadata 的 key
        String source = seg.metadata() != null ? seg.metadata().getString("file_name") : null;
        // ContentRetriever 的 sources 不直接带相似度分数，这里留 null
        return new SearchHitDto(seg.text(), source, null);
    }

    // ============================================================
    // Step 5 新增：/agent —— Agent + 工具调用
    // ------------------------------------------------------------
    // 对比 Step 4 /ask（"必定先检索"）：/agent 把检索做成工具，让模型自己决定
    // 要不要查知识库。控制器依旧极薄——agentAssistant.chat() 内部，AiServices
    // 会自动跑完"模型决定调工具 → 执行 KnowledgeBaseTools → 回灌 → 再问模型"的循环。
    //   curl -X POST http://localhost:8080/agent -H "Content-Type: application/json" \
    //        -d '{"message":"RAG 是什么"}'      # 会触发检索
    //   curl -X POST http://localhost:8080/agent -H "Content-Type: application/json" \
    //        -d '{"message":"你好"}'            # 不会检索，直接答
    // ============================================================
    @PostMapping("/agent")
    public AgentReplyDto agent(@RequestBody AgentRequestDto req) {
        String reply = agentAssistant.chat(req.message());
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
    public record AskRequestDto(String question) {}
    public record AskResponseDto(String answer, List<SearchHitDto> sources) {}

    // Step 5 新增 DTO
    public record AgentRequestDto(String message) {}
    public record AgentReplyDto(String reply) {}

    // 用户系统 DTO
    public record ConversationDto(String id, String title, String backend, String updatedAt) {}
    public record HistoryMessageDto(String role, String content) {}
    public record RenameDto(String title) {}
}
