package com.example.lc4j.ai;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * ============================================================
 * Step 5 · Agent 助手（带工具调用）
 * ------------------------------------------------------------
 * 跟 Step 4 的 RagAssistant 的区别：
 *   RagAssistant —— 挂的是 ContentRetriever，"每次必检索"（被动注入 context）
 *   AgentAssistant —— 挂的是 @Tool 工具，"是否检索由模型自己决定"（主动调用）
 *
 * 装配见 Application.java#agentAssistant：把 KnowledgeBaseTools 通过 .tools()
 * 交给 AiServices，模型就能在需要时自动调用其中的 @Tool 方法。
 *
 * 系统提示里要讲清楚"什么时候用工具、什么时候直接答"，引导模型正确决策。
 * ============================================================
 */
public interface AgentAssistant {

    @SystemMessage("""
            你是一个知识库智能助手，可以调用 searchKnowledgeBase 工具检索专业知识库。
            规则：
            1. 用户问到 RAG、LangChain、向量库等专业内容时，先调用工具检索，再基于检索结果回答；
            2. 普通闲聊、打招呼、你已知的常识问题，直接回答，不要调用工具；
            3. 基于工具返回的资料回答时，不要编造资料里没有的内容。
            用中文回答，简洁清楚。
            """)
    String chat(@UserMessage String message);
}
