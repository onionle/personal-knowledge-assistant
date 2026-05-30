package com.example.lc4j.ai;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * ============================================================
 * Step 4 · RAG 问答 AI Service（LangChain4j 最"魔法"的一面）
 * ------------------------------------------------------------
 * 对比 Python 版的"手动拼接"：
 *   Python 要自己 检索 → 拼 context → 塞 prompt → 调 LLM 四步全写出来。
 *   LangChain4j 这边——你只声明一个接口，把 ContentRetriever 在
 *   AiServices 装配时挂上去（见 Application.java#ragAssistant），
 *   框架就会在每次调用前自动：
 *     1. 用问题去 ContentRetriever 检索相关片段
 *     2. 把片段拼进系统提示（默认模板，也可自定义）
 *     3. 调 LLM
 *   业务代码里一行检索/拼接都看不到——这就是声明式 RAG。
 *
 * 两个注解点：
 *   @SystemMessage   我们自己的角色 + 防幻觉约束；框架会把检索到的资料
 *                    追加到这段系统提示后面
 *   返回 Result<String> 而不是 String：
 *                    String 只能拿到答案；Result 还能拿到 .sources()——
 *                    就是这次回答到底引用了哪些文档块，用来做"溯源"。
 * ============================================================
 */
public interface RagAssistant {

    @SystemMessage("""
            你是一个严谨的知识库问答助手。只能根据检索到的【参考资料】回答用户问题。
            如果参考资料里找不到答案，就直说"根据已有资料无法回答"，绝对不要编造。
            使用中文回答，控制在 300 字以内。
            """)
    Result<String> ask(@UserMessage String question);
}
