package com.example.lc4j.ai;

import dev.langchain4j.model.output.structured.Description;

/**
 * ============================================================
 * 抽取出来的人物信息（结构化输出目标类型）
 * ------------------------------------------------------------
 * 用 Java 21 record，几行就定义好一个不可变数据类。
 * @Description 注解会被 LangChain4j 写进自动生成的 JSON Schema，
 * 模型看到这些描述能更准确地决定填什么。
 *
 * 字段都用包装类型（Integer 而不是 int），因为"未提及"要能用 null 表示。
 * ============================================================
 */
public record Person(
        @Description("人物姓名") String name,
        @Description("年龄，未提及就留空") Integer age,
        @Description("职业，未提及就留空") String occupation,
        @Description("一句话概括这个人") String summary
) {}
