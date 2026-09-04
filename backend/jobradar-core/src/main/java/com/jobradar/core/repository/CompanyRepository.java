package com.jobradar.core.repository;

import com.jobradar.core.domain.Company;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CompanyRepository extends JpaRepository<Company, Long> {

    /** 录入/爬虫归一化公司名后按名查重，存在则复用（companies.name 有唯一约束） */
    Optional<Company> findByName(String name);
}
