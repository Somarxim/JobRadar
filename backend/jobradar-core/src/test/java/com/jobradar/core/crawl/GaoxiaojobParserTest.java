package com.jobradar.core.crawl;

import com.jobradar.core.domain.CrawlSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 高校人才网解析器：基于 2026-09-15 实测的 SSR 内联 JSON 结构。
 * 重点验证：内联 JSON 提取 + filterKeywords 过滤 + 相对链接绝对化。
 */
class GaoxiaojobParserTest {

    private final GaoxiaojobParser parser = new GaoxiaojobParser();

    private static final String SAMPLE = """
            <html><head></head><body><div id="app"></div>
            <script>
            window.__JOB_INDEX_EMBED__ = {"list":[
              {"jobId":"1797050","jobName":"软件研发工程师","companyName":"航天科技某研究所",
               "areaName":"西安市","education":"硕士","companyNatureName":"科研设计单位",
               "companyTypeName":"科研院所","releaseTime":"2026-09-14","url":"/job/1797050.html",
               "announcementName":"航天科技某研究所2027校园招聘"},
              {"jobId":"1797051","jobName":"电力工程学院专任教师","companyName":"重庆水利电力职业技术学院",
               "areaName":"重庆市","education":"博士","releaseTime":"2026-09-14",
               "url":"https://www.gaoxiaojob.com/job/1797051.html"}
            ]};
            </script></body></html>
            """;

    private static CrawlSource source(String meta) {
        CrawlSource s = new CrawlSource();
        s.setName("高校人才网·测试");
        s.setUrl("https://www.gaoxiaojob.com/job");
        s.setParser("gaoxiaojob");
        s.setMeta(meta);
        return s;
    }

    @Test
    void extractsInlineJsonAndAbsolutizesRelativeUrl() {
        List<RawJobPosting> out = parser.parse(source(null), SAMPLE);

        assertThat(out).hasSize(2);
        RawJobPosting first = out.get(0);
        assertThat(first.company()).isEqualTo("航天科技某研究所");
        assertThat(first.url()).isEqualTo("https://www.gaoxiaojob.com/job/1797050.html");
        assertThat(first.publishDate()).isEqualTo("2026-09-14");
        assertThat(first.jdText()).contains("学历要求：硕士");
        // 绝对链接原样保留
        assertThat(out.get(1).url()).isEqualTo("https://www.gaoxiaojob.com/job/1797051.html");
    }

    @Test
    void filterKeywordsKeepOnlyMatchingPostings() {
        String meta = "{\"filterKeywords\":[\"研究所\",\"航天\"]}";
        List<RawJobPosting> out = parser.parse(source(meta), SAMPLE);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).company()).contains("研究所");
    }

    @Test
    void missingEmbedReturnsEmpty() {
        assertThat(parser.parse(source(null), "<html><body>改版了</body></html>")).isEmpty();
    }
}
