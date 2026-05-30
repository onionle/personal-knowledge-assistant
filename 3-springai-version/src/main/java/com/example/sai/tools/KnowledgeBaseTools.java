package com.example.sai.tools;

import com.example.sai.rag.RagService;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * ============================================================
 * Step 5 · 知识库检索"工具"（Spring AI 版）
 * ------------------------------------------------------------
 * Spring AI 用 org.springframework.ai.tool.annotation.@Tool 把方法标成工具。
 * 在 ChatController 里通过 chatClient.prompt().tools(knowledgeBaseTools) 挂上去后，
 * 模型决定调用时，Spring AI 会自动反射执行这个方法、把返回值回灌给模型，
 * 整个工具调用循环框架全包了——和 LangChain4j 的 AiServices.tools() 思路一致，
 * 跟 Python 手写 while 循环形成对比。
 *
 * @Tool 的 description / @ToolParam 的 description 就是"写给模型看的说明书"，
 * 模型靠它判断何时调用、参数怎么填。
 * ============================================================
 */
@Component
public class KnowledgeBaseTools {

    private final RagService ragService;

    public KnowledgeBaseTools(RagService ragService) {
        this.ragService = ragService;
    }

    @Tool(description = "检索本地知识库，获取关于 RAG、LangChain、LangChain4j、Spring AI、向量库等"
            + "专业主题的资料。当用户问题涉及这些专业知识、需要事实依据时调用；"
            + "普通闲聊、打招呼、常识性问题不要调用。")
    public String searchKnowledgeBase(
            @ToolParam(description = "用来检索的关键词或问题") String query
    ) {
        List<Document> hits = ragService.search(query, 4);
        if (hits.isEmpty()) {
            return "知识库里没有找到相关资料。";
        }
        return hits.stream()
                .map(d -> {
                    String source = d.getMetadata().getOrDefault("source", "unknown").toString();
                    return "[来源：" + source + "]\n" + d.getText();
                })
                .collect(Collectors.joining("\n\n"));
    }
}
