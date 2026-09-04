package com.jobradar.app.web;

import com.jobradar.core.dto.JobDtos.IngestRequest;
import com.jobradar.core.dto.JobDtos.IngestResponse;
import com.jobradar.core.dto.JobDtos.JobCreateRequest;
import com.jobradar.core.dto.JobDtos.JobDetail;
import com.jobradar.core.dto.JobDtos.JobPatchRequest;
import com.jobradar.core.dto.JobDtos.JobSummary;
import com.jobradar.core.dto.PageResponse;
import com.jobradar.core.service.JobService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 岗位 API（/api/jobs）。Controller 只做三件事：接参（@Valid 校验）→ 调 Service → 返回。
 * 不含业务逻辑，也不需要 @Transactional——事务边界在 Service。
 */
@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @GetMapping
    public PageResponse<JobSummary> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false, name = "company_type") String companyType,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String stage,
            @RequestParam(required = false) String tier,
            @RequestParam(required = false, name = "deadline_before") LocalDate deadlineBefore,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        // semantic / min_match / match_desc 属 W2/W3 能力，JobService 对未知 sort 显式 400
        return jobService.search(q, companyType, city, stage, tier, deadlineBefore, sort, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public JobDetail create(@Valid @RequestBody JobCreateRequest req) {
        return jobService.create(req);
    }

    @GetMapping("/{id}")
    public JobDetail detail(@PathVariable long id) {
        return jobService.detail(id);
    }

    @PatchMapping("/{id}")
    public JobDetail patch(@PathVariable long id, @RequestBody JobPatchRequest req) {
        return jobService.patch(id, req);
    }

    @PostMapping("/ingest")
    public IngestResponse ingest(@Valid @RequestBody IngestRequest req) {
        return jobService.ingest(req);
    }
}
