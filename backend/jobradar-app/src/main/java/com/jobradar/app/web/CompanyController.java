package com.jobradar.app.web;

import com.jobradar.core.domain.CompanyTier;
import com.jobradar.core.service.CompanyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 公司 API（/api/companies）：目前只有分级更新（tier 挂在公司实体，岗位编辑管不到它） */
@RestController
@RequestMapping("/api/companies")
public class CompanyController {

    private final CompanyService companyService;

    public CompanyController(CompanyService companyService) {
        this.companyService = companyService;
    }

    public record TierPatchRequest(@NotNull(message = "tier 不能为空") CompanyTier tier) {
    }

    @PatchMapping("/{id}")
    public Map<String, CompanyTier> updateTier(@PathVariable long id,
                                               @Valid @RequestBody TierPatchRequest req) {
        return Map.of("tier", companyService.updateTier(id, req.tier()));
    }
}
