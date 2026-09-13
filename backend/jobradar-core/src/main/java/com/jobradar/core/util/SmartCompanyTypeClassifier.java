package com.jobradar.core.util;

import com.jobradar.core.domain.CompanyType;
import com.jobradar.core.llm.LlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 企业性质智能分类器：规则优先 + LLM fallback + 内存缓存。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>规则优先</b>：低成本、低延迟、可解释； ordered dictionary 覆盖头部公司。
 *     规则命中直接返回，不走 LLM。</li>
 *   <li><b>LLM 只做 OTHER 的 fallback</b>：避免对已知头部公司浪费 token；
 *     本地部署场景下每日新公司名数量有限，LLM 调用次数可控。</li>
 *   <li><b>内存缓存</b>：ConcurrentHashMap 缓存已分类结果，同一公司名（含不同岗位来源）
 *     只调一次 LLM。单用户本地应用，内存无限增长风险极低。</li>
 *   <li><b>降级</b>：LLM 未配置/超预算/网络异常时保持 OTHER，不阻断爬虫入库。</li>
 * </ul>
 */
@Component
public class SmartCompanyTypeClassifier {

    private static final Logger log = LoggerFactory.getLogger(SmartCompanyTypeClassifier.class);

    private final LlmService llmService;
    /** 公司名 → 类型的内存缓存（大小有限，本地单用户场景安全） */
    private final Map<String, CompanyType> cache = new ConcurrentHashMap<>();

    public SmartCompanyTypeClassifier(LlmService llmService) {
        this.llmService = llmService;
    }

    /**
     * 分类公司名称：规则 → LLM fallback → 缓存。
     *
     * @param companyName 公司名（已归一化）
     * @return 企业性质枚举，不会返回 null
     */
    public CompanyType classify(String companyName) {
        // 1. 规则分类器（低成本、确定性）
        CompanyType rule = CompanyTypeClassifier.classify(companyName);
        if (rule != CompanyType.OTHER) {
            return rule;
        }

        // 2. 查缓存（避免重复 LLM 调用）
        CompanyType cached = cache.get(companyName);
        if (cached != null) {
            return cached;
        }

        // 3. LLM fallback（轻量调用，失败降级为 OTHER）
        CompanyType llm = llmService.classifyCompanyType(companyName)
                .orElse(CompanyType.OTHER);
        cache.put(companyName, llm);
        if (llm != CompanyType.OTHER) {
            log.info("LLM 补充分类：{} → {}", companyName, llm.toJson());
        }
        return llm;
    }
}
