package com.jobradar.core.util;

import com.jobradar.core.domain.CompanyType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CompanyTypeClassifier} 契约测试。用例尽量取真实入库的公司名，
 * 重点覆盖优先级规则：「中国X」名字必须先命中运营商/银行/研究所，而不是央企前缀兜底。
 */
class CompanyTypeClassifierTest {

    @Test
    void operatorBeforeCentralPrefix() {
        assertThat(CompanyTypeClassifier.classify("中国移动")).isEqualTo(CompanyType.OPERATOR);
        assertThat(CompanyTypeClassifier.classify("中国电信广东分公司")).isEqualTo(CompanyType.OPERATOR);
        assertThat(CompanyTypeClassifier.classify("中国电信-天翼云")).isEqualTo(CompanyType.OPERATOR);
        assertThat(CompanyTypeClassifier.classify("中国联通")).isEqualTo(CompanyType.OPERATOR);
    }

    @Test
    void bankBeforeCentralPrefix() {
        assertThat(CompanyTypeClassifier.classify("南京银行")).isEqualTo(CompanyType.BANK);
        assertThat(CompanyTypeClassifier.classify("中国工商银行软件开发中心")).isEqualTo(CompanyType.BANK);
        assertThat(CompanyTypeClassifier.classify("厦门银行股份有限公司")).isEqualTo(CompanyType.BANK);
        assertThat(CompanyTypeClassifier.classify("平安银行金融科技部")).isEqualTo(CompanyType.BANK);
    }

    @Test
    void instituteBeforeCentralPrefix() {
        assertThat(CompanyTypeClassifier.classify("中国电科XX研究所")).isEqualTo(CompanyType.INSTITUTE);
        assertThat(CompanyTypeClassifier.classify("中国航天科技集团第五研究院")).isEqualTo(CompanyType.INSTITUTE);
        assertThat(CompanyTypeClassifier.classify("航空工业某所")).isEqualTo(CompanyType.INSTITUTE);
    }

    @Test
    void internetAndForeignKnownNames() {
        assertThat(CompanyTypeClassifier.classify("华为软件技术有限公司")).isEqualTo(CompanyType.INTERNET);
        assertThat(CompanyTypeClassifier.classify("华为HUAWEI")).isEqualTo(CompanyType.INTERNET);
        assertThat(CompanyTypeClassifier.classify("阿里巴巴集团")).isEqualTo(CompanyType.INTERNET);
        assertThat(CompanyTypeClassifier.classify("北京京东世纪贸易有限公司")).isEqualTo(CompanyType.INTERNET);
        assertThat(CompanyTypeClassifier.classify("去哪儿旅行")).isEqualTo(CompanyType.INTERNET);
        assertThat(CompanyTypeClassifier.classify("Qualcomm高通")).isEqualTo(CompanyType.FOREIGN);
        assertThat(CompanyTypeClassifier.classify("英特尔")).isEqualTo(CompanyType.FOREIGN);
        assertThat(CompanyTypeClassifier.classify("爱立信（中国）通信有限公司")).isEqualTo(CompanyType.FOREIGN);
        assertThat(CompanyTypeClassifier.classify("深圳虾皮信息科技有限公司")).isEqualTo(CompanyType.FOREIGN);
    }

    @Test
    void centralSoeByKnownNameOrPrefix() {
        assertThat(CompanyTypeClassifier.classify("国家电网")).isEqualTo(CompanyType.SOE_CENTRAL);
        assertThat(CompanyTypeClassifier.classify("中国电子云")).isEqualTo(CompanyType.SOE_CENTRAL);
    }

    @Test
    void unknownFallsBackToOther() {
        assertThat(CompanyTypeClassifier.classify("北京柠檬微趣科技股份有限公司")).isEqualTo(CompanyType.OTHER);
        assertThat(CompanyTypeClassifier.classify("Dexmal 原力灵机")).isEqualTo(CompanyType.OTHER);
        assertThat(CompanyTypeClassifier.classify("")).isEqualTo(CompanyType.OTHER);
        assertThat(CompanyTypeClassifier.classify(null)).isEqualTo(CompanyType.OTHER);
    }
}
