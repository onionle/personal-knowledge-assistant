package com.example.lc4j.ai;

import com.example.lc4j.rag.RagService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * ============================================================
 * Step 5 · 知识库检索"工具"
 * ------------------------------------------------------------
 * @Tool 把一个普通 Java 方法暴露成"模型能调用的工具"。
 * LangChain4j 启动时会读 @Tool 的描述 + 方法参数，生成 function calling 的
 * 工具定义；运行时如果模型决定调用它，AiServices 会自动反射调用这个方法、
 * 把返回值回灌给模型——整个"调工具"的循环你一行都不用写。
 *
 * 注解里的描述（@Tool 的 value、@P 的描述）等于"写给模型看的说明书"：
 * 模型靠它判断"什么时候该用这个工具、参数填什么"，所以要写清楚适用场景。
 *
 * 对比 Python 版：Python 要自己写 while 循环处理 tool_calls；
 * 这里声明式注解 + AiServices 全自动。
 * ============================================================
 */
@Component
public class KnowledgeBaseTools {

    private final RagService ragService;

    public KnowledgeBaseTools(RagService ragService) {
        this.ragService = ragService;
    }

    @Tool("检索本地知识库，获取关于 RAG、LangChain、LangChain4j、Spring AI、向量库等专业主题的资料。"
            + "当用户的问题涉及这些专业知识、需要事实依据时调用本工具；"
            + "普通闲聊、打招呼、常识性问题不要调用。")
    public String searchKnowledgeBase(
            @P("用来检索的关键词或问题") String query
    ) {
        List<EmbeddingMatch<TextSegment>> matches = ragService.search(query, 4);
        if (matches.isEmpty()) {
            return "知识库里没有找到相关资料。";
        }
        // 把命中的片段拼成一段文本回给模型（带来源，便于模型引用）
        return matches.stream()
                .map(m -> {
                    TextSegment seg = m.embedded();
                    String source = seg.metadata() != null ? seg.metadata().getString("file_name") : "unknown";
                    return "[来源：" + source + "]\n" + seg.text();
                })
                .collect(Collectors.joining("\n\n"));
    }
}
