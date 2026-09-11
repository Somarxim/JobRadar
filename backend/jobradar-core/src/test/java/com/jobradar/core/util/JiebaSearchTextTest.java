package com.jobradar.core.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JiebaSearchText 的分词契约测试。断言刻意做"包含"式而非精确词元序列——
 * jieba 词典升级会微调切分结果，测试要锁的是行为契约（中文可切、AND 拼接、
 * tsquery 元字符被剥除），不是某个版本的具体切法。
 */
class JiebaSearchTextTest {

    @Test
    void indexTextSegmentsChineseIntoTokens() {
        // 实测（jieba 1.0.2）："大模型应用工程师" → "大 模型 应用 工程 工程师"
        String text = JiebaSearchText.indexText("大模型应用工程师", "杭州");
        assertThat(text).contains("模型").contains("工程师").contains("杭州");
        // 词元以空格分隔（to_tsvector('simple') 按空格切词的前提）
        assertThat(text).contains(" ");
    }

    @Test
    void indexTextSkipsNullBlankAndDedupes() {
        String text = JiebaSearchText.indexText(null, "", "  ", "Java 后端 Java");
        assertThat(text).isNotEmpty();
        assertThat(text.split(" ")).contains("java");
        // 去重：java 只出现一次
        assertThat(text.split(" ")).filteredOn("java"::equals).hasSize(1);
    }

    @Test
    void tsQueryJoinsTokensWithAnd() {
        String tsq = JiebaSearchText.tsQuery("Java后端开发");
        assertThat(tsq).contains("java").contains("&");
    }

    @Test
    void tsQueryStripsOperatorsAndKeepsTechTokens() {
        String tsq = JiebaSearchText.tsQuery("C++ (校招) | 工程师!");
        // tsquery 元字符全部剥除（安全边界：输出只含词元与 " & "）
        assertThat(tsq).doesNotContain("|", "!", "(", ")");
        assertThat(tsq).contains("c++");
    }

    @Test
    void tsQueryBlankOrPurePunctuationYieldsEmpty() {
        assertThat(JiebaSearchText.tsQuery(null)).isEmpty();
        assertThat(JiebaSearchText.tsQuery("   ")).isEmpty();
        assertThat(JiebaSearchText.tsQuery("！！！…")).isEmpty();
    }
}
