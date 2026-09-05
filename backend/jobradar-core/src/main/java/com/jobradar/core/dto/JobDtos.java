package com.jobradar.core.dto;

import com.jobradar.core.domain.CompanyTier;
import com.jobradar.core.domain.CompanyType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 岗位相关 DTO。契约见 docs/api-design.md §2.1/2.2。
 * 为什么 Service 返回 DTO 而非实体：本项目关闭了 OSIV，控制器层没有事务，
 * 返回实体后在事务外访问懒加载字段会抛 LazyInitializationException；
 * DTO 在 Service 事务边界内装配完毕，同时充当 API 契约与表结构之间的防腐层。
 */
public final class JobDtos {

    private JobDtos() {
    }

    /** 内嵌的公司摘要 */
    public record CompanyBrief(Long id, String name, CompanyType companyType, CompanyTier tier) {
    }

    /** 列表项（GET /jobs）。matchScore 为 W2 字段，W1 恒为 null */
    public record JobSummary(Long id, CompanyBrief company, String title, String city,
                             String salaryRange, String sourcePlatform, String sourceUrl,
                             LocalDate deadline, String jdSummary,
                             Integer matchScore, String applicationStage, Instant createdAt) {
    }

    /** 详情（GET /jobs/{id}）：含完整 JD、投递状态与最新匹配报告（无报告时 null） */
    public record JobDetail(Long id, CompanyBrief company, String title, String jdText,
                            String jdSummary, String requirements, String city, String salaryRange,
                            String sourcePlatform, String sourceUrl, LocalDate publishDate,
                            LocalDate deadline, boolean active,
                            ApplicationDtos.ApplicationCard application,
                            MatchDtos.MatchReportView latestMatchReport, Instant createdAt) {
    }

    /** 手动录入（POST /jobs）。公司按名查复用，不存在则新建 */
    public record JobCreateRequest(
            @NotBlank(message = "company_name 不能为空") String companyName,
            CompanyType companyType,
            @NotBlank(message = "title 不能为空") @Size(max = 500) String title,
            String jdText, String city, String salaryRange, String sourceUrl,
            LocalDate publishDate, LocalDate deadline) {
    }

    /** 修正岗位（PATCH /jobs/{id}）：null 字段不动（部分更新语义） */
    public record JobPatchRequest(String title, String jdText, String jdSummary, String city,
                                  String salaryRange, String sourceUrl,
                                  LocalDate publishDate, LocalDate deadline, Boolean active) {
    }

    /** 统一导入（POST /jobs/ingest）：插件/粘贴共用。
     *  hints 自 W2 起整体可选：company/title 缺省时由 LLM 提取（raw_text 走文本解析，
     *  image_base64 走海报多模态解析，二者至少其一），手填字段始终优先于 AI 结果 */
    public record IngestRequest(@NotBlank String source,
                                String url,
                                String rawText,
                                String pageHtml,
                                String imageBase64,
                                String imageMediaType,
                                Hints hints) {
        public record Hints(String company, String title,
                            String city, String salaryRange, LocalDate deadline) {
        }
    }

    public record IngestResponse(Long jobId, boolean alreadyExists,
                                 ParsedBrief parsed, java.util.List<String> warnings) {
        public record ParsedBrief(String company, String title, String city, LocalDate deadline) {
        }
    }
}
