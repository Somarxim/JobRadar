package com.jobradar.app.web;

import com.jobradar.core.dto.ApplicationDtos.ApplicationCard;
import com.jobradar.core.dto.ApplicationDtos.ApplicationCreateRequest;
import com.jobradar.core.dto.ApplicationDtos.ApplicationDetail;
import com.jobradar.core.dto.ApplicationDtos.ApplicationPatchRequest;
import com.jobradar.core.dto.ApplicationDtos.BoardResponse;
import com.jobradar.core.dto.ApplicationDtos.EventItem;
import com.jobradar.core.dto.ApplicationDtos.StageTransitionRequest;
import com.jobradar.core.dto.ApplicationDtos.TransitionResponse;
import com.jobradar.core.service.ApplicationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 投递 API（/api/applications）：看板 / 创建 / 更新 / 流转 / 事件时间线 */
@RestController
@RequestMapping("/api/applications")
public class ApplicationController {

    private final ApplicationService applicationService;

    public ApplicationController(ApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @GetMapping
    public BoardResponse board() {
        return applicationService.board();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApplicationCard create(@Valid @RequestBody ApplicationCreateRequest req) {
        return applicationService.create(req);
    }

    @PatchMapping("/{id}")
    public ApplicationDetail patch(@PathVariable long id,
                                   @RequestBody ApplicationPatchRequest req) {
        return applicationService.patch(id, req);
    }

    @PostMapping("/{id}/stage")
    public TransitionResponse transition(@PathVariable long id,
                                         @Valid @RequestBody StageTransitionRequest req) {
        return applicationService.transition(id, req);
    }

    @GetMapping("/{id}/events")
    public List<EventItem> events(@PathVariable long id) {
        return applicationService.eventsOf(id);
    }
}
