package com.jobradar.core.service;

import com.jobradar.core.domain.Company;
import com.jobradar.core.domain.CompanyTier;
import com.jobradar.core.exception.NotFoundException;
import com.jobradar.core.repository.CompanyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 公司服务。当前只承载公司分级（tier）更新——tier 挂在 Company 而非 Job 上
 * （同公司多岗位共享分级：海康威视是"主攻"，它所有岗位都是主攻），
 * 所以编辑入口独立于岗位编辑。
 */
@Service
public class CompanyService {

    private final CompanyRepository companyRepository;

    public CompanyService(CompanyRepository companyRepository) {
        this.companyRepository = companyRepository;
    }

    /** 更新公司分级（PATCH /companies/{id}）。投递规划三层：dream=冲刺 / target=主攻 / backup=保底 */
    @Transactional
    public CompanyTier updateTier(long companyId, CompanyTier tier) {
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new NotFoundException("公司不存在: id=" + companyId));
        company.setTier(tier);
        return company.getTier(); // 托管实体脏检查自动落库
    }
}
