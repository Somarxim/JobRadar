package com.jobradar.core.service;

import com.jobradar.core.domain.Job;
import com.jobradar.core.llm.ParsedResume;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 推荐粗筛规则的单元测试（W3-3）。coarseScore 是纯函数，直接断言计分明细，
 * 不起 Spring 容器——规则回归应该跑得又快又稳。
 */
class RecommendServiceTest {

    private static Job job(String title, String jdText, String city,
                           String salary, LocalDate deadline, LocalDate publishDate) {
        Job j = new Job();
        j.setTitle(title);
        j.setJdText(jdText);
        j.setCity(city);
        j.setSalaryRange(salary);
        j.setDeadline(deadline);
        j.setPublishDate(publishDate);
        return j;
    }

    private static ParsedResume resumeWith(List<String> positions, List<String> cities) {
        return new ParsedResume("张三", null, null, null, positions, cities, null, null);
    }

    @Test
    void directionKeywordsHitAndCap() {
        // 标题命中 2 个方向词（Java、后端）= 30 分
        Job j = job("Java 后端工程师", "负责业务开发", null, null, null, null);
        var s = RecommendService.coarseScore(j, null);
        assertThat(s.score()).isEqualTo(30);
        assertThat(s.hits()).contains("Java", "后端");

        // 命中 4+ 个方向词也封顶 45（封顶防关键词堆砌刷分）
        Job stuffed = job("Java 后端开发", "大模型 AI AIGC 智能体 算法 Python", null, null, null, null);
        var capped = RecommendService.coarseScore(stuffed, null);
        assertThat(capped.score()).isEqualTo(45);
    }

    @Test
    void resumeTargetsAddBonus() {
        // 目标岗位命中 +20、目标城市命中 +15；叠加在方向词（后端=15）之上
        Job j = job("后端开发工程师", null, "西安", null, null, null);
        var s = RecommendService.coarseScore(j, resumeWith(List.of("后端开发"), List.of("西安")));
        assertThat(s.score()).isEqualTo(15 + 20 + 15);
    }

    @Test
    void freshnessAndInfoBonuses() {
        LocalDate today = LocalDate.now();
        // 无方向词，只靠信息质量分：薪资透明 5 + 截止日在未来 5 + 近 3 天发布 10 = 20
        Job j = job("储备干部", null, null, "10-15K", today.plusDays(30), today.minusDays(1));
        assertThat(RecommendService.coarseScore(j, null).score()).isEqualTo(20);

        // 截止日已过 / 发布超 3 天 → 对应加分不生效
        Job stale = job("储备干部", null, null, null, today.minusDays(1), today.minusDays(10));
        assertThat(RecommendService.coarseScore(stale, null).score()).isZero();
    }

    @Test
    void nullFieldsAreSafe() {
        // 抓取来的岗位字段常缺：粗筛必须对 null 健壮，不得 NPE
        Job j = job(null, null, null, null, null, null);
        var s = RecommendService.coarseScore(j, null);
        assertThat(s.score()).isZero();
        assertThat(s.hits()).isEmpty();
    }
}
