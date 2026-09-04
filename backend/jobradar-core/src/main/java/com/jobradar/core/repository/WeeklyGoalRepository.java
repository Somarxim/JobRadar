package com.jobradar.core.repository;

import com.jobradar.core.domain.WeeklyGoal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;

public interface WeeklyGoalRepository extends JpaRepository<WeeklyGoal, LocalDate> {
}
