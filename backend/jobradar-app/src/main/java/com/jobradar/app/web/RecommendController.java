package com.jobradar.app.web;

import com.jobradar.core.dto.RecommendDtos.FeedbackRequest;
import com.jobradar.core.dto.RecommendDtos.RecommendRunReport;
import com.jobradar.core.dto.RecommendDtos.RecommendationView;
import com.jobradar.core.service.RecommendService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 每日推荐 API（/api/recommendations，W3-3）：
 * - GET  /today                 今日推荐列表（只读免令牌）
 * - POST /{id}/feedback         反馈闭环：accept→自动建看板卡片 / ignore→记原因标签
 * - POST /run                   手动触发一轮推荐管线（调试入口；与定时调度同一 Service 方法，
 *                               写操作走 LocalTokenFilter：前端 Origin 或 X-Local-Token）
 */
@RestController
@RequestMapping("/api/recommendations")
public class RecommendController {

    private final RecommendService recommendService;

    public RecommendController(RecommendService recommendService) {
        this.recommendService = recommendService;
    }

    @GetMapping("/today")
    public List<RecommendationView> today() {
        return recommendService.today();
    }

    @PostMapping("/{id}/feedback")
    public RecommendationView feedback(@PathVariable long id, @Valid @RequestBody FeedbackRequest req) {
        return recommendService.feedback(id, req);
    }

    @PostMapping("/run")
    public RecommendRunReport run() {
        return recommendService.runPipeline();
    }
}
