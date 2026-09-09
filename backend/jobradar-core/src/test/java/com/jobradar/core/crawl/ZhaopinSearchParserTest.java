package com.jobradar.core.crawl;

import com.jobradar.core.domain.CrawlSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 智联搜索解析器：字段映射基于 2026-09-10 真实响应样例
 * （POST fe-api.zhaopin.com/c/i/search/positions 实测）。
 */
class ZhaopinSearchParserTest {

    private final ZhaopinSearchParser parser = new ZhaopinSearchParser();

    /** 真实样例缩编：正常条目（含大小写两种 URL 字段）、面议条目、缺字段的广告卡 */
    private static final String SAMPLE = """
            {"code":200,"apiCode":200,"data":{"count":100,"isEndPage":0,"list":[
              {"name":"Java开发工程师（2026届校招）","companyName":"某国有银行科技子公司",
               "workCity":"北京","education":"本科","salary60":"1.5-2.5万","salaryReal":"15000-25000",
               "publishTime":"2026-09-08 01:46:26","property":"国企",
               "positionURL":"http://jobs.zhaopin.com/CCL1514334500J40825123514.htm",
               "jobSummary":"负责核心系统后端开发，参与分布式架构设计"},
              {"name":"客户经理","companyName":"某股份制企业",
               "workCity":"成都","salary60":"面议",
               "publishTime":"2026-09-07 10:00:00",
               "positionUrl":"http://jobs.zhaopin.com/CC880987410J40449813411.htm"},
              {"adResponse":{"adId":1},"liveCard":{}}
            ]}}
            """;

    private static CrawlSource source() {
        CrawlSource s = new CrawlSource();
        s.setName("智联·测试");
        s.setUrl("https://fe-api.zhaopin.com/c/i/search/positions");
        s.setParser("zhaopin-search");
        return s;
    }

    @Test
    void parsesFieldsAndSkipsAdCards() {
        List<RawJobPosting> out = parser.parse(source(), SAMPLE);

        assertThat(out).hasSize(2); // 无 name/companyName 的广告卡被跳过

        RawJobPosting first = out.get(0);
        assertThat(first.company()).isEqualTo("某国有银行科技子公司");
        assertThat(first.title()).isEqualTo("Java开发工程师（2026届校招）");
        assertThat(first.city()).isEqualTo("北京");
        assertThat(first.salaryRange()).isEqualTo("1.5-2.5万");
        assertThat(first.publishDate()).isEqualTo("2026-09-08");
        assertThat(first.deadline()).isNull(); // 列表接口无截止时间
        assertThat(first.url()).isEqualTo("http://jobs.zhaopin.com/CCL1514334500J40825123514.htm");
        assertThat(first.jdText()).contains("分布式架构");

        // 第二条：小写 positionUrl 兜底 + 面议薪资 → null
        RawJobPosting second = out.get(1);
        assertThat(second.url()).isEqualTo("http://jobs.zhaopin.com/CC880987410J40449813411.htm");
        assertThat(second.salaryRange()).isNull();
    }

    @Test
    void throwsOnErrorResponse() {
        assertThatThrownBy(() -> parser.parse(source(), "{\"code\":500,\"msg\":\"freq limit\"}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("data.list");
    }
}
