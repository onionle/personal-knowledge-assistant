package com.example.lc4j.ai;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * ============================================================
 * 结构化输出 AI Service
 * ------------------------------------------------------------
 * 关键：方法返回类型是自定义的 Person 记录，而不是 String。
 * LangChain4j 看到这种返回类型会自动：
 *   1) 反射 Person 字段，生成 JSON Schema
 *   2) 把"请按这个 schema 输出"指令拼到 prompt 里
 *   3) 拿到模型回复后用 Jackson 反序列化成 Person 对象
 *
 * 也就是说——上层业务代码完全不用碰 JSON 字符串解析，拿到的就是 typed 对象。
 * ============================================================
 */
public interface Extractor {

    @SystemMessage("你是一个信息抽取助手。从用户输入中抽取人物信息，未提及的字段留空。")
    Person extract(@UserMessage String text);
}
