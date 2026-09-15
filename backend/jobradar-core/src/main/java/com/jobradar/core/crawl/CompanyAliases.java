package com.jobradar.core.crawl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 公司别名归一化（company-aliases.yml）：把「航空工业631所」「AVIC计算所」
 * 这类别名统一为标准名，让 DedupeHash 跨源命中同一岗位。
 *
 * <p>数据流：爬虫抓到别名公司名 → normalize() → 标准名 → companies 表按名复用。
 * 表随爬虫运行持续补充（docs/target-sources.md §6），yml 而非 DB 的理由：
 * 改动走 git 评审、启动即生效、个人项目无需运营后台。
 */
@Component
public class CompanyAliases {

    private static final Logger log = LoggerFactory.getLogger(CompanyAliases.class);

    /** 归一化键（去空白小写）→ 标准名 */
    private final Map<String, String> aliasToCanonical = new HashMap<>();

    public CompanyAliases() {
        try (InputStream in = new ClassPathResource("company-aliases.yml").getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            Object aliases = root == null ? null : root.get("aliases");
            if (aliases instanceof Map<?, ?> map) {
                map.forEach((canonical, aliasList) -> {
                    register(String.valueOf(canonical), String.valueOf(canonical)); // 标准名自身也是键（幂等）
                    if (aliasList instanceof List<?> list) {
                        list.forEach(alias -> register(String.valueOf(alias), String.valueOf(canonical)));
                    }
                });
            }
            log.info("公司别名表加载完成：{} 条映射", aliasToCanonical.size());
        } catch (Exception e) {
            // 别名表缺失不致命：退化为"原文即标准名"，仅损失跨源去重精度
            log.warn("company-aliases.yml 加载失败，别名归一化停用: {}", e.getMessage());
        }
    }

    private void register(String alias, String canonical) {
        aliasToCanonical.put(key(alias), canonical.trim());
    }

    /** 别名 → 标准名；无映射时返回去空白后的原名 */
    public String normalize(String companyName) {
        if (companyName == null) {
            return null;
        }
        return aliasToCanonical.getOrDefault(key(companyName), companyName.trim());
    }

    /**
     * 在自由文本（如公众号文章标题）里找已知公司：返回命中别名对应的标准名，无命中返回 null。
     * 供「搜索发现」类解析器从非结构化标题里识别主体公司（如「航天科工二院2027届校园招聘」）。
     * 别名按键长降序匹配，优先命中更具体的名字（「航天科工二院」优于「航天科工」）。
     */
    public String findIn(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String haystack = key(text);
        return aliasToCanonical.entrySet().stream()
                .filter(e -> haystack.contains(e.getKey()))
                .max(java.util.Comparator.comparingInt(e -> e.getKey().length()))
                .map(Map.Entry::getValue)
                .orElse(null);
    }

    private static String key(String name) {
        return name.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
    }
}
