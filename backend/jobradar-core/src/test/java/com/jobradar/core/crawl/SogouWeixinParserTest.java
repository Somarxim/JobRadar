package com.jobradar.core.crawl;

import com.jobradar.core.domain.CrawlSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 搜狗微信解析器：基于 2026-09-15 实测的搜索结果页结构。
 * 重点验证：列表提取、公司三级兜底识别、链接跳转解析（打桩防触网）。
 */
class SogouWeixinParserTest {

    /** 打桩 fetcher：跳转解析不触网，直接返回规范 mp 链接 */
    private final PageFetcher stubFetcher = new PageFetcher() {
        @Override
        public String resolveFinalUrl(String url) {
            return "https://mp.weixin.qq.com/s/resolved";
        }
    };
    private final SogouWeixinParser parser = new SogouWeixinParser(stubFetcher, new CompanyAliases());

    /** 真实结果页缩编：正常条目（含 timeConvert 时间脚本）+ 无标题坏条目 */
    private static final String SAMPLE = """
            <html><body>
            <ul class="news-list">
              <li>
                <div class="txt-box">
                  <h3><a href="/link?url=abc123">【宣讲预告】航天科工二院2027届校园招聘正式启动！</a></h3>
                  <p class="txt-info">航天科工二院2027届校园招聘正式启动，招聘岗位包括总体设计、软件开发……</p>
                  <div class="s-p"><a>航天科工二院</a><span class="s2">
                    <script>timeConvert('1789441200')</script></span></div>
                </div>
              </li>
              <li>
                <div class="txt-box">
                  <h3><a href="/link?url=def456">中国航空工业集团2027届校园招聘</a></h3>
                  <p class="txt-info">航空工业集团校招启动……</p>
                  <div class="s-p"><a>国资小新</a></div>
                </div>
              </li>
              <li><div class="txt-box"><h3></h3></div></li>
            </ul>
            </body></html>
            """;

    private static CrawlSource source() {
        CrawlSource s = new CrawlSource();
        s.setName("微信·测试");
        s.setUrl("https://weixin.sogou.com/weixin");
        s.setParser("sogou-weixin");
        s.setMeta("{\"request\":{\"params\":{\"query\":\"航天科工 2027校园招聘\"}}}");
        return s;
    }

    @Test
    void parsesArticlesAndResolvesLinks() {
        List<RawJobPosting> out = parser.parse(source(), SAMPLE);

        assertThat(out).hasSize(2); // 无标题坏条目被跳过

        RawJobPosting first = out.get(0);
        assertThat(first.title()).contains("航天科工二院2027届校园招聘");
        // 公司识别：标题含「航天科工二院」→ 别名表（其标准名自身也是键）
        assertThat(first.company()).contains("航天科工");
        // 链接已被打桩解析为规范 mp 链接
        assertThat(first.url()).isEqualTo("https://mp.weixin.qq.com/s/resolved");
        assertThat(first.publishDate()).isNotBlank();
        assertThat(first.jdText()).contains("公众号").contains("航天科工二院");

        // 第二条：标题无别名命中、公众号「国资小新」是转载号（名字不在标题里）→ 落种子关键词
        // （本源的查询词是「航天科工 2027校园招聘」，跨集团文章落到种子词是文档化的兜底行为）
        RawJobPosting second = out.get(1);
        assertThat(second.company()).isEqualTo("航天科工");
        assertThat(second.jdText()).contains("按标题搜索原文");
    }
}
