package com.jobradar.core.repository;

import com.jobradar.core.domain.Resume;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ResumeRepository extends JpaRepository<Resume, Long> {

    Optional<Resume> findByIsDefaultTrue();
}
