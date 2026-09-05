package com.jobradar.core.llm;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * 匹配报告详情（MatchDetail，schema 见 docs/agent-design.md §3.3）。
 * 落库为 match_reports.detail（JSONB）；score_total 同时冗余为表列（SQL 筛选/排序用）。
 *
 * <p>字段顺序即 CoT 顺序（设计文档明确要求）：hardChecks 硬性条件先逐条核对，
 * 再给出 hardPass 与打分——让模型「先论证后结论」，显著减少「忽略硬门槛给高分」
 * 的失败模式。调整字段顺序前请重读 agent-design.md §3.3。
 */
public record MatchDetail(
        /** 硬性条件逐条核对：学历/专业/应届身份/政治面貌/保密要求等（军工所特化） */
        @JsonProperty("hard_checks") List<HardCheck> hardChecks,
        /** 硬性条件整体是否通过；false 时 score_total 上限 39（服务端也会强制封顶） */
        @JsonProperty("hard_pass") Boolean hardPass,
        /** 总分 0-100。锚点：≥85 强匹配必投 / 70-84 推荐 / 55-69 可投 / <55 不推荐 */
        @JsonProperty("score_total") Integer scoreTotal,
        /** 维度分：{"skill":0-40, "experience":0-40, "fit":0-20} */
        @JsonProperty("score_breakdown") Map<String, Integer> scoreBreakdown,
        @JsonProperty("matched_skills") List<String> matchedSkills,
        @JsonProperty("missing_skills") List<String> missingSkills,
        /** 简历中最契合的 2-3 个经历要点 */
        List<String> highlights,
        /** 投递建议：是否投 + 简历侧重点 + 注意事项 */
        String suggestion,
        /** 一句话结论（列表展示用） */
        @JsonProperty("one_liner") String oneLiner) {

    /** 硬性条件核对项：item=条件名，resumeValue=简历对应情况，passed=是否满足，note=说明 */
    public record HardCheck(String item,
                            @JsonProperty("resume_value") String resumeValue,
                            Boolean passed,
                            String note) {
    }
}
