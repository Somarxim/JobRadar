package com.jobradar.core.repository;

import com.jobradar.core.domain.Resume;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ResumeRepository extends JpaRepository<Resume, Long> {

    Optional<Resume> findByIsDefaultTrue();

    /** 列表只展示未归档简历（秋招版本迭代快，软删避免误删有匹配历史的数据） */
    List<Resume> findByArchivedFalse();
}
