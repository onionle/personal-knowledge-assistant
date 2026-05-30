package com.example.sai.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * ============================================================
 * 抽取出来的人物信息（结构化输出目标类型）
 * ------------------------------------------------------------
 * 用 Java 21 record。Spring AI 的 .entity() 调用底层走 Jackson 反序列化，
 * @JsonPropertyDescription 会被写进自动生成的 JSON Schema，模型看得到。
 *
 * LangChain4j 那边用的是自己的 @Description 注解，Spring AI 这边用 Jackson 的标准注解，
 * 这俩生态选了不同的依赖路径，但目的一样。
 * ============================================================
 */
public record Person(
        @JsonPropertyDescription("人物姓名") String name,
        @JsonPropertyDescription("年龄，未提及就留空") Integer age,
        @JsonPropertyDescription("职业，未提及就留空") String occupation,
        @JsonPropertyDescription("一句话概括这个人") String summary
) {}
