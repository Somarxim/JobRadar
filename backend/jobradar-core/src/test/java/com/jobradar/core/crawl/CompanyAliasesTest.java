package com.jobradar.core.crawl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 别名归一化：别名命中标准名；大小写/空白不敏感；未知名原样返回（trim） */
class CompanyAliasesTest {

    private final CompanyAliases aliases = new CompanyAliases();

    @Test
    void aliasMapsToCanonical() {
        assertThat(aliases.normalize("航空工业631所")).isEqualTo("中国航空工业计算技术研究所");
        assertThat(aliases.normalize("AVIC计算所")).isEqualTo("中国航空工业计算技术研究所");
        assertThat(aliases.normalize("建信金科")).isEqualTo("建信金融科技有限责任公司");
    }

    @Test
    void normalizationIgnoresWhitespaceAndCase() {
        assertThat(aliases.normalize(" 航空工业 631所 ")).isEqualTo("中国航空工业计算技术研究所");
        assertThat(aliases.normalize("avic计算所")).isEqualTo("中国航空工业计算技术研究所");
    }

    @Test
    void canonicalNameMapsToItself() {
        assertThat(aliases.normalize("中国空间技术研究院")).isEqualTo("中国空间技术研究院");
    }

    @Test
    void unknownNamePassesThrough() {
        assertThat(aliases.normalize("  某不知名公司 ")).isEqualTo("某不知名公司");
        assertThat(aliases.normalize(null)).isNull();
    }
}
