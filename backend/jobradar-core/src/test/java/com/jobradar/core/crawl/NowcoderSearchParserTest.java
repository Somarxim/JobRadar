package com.jobradar.core.crawl;

import com.jobradar.core.domain.CrawlSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 牛客搜索解析器：字段映射基于 2026-09-09 真实响应样例
 * （POST nowpick.nowcoder.com/u/job/square-search 实测）。
 */
class NowcoderSearchParserTest {

    private final NowcoderSearchParser parser = new NowcoderSearchParser();

    /** 真实样例的缩编：正常条目（编号后缀+批次前缀）、面议条目、长期窗口、广告卡片 */
    private static final String SAMPLE = """
            {"code":0,"msg":"OK","data":{"totalCount":200,"totalPage":10,"currentPage":1,"datas":[
              {"data":{"id":459414,"recruitType":1,"jobName":"【2027秋招】交付工程师(J10357)",
                "ext":"{\\"infos\\":\\"负责金融行业应用系统实施\\",\\"requirements\\":\\"本科及以上学历，计算机相关专业\\",\\"jobStrength\\":\\"\\"}",
                "jobCity":"武汉","deliverBegin":1786464000000,"deliverEnd":1789084800000,
                "refreshTime":1788931336000,"salaryMin":10,"salaryMax":20,"salaryMonth":14,
                "recommendInternCompany":{"companyName":"恒生电子股份有限公司"}},"rc_type":3010},
              {"data":{"id":411304,"jobName":"AI软件工程师",
                "ext":"{\\"infos\\":\\"参与大模型应用开发\\",\\"requirements\\":\\"熟悉 Java/Python\\"}",
                "jobCity":"西安,上海,成都","deliverBegin":1786464000000,"deliverEnd":1881193690000,
                "refreshTime":1788931336000,"salaryMin":0,"salaryMax":9999999,"salaryMonth":0,
                "recommendInternCompany":{"companyName":"华为软件技术有限公司"}},"rc_type":3010},
              {"data":{"id":999,"adTop":true,"adLabel":"推广"},"rc_type":9999}
            ]}}
            """;

    private static CrawlSource source() {
        CrawlSource s = new CrawlSource();
        s.setName("牛客·测试");
        s.setUrl("https://nowpick.nowcoder.com/u/job/square-search");
        s.setParser("nowcoder-search");
        return s;
    }

    @Test
    void parsesFieldsAndSkipsAdCards() {
        List<RawJobPosting> out = parser.parse(source(), SAMPLE);

        assertThat(out).hasSize(2); // 广告卡片（无 jobName）被跳过

        RawJobPosting first = out.get(0);
        assertThat(first.company()).isEqualTo("恒生电子股份有限公司");
        // 标题清洗：【批次】前缀 + (J10357) 内部编号后缀剥离
        assertThat(first.title()).isEqualTo("交付工程师");
        assertThat(first.city()).isEqualTo("武汉");
        assertThat(first.salaryRange()).isEqualTo("10-20K·14薪");
        assertThat(first.url()).isEqualTo("https://www.nowcoder.com/jobs/459414");
        assertThat(first.publishDate()).isNotBlank();
        // deliverEnd 1789084800000 ≈ 2026-09-11，30 天窗口 → 落 deadline
        assertThat(first.deadline()).isEqualTo("2026-09-11");
        // JD 正文从 ext 内嵌 JSON 拼装
        assertThat(first.jdText()).contains("【职位描述】", "负责金融行业应用系统实施",
                "【任职要求】", "本科及以上学历");

        RawJobPosting second = out.get(1);
        assertThat(second.title()).isEqualTo("AI软件工程师");
        // 面议哨兵（0-9999999）→ 不落薪资
        assertThat(second.salaryRange()).isNull();
        // 投递窗口 >400 天（至 2029）→ 视为长期岗，不落 deadline（防 DDL 列表被假截止日淹没）
        assertThat(second.deadline()).isNull();
    }

    @Test
    void titleCleaningKeepsSemanticParens() {
        assertThat(NowcoderSearchParser.cleanTitle("【27届校招】软件工程师")).isEqualTo("软件工程师");
        assertThat(NowcoderSearchParser.cleanTitle("大模型算法工程师(A231667)")).isEqualTo("大模型算法工程师");
        // 非编号括号（语义修饰）保留
        assertThat(NowcoderSearchParser.cleanTitle("产品经理（校招）")).isEqualTo("产品经理（校招）");
    }

    @Test
    void malformedResponseFailsFast() {
        // 被限流/改版时响应形态变化（无 data.datas）→ 抛错交给管线隔离，而不是静默吞掉
        assertThatThrownBy(() -> parser.parse(source(), "{\"code\":-1,\"msg\":\"频率超限\"}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("data.datas");
        assertThatThrownBy(() -> parser.parse(source(), "<html>403</html>"))
                .isInstanceOf(IllegalStateException.class);
    }
}
