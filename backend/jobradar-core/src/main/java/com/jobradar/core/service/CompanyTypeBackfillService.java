package com.jobradar.core.service;

import com.jobradar.core.domain.Company;
import com.jobradar.core.domain.CompanyType;
import com.jobradar.core.repository.CompanyRepository;
import com.jobradar.core.util.CompanyTypeClassifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 企业性质存量回填：自动分类上线前入库的公司全是默认 OTHER，
 * 启动时由 app 模块的 CompanyTypeBackfillRunner 驱动本服务重判。
 *
 * <p>只动仍为 OTHER 的公司——用户通过 PATCH 手动纠正过的非 OTHER 值不会被覆盖。
 * 公司量级在数百、规则匹配是纯 CPU 微秒级，单事务一次跑完，无需分批。
 */
@Service
public class CompanyTypeBackfillService {

    private final CompanyRepository companyRepository;

    public CompanyTypeBackfillService(CompanyRepository companyRepository) {
        this.companyRepository = companyRepository;
    }

    /** 重判一批 OTHER 公司；返回 [检查数, 改判数]，检查数 0 表示无存量 */
    @Transactional
    public int[] reclassifyOthers() {
        List<Company> others = companyRepository.findByCompanyType(CompanyType.OTHER);
        int changed = 0;
        for (Company c : others) {
            CompanyType classified = CompanyTypeClassifier.classify(c.getName());
            if (classified != CompanyType.OTHER) {
                c.setCompanyType(classified); // 托管实体脏检查自动落库
                changed++;
            }
        }
        return new int[]{others.size(), changed};
    }
}
