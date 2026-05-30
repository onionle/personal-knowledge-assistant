package com.example.lc4j.ai;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * ============================================================
 * AI Service：LangChain4j 最有特色的特性
 * ------------------------------------------------------------
 * 你只需要声明一个接口（连实现都不写！），LangChain4j 在启动时
 * 通过动态代理把它实现成一个真正能调用 LLM 的对象。
 *
 * 注解作用：
 *   @SystemMessage  系统提示词模板，{{var}} 是变量占位符
 *   @UserMessage    标记哪个参数是用户输入（不写也行，但显式更清楚）
 *   @V              把 Java 方法参数绑定到 {{var}}
 *   @MemoryId       同一个 memoryId 共享一份 ChatMemory，实现多轮对话
 *
 * 跟 Spring AI 的 ChatClient 风格对比：
 *   - Spring AI: 链式 fluent → chatClient.prompt().system().user().call().content()
 *   - LangChain4j: 声明式 → 直接调 Java 方法
 * ============================================================
 */
public interface Assistant {

    @SystemMessage("你是一个{{role}}。使用{{language}}回答用户问题，回答控制在 {{maxWords}} 字以内。")
    String chat(
            @MemoryId String conversationId,
            @V("role") String role,
            @V("language") String language,
            @V("maxWords") int maxWords,
            @UserMessage String message
    );

    /**
     * Step 6 新增：流式版对话。
     * <p>
     * 跟 chat() 几乎一样，唯一区别是返回 {@link TokenStream} 而不是 String——
     * 方法签名返回 TokenStream 时，AiServices 会自动用 streamingChatModel 跑流式，
     * 通过回调（onPartialResponse / onCompleteResponse / onError）把 token 一段段交出来。
     * 和普通 chat() 共享同一份 ChatMemory（靠同一个 memoryId），所以流式也支持多轮。
     */
    @SystemMessage("你是一个{{role}}。使用{{language}}回答用户问题，回答控制在 {{maxWords}} 字以内。")
    TokenStream chatStream(
            @MemoryId String conversationId,
            @V("role") String role,
            @V("language") String language,
            @V("maxWords") int maxWords,
            @UserMessage String message
    );
}
