package com.jobradar.core.service;

import com.jobradar.core.domain.Application;
import com.jobradar.core.domain.ApplicationEvent;
import com.jobradar.core.domain.ApplicationStage;
import com.jobradar.core.domain.Company;
import com.jobradar.core.domain.Job;
import com.jobradar.core.domain.RecommendationStatus;
import com.jobradar.core.domain.WeeklyGoal;
import com.jobradar.core.dto.DashboardDtos.WeeklyReportView;
import com.jobradar.core.exception.BadRequestException;
import com.jobradar.core.llm.LlmService;
import com.jobradar.core.repository.ApplicationEventRepository;
import com.jobradar.core.repository.JobRepository;
import com.jobradar.core.repository.RecommendationRepository;
import com.jobradar.core.repository.WeeklyGoalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 周报 Agent 的单元测试（W4-2）：仓储全部 mock，专注验证
 * 统计口径、weekOffset 校验与 LLM 叙事的两级降级（不调/失败）。
 */
class WeeklyReportServiceTest {

    private final ApplicationEventRepository eventRepository = mock(ApplicationEventRepository.class);
    private final JobRepository jobRepository = mock(JobRepository.class);
    private final RecommendationRepository recommendationRepository = mock(RecommendationRepository.class);
    private final WeeklyGoalRepository weeklyGoalRepository = mock(WeeklyGoalRepository.class);
    private final LlmService llmService = mock(LlmService.class);

    private final WeeklyReportService service = new WeeklyReportService(
            eventRepository, jobRepository, recommendationRepository, weeklyGoalRepository, llmService);

    @BeforeEach
    void stubEmptyData() {
        lenient().when(eventRepository
                        .findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(any(), any()))
                .thenReturn(List.of());
        lenient().when(eventRepository
                        .countByToStageAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(any(), any(), any()))
                .thenReturn(0L);
        lenient().when(jobRepository.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(any(), any()))
                .thenReturn(0L);
        lenient().when(recommendationRepository.countByRecDateBetween(any(), any())).thenReturn(0L);
        lenient().when(recommendationRepository.countByRecDateBetweenAndStatus(any(), any(), any()))
                .thenReturn(0L);
        lenient().when(weeklyGoalRepository.findById(any())).thenReturn(Optional.empty());
    }

    @Test
    void rejectsFutureWeekOffset() {
        assertThatThrownBy(() -> service.report(1, false))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("week_offset");
    }

    @Test
    void assemblesStatsWithoutTouchingLlm() {
        when(eventRepository
                .findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(any(), any()))
                .thenReturn(List.of(
                        event("航天科技", ApplicationStage.PLANNED),
                        event("航天科技", ApplicationStage.APPLIED),
                        event("中国银行", ApplicationStage.APPLIED),
                        event("中国银行", ApplicationStage.INTERVIEW)));
        when(eventRepository.countByToStageAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                eq(ApplicationStage.APPLIED), any(), any())).thenReturn(1L);
        when(jobRepository.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(any(), any())).thenReturn(7L);
        when(recommendationRepository.countByRecDateBetween(any(), any())).thenReturn(5L);
        when(recommendationRepository.countByRecDateBetweenAndStatus(any(), any(),
                eq(RecommendationStatus.ACCEPTED))).thenReturn(2L);
        WeeklyGoal goal = new WeeklyGoal();
        goal.setTargetCount(8);
        when(weeklyGoalRepository.findById(any())).thenReturn(Optional.of(goal));

        WeeklyReportView r = service.report(0, false);

        assertThat(r.stats().applied()).isEqualTo(2);
        assertThat(r.stats().goal()).isEqualTo(8);
        assertThat(r.stats().prevWeekApplied()).isEqualTo(1);
        assertThat(r.stats().newJobs()).isEqualTo(7);
        assertThat(r.stats().recGenerated()).isEqualTo(5);
        assertThat(r.stats().recAccepted()).isEqualTo(2);
        assertThat(r.stats().stageInflow())
                .containsEntry("applied", 2L)
                .containsEntry("interview", 1L);
        assertThat(r.events()).hasSize(4);
        // narrative=false 时完全不碰 LLM（省钱：数据版周报零成本）
        assertThat(r.llmGenerated()).isFalse();
        assertThat(r.narrativeMarkdown()).isNull();
        verifyNoInteractions(llmService);
    }

    @Test
    void weekOffsetMinusOneCoversPreviousWeek() {
        LocalDate monday = LocalDate.now().with(java.time.DayOfWeek.MONDAY);
        WeeklyReportView r = service.report(-1, false);
        assertThat(r.weekStart()).isEqualTo(monday.minusWeeks(1));
        assertThat(r.weekEnd()).isEqualTo(monday.minusDays(1));
    }

    @Test
    void narrativeFallsBackWhenLlmUnavailable() {
        when(llmService.narrateWeeklyReport(any())).thenReturn(Optional.empty());

        WeeklyReportView r = service.report(0, true);

        assertThat(r.llmGenerated()).isFalse();
        assertThat(r.narrativeMarkdown()).isNull();
    }

    @Test
    void narrativeAttachedWhenLlmResponds() {
        when(llmService.narrateWeeklyReport(any())).thenReturn(Optional.of("## 本周概览\n投了 0 个……"));

        WeeklyReportView r = service.report(0, true);

        assertThat(r.llmGenerated()).isTrue();
        assertThat(r.narrativeMarkdown()).contains("本周概览");
    }

    private static ApplicationEvent event(String companyName, ApplicationStage toStage) {
        Company c = new Company();
        c.setName(companyName);
        Job j = new Job();
        j.setCompany(c);
        j.setTitle("软件工程师");
        Application a = new Application();
        a.setJob(j);
        ApplicationEvent e = new ApplicationEvent();
        e.setApplication(a);
        e.setToStage(toStage);
        e.setCreatedAt(Instant.now());
        return e;
    }
}
