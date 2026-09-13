package com.jobradar.app.web;

import com.jobradar.core.domain.Company;
import com.jobradar.core.domain.CompanyTier;
import com.jobradar.core.domain.CompanyType;
import com.jobradar.core.exception.BadRequestException;
import com.jobradar.core.service.CompanyService;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 公司 API（/api/companies）：tier（投递分级）与 companyType（企业性质）都挂在公司实体，
 * 岗位编辑管不到它们，所以编辑入口独立在此。
 * PATCH 语义与全局一致：字段不传/为 null = 保持原值。
 */
@RestController
@RequestMapping("/api/companies")
public class CompanyController {

    private final CompanyService companyService;

    public CompanyController(CompanyService companyService) {
        this.companyService = companyService;
    }

    /** 两个字段都可空（null=保持原值），但至少要给一个——空 PATCH 视为客户端笔误 */
    public record CompanyPatchRequest(CompanyTier tier, CompanyType companyType) {
    }

    @PatchMapping("/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody CompanyPatchRequest req) {
        if (req.tier() == null && req.companyType() == null) {
            throw new BadRequestException("tier 与 companyType 至少给一个");
        }
        Company company = companyService.update(id, req.tier(), req.companyType());
        Map<String, Object> body = new HashMap<>();
        body.put("tier", company.getTier());
        body.put("company_type", company.getCompanyType());
        return body;
    }

    /**
     * 存量 OTHER 公司重分类（规则更新/LLM 上线前的历史数据修复口）。
     * 同步接口：LLM fallback 每家公司一次调用，几十家约 1-2 分钟，调用方需耐心等。
     */
    @PostMapping("/backfill-types")
    public CompanyService.BackfillReport backfillTypes() {
        return companyService.backfillTypes();
    }
}
