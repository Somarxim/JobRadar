package com.jobradar.core.crawl;

import com.jobradar.core.domain.CrawlSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 24365 平台搜索解析器：字段映射基于 2026-09-15 真实响应样例
 * （GET job.ncss.cn/student/jobs/jobslist/ajax/ 匿名实测）。
 */
class NcssSearchParserTest {

    private final NcssSearchParser parser = new NcssSearchParser();

    /** 真实样例缩编：正常条目、面议薪资条目、缺公司名条目 */
    private static final String SAMPLE = """
            {"flag":true,"data":{"list":[
              {"jobName":"飞行器设计工程师","recName":"航天科技集团某院","areaCodeName":"北京市",
               "degreeName":"硕士","major":"航空航天类","headCount":3,"recProperty":"国有企业",
               "recScale":"10000人以上","sourcesNameCh":"北京航空航天大学",
               "lowMonthPay":12.0,"highMonthPay":18.0,"publishDate":1789441200000,"jobId":"12345"},
              {"jobName":"软件开发工程师","recName":"中国电科某所","areaCodeName":"成都市",
               "degreeName":"本科","major":"计算机类","headCount":5,"recProperty":"国有企业",
               "lowMonthPay":0.0,"highMonthPay":0.0,"publishDate":0,"jobId":"12346"},
              {"jobName":"无公司名条目","recName":null,"jobId":"12347"}
            ],"pagenation":{"count":100,"total":10,"limit":10,"offset":1}}}
            """;

    private static CrawlSource source() {
        CrawlSource s = new CrawlSource();
        s.setName("24365·测试");
        s.setUrl("https://job.ncss.cn/student/jobs/jobslist/ajax/");
        s.setParser("ncss-search");
        return s;
    }

    @Test
    void parsesFieldsAndSkipsIncomplete() {
        List<RawJobPosting> out = parser.parse(source(), SAMPLE);

        assertThat(out).hasSize(2); // 缺公司名的条目被跳过

        RawJobPosting first = out.get(0);
        assertThat(first.company()).isEqualTo("航天科技集团某院");
        assertThat(first.title()).isEqualTo("飞行器设计工程师");
        assertThat(first.city()).isEqualTo("北京市");
        assertThat(first.salaryRange()).isEqualTo("12-18K/月");
        assertThat(first.url()).isEqualTo("https://job.ncss.cn/student/jobs/detail.html?jobId=12345");
        assertThat(first.publishDate()).isNotBlank();
        assertThat(first.deadline()).isNull(); // 列表接口无 DDL
        assertThat(first.jdText()).contains("学历要求：硕士").contains("单位性质：国有企业");

        RawJobPosting second = out.get(1);
        assertThat(second.salaryRange()).isNull(); // 面议（0/0）→ 置空
        assertThat(second.publishDate()).isNull(); // publishDate=0 → 置空
    }
}
