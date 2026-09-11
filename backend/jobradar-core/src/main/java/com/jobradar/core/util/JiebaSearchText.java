package com.jobradar.core.util;

import com.huaban.analysis.jieba.JiebaSegmenter;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 全文检索词元工具：jieba 分词桥接 jobs.search_vector（GIN 索引的 TSVECTOR 生成列）。
 *
 * <p>为什么需要它：PostgreSQL 内置中文分词为零——{@code to_tsvector('simple', text)}
 * 只按空格/标点切，「大模型工程师」整个串算一个词元，查询「大模型」永远命中不了。
 * 所以 schema 设计成「应用层分词」：写入侧用 jieba 把岗位文本切成空格分隔的词元存进
 * {@code search_text}，生成列再对它建 tsvector；检索侧用同一分词器切查询词、
 * 以 {@code &}（AND）拼 tsquery。两侧分词器一致是命中率的前提。
 *
 * <p>两个模式的用途：
 * <ul>
 *   <li>索引侧用 {@code INDEX} 模式（细粒度全切分，尽量多产词元，提升召回）；</li>
 *   <li>查询侧用 {@code SEARCH} 模式（粗粒度，用户意图更精确，减少噪音命中）。</li>
 * </ul>
 *
 * <p>安全：{@link #tsQuery} 输出只含词元与 {@code " & "}，词元经白名单正则过滤
 * （tsquery 元字符 {@code & | ! ( ) : < >} 一律剥除），且最终作为绑定参数传给
 * {@code to_tsquery('simple', ?)}——不存在 SQL/tsquery 注入面。
 */
public final class JiebaSearchText {

    /** JD 参与索引的最大字符数：长 JD 截断，控制 tsvector 体积与分词耗时 */
    private static final int JD_INDEX_CHARS = 4000;

    /** JiebaSegmenter 是无状态的词典持有者，官方用法即全局单例（加载词典约 1s，只付一次） */
    private static final JiebaSegmenter SEGMENTER = new JiebaSegmenter();

    private JiebaSearchText() {
    }

    /**
     * 索引文本：把岗位的可检索字段分词后拼成空格分隔的词元串（写入 jobs.search_text）。
     * null/空白字段自动跳过；词元去重（LinkedHashSet 保序，便于排查时阅读）。
     */
    public static String indexText(String... parts) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            String text = part.length() > JD_INDEX_CHARS ? part.substring(0, JD_INDEX_CHARS) : part;
            for (var seg : SEGMENTER.process(text, JiebaSegmenter.SegMode.INDEX)) {
                String token = sanitize(seg.word);
                if (token != null) {
                    tokens.add(token);
                }
            }
        }
        return String.join(" ", tokens);
    }

    /**
     * 查询词 → tsquery 串：jieba SEARCH 模式切词，白名单过滤后用 {@code &} 连接
     * （AND 语义：所有词元都命中才算匹配，比 OR 更贴合"关键词收窄"的搜索意图）。
     * 返回空串表示查询词无有效词元（纯标点等），调用方应视为"零命中"。
     */
    public static String tsQuery(String q) {
        if (q == null || q.isBlank()) {
            return "";
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (var seg : SEGMENTER.process(q.trim(), JiebaSegmenter.SegMode.SEARCH)) {
            String token = sanitize(seg.word);
            if (token != null) {
                tokens.add(token);
            }
        }
        return String.join(" & ", tokens);
    }

    /** 词元清洗：剥除 tsquery 元字符与标点，统一小写（tsvector 'simple' 配置同样小写化） */
    private static String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        String token = raw.replaceAll("[^\\p{L}\\p{N}+#.]", "").toLowerCase();
        // 剥完可能只剩 '+','#','.' 这类孤立符号，不是有效词元
        return token.chars().anyMatch(Character::isLetterOrDigit) ? token : null;
    }
}
