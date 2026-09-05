package com.jobradar.app.web;

import com.jobradar.core.dto.MatchDtos.MatchReportView;
import com.jobradar.core.dto.MatchDtos.MatchRequest;
import com.jobradar.core.service.MatchingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 匹配 API（/api/match，契约 api-design.md §2.6）。同步返回（一次 LLM 调用 3-10s），
 * 前端触发时显示 loading；批量匹配 /match/batch 属 W3 每日推荐管线。
 */
@RestController
@RequestMapping("/api/match")
public class MatchController {

    private final MatchingService matchingService;

    public MatchController(MatchingService matchingService) {
        this.matchingService = matchingService;
    }

    /** 触发匹配：body 可空（缺省用默认简历） */
    @PostMapping("/jobs/{jobId}")
    public MatchReportView match(@PathVariable long jobId,
                                 @RequestBody(required = false) MatchRequest req) {
        return matchingService.match(jobId, req != null ? req.resumeId() : null);
    }

    /** 查最新匹配报告；无报告 404（前端据此显示空态 + 生成按钮） */
    @GetMapping("/jobs/{jobId}")
    public MatchReportView latest(@PathVariable long jobId) {
        return matchingService.latest(jobId);
    }
}
