package com.jobradar.core.service;

import com.jobradar.core.domain.Company;
import com.jobradar.core.domain.CompanyTier;
import com.jobradar.core.domain.CompanyType;
import com.jobradar.core.exception.NotFoundException;
import com.jobradar.core.repository.CompanyRepository;
import com.jobradar.core.util.SmartCompanyTypeClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 公司服务。承载公司级属性的更新：tier（投递分级）与 companyType（企业性质）。
 * 两者都挂在 Company 而非 Job 上（同公司多岗位共享：海康威视是"主攻"，它所有岗位都是主攻），
 * 所以编辑入口独立于岗位编辑。
 */
@Service
public class CompanyService {

    private static final Logger log = LoggerFactory.getLogger(CompanyService.class);

    private final CompanyRepository companyRepository;
    private final SmartCompanyTypeClassifier typeClassifier;

    public CompanyService(CompanyRepository companyRepository, SmartCompanyTypeClassifier typeClassifier) {
        this.companyRepository = companyRepository;
        this.typeClassifier = typeClassifier;
    }

    /**
     * 部分更新公司属性（PATCH /companies/{id}，null=保持原值）。
     * tier：投递规划三层 dream=冲刺 / target=主攻 / backup=保底；
     * companyType：规则分类器误判时的手动纠正口（回填只动 OTHER，不会覆盖纠正结果）。
     */
    @Transactional
    public Company update(long companyId, CompanyTier tier, CompanyType companyType) {
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new NotFoundException("公司不存在: id=" + companyId));
        if (tier != null) {
            company.setTier(tier);
        }
        if (companyType != null) {
            company.setCompanyType(companyType);
        }
        return company; // 托管实体脏检查自动落库
    }

    /** 回填报告：检查数 / 变更数 / 逐条变更明细（公司名 → 新类型） */
    public record BackfillReport(int checked, int changed, List<String> changes) {
    }

    /**
     * 存量 OTHER 公司重分类（POST /api/companies/backfill-types）。
     *
     * <p>用途：LLM fallback 上线前已入库的公司永远是 OTHER；规则词典扩充后同理。
     * 本方法对所有仍为 OTHER 的公司重跑「规则 → LLM」分类，只更新有结论的，
     * 手动纠正过的非 OTHER 公司不受影响（查询条件已过滤）。
     *
     * <p>事务边界：刻意不加 @Transactional——LLM 调用是秒级网络 IO，
     * 不能让它占着数据库连接；逐条 save（Repository 自带事务，
     * 与 CrawlerService 同模式），一条失败不影响其余。
     */
    public BackfillReport backfillTypes() {
        List<Company> others = companyRepository.findByCompanyType(CompanyType.OTHER);
        List<String> changes = new ArrayList<>();
        for (Company c : others) {
            CompanyType t = typeClassifier.classify(c.getName());
            if (t != CompanyType.OTHER) {
                c.setCompanyType(t);
                companyRepository.save(c);
                changes.add(c.getName() + " → " + t.toJson());
            }
        }
        log.info("公司类型回填完成：检查 {} 家，改判 {} 家", others.size(), changes.size());
        return new BackfillReport(others.size(), changes.size(), changes);
    }
}
