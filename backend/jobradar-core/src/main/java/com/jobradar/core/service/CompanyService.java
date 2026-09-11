package com.jobradar.core.service;

import com.jobradar.core.domain.Company;
import com.jobradar.core.domain.CompanyTier;
import com.jobradar.core.domain.CompanyType;
import com.jobradar.core.exception.NotFoundException;
import com.jobradar.core.repository.CompanyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 公司服务。承载公司级属性的更新：tier（投递分级）与 companyType（企业性质）。
 * 两者都挂在 Company 而非 Job 上（同公司多岗位共享：海康威视是"主攻"，它所有岗位都是主攻），
 * 所以编辑入口独立于岗位编辑。
 */
@Service
public class CompanyService {

    private final CompanyRepository companyRepository;

    public CompanyService(CompanyRepository companyRepository) {
        this.companyRepository = companyRepository;
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
}
