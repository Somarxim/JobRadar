package com.jobradar.core.crawl;

import com.jobradar.core.domain.CrawlCategory;
import com.jobradar.core.domain.CrawlSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 通用选择器解析器：fixture HTML 验证字段提取、@attr 语法、相对链接补全、关键词过滤、假条目跳过 */
class SelectorListParserTest {

    private final SelectorListParser parser = new SelectorListParser();

    private static final String FIXTURE = """
            <html><body>
            <table>
              <tr class="job-row">
                <td><a class="jt" href="/job/101">Java工程师（2026校招）</a></td>
                <td class="co">航空工业631所</td><td class="city">西安</td><td class="date">2026-09-01</td>
              </tr>
              <tr class="job-row">
                <td><a class="jt" href="job/102">软件测试</a></td>
                <td class="co">新源公司</td><td class="city">北京</td>
              </tr>
              <tr class="job-row ad"><td colspan="4">—— 广告位 ——</td></tr>
            </table>
            </body></html>
            """;

    private static CrawlSource source(String meta) {
        CrawlSource s = new CrawlSource();
        s.setName("测试源");
        s.setUrl("https://example.com/jobs/list?page=1");
        s.setParser("selector-list");
        s.setCategory(CrawlCategory.SOE_OTHER);
        s.setMeta(meta);
        return s;
    }

    @Test
    void parsesFieldsAndResolvesRelativeUrl() {
        var meta = """
                {"item":"tr.job-row","title":"a.jt","company":".co","city":".city",
                 "date":".date","url":"a.jt@href"}
                """;
        var postings = parser.parse(source(meta), FIXTURE);

        assertThat(postings).hasSize(2); // 广告行无 a.jt，被跳过
        var first = postings.get(0);
        assertThat(first.title()).isEqualTo("Java工程师（2026校招）");
        assertThat(first.company()).isEqualTo("航空工业631所");
        assertThat(first.city()).isEqualTo("西安");
        assertThat(first.publishDate()).isEqualTo("2026-09-01");
        // 相对链接按页面 URL 补全为绝对地址
        assertThat(first.url()).isEqualTo("https://example.com/job/101");
        // "job/102" 相对当前目录 /jobs/
        assertThat(postings.get(1).url()).isEqualTo("https://example.com/jobs/job/102");
    }

    @Test
    void titleContainsFilterWorks() {
        var meta = """
                {"item":"tr.job-row","title":"a.jt","company":".co","title_contains":["工程师"]}
                """;
        var postings = parser.parse(source(meta), FIXTURE);

        assertThat(postings).hasSize(1);
        assertThat(postings.get(0).title()).contains("工程师");
    }

    @Test
    void missingRequiredMetaFailsFast() {
        var s = source("{\"item\":\"tr.job-row\"}");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> parser.parse(s, FIXTURE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("title");
    }
}
