package com.jobradar.core.repository;

import com.jobradar.core.domain.Company;
import com.jobradar.core.domain.CompanyType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CompanyRepository extends JpaRepository<Company, Long> {

    /** 录入/爬虫归一化公司名后按名查重，存在则复用（companies.name 有唯一约束） */
    Optional<Company> findByName(String name);

    /** 企业性质回填：找出仍为 OTHER 的存量公司，交给分类器重判 */
    List<Company> findByCompanyType(CompanyType companyType);
}
