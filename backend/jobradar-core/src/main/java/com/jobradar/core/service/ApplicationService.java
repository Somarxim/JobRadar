package com.jobradar.core.service;

import com.jobradar.core.domain.Application;
import com.jobradar.core.domain.ApplicationEvent;
import com.jobradar.core.domain.ApplicationStage;
import com.jobradar.core.domain.Job;
import com.jobradar.core.dto.ApplicationDtos.ApplicationCard;
import com.jobradar.core.dto.ApplicationDtos.ApplicationCreateRequest;
import com.jobradar.core.dto.ApplicationDtos.ApplicationDetail;
import com.jobradar.core.dto.ApplicationDtos.ApplicationPatchRequest;
import com.jobradar.core.dto.ApplicationDtos.BoardResponse;
import com.jobradar.core.dto.ApplicationDtos.EventItem;
import com.jobradar.core.dto.ApplicationDtos.StageTransitionRequest;
import com.jobradar.core.dto.ApplicationDtos.TransitionResponse;
import com.jobradar.core.exception.BadRequestException;
import com.jobradar.core.exception.ConflictException;
import com.jobradar.core.exception.NotFoundException;
import com.jobradar.core.repository.ApplicationEventRepository;
import com.jobradar.core.repository.ApplicationRepository;
import com.jobradar.core.repository.JobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 投递生命周期服务：看板 / 创建 / 部分更新 / 状态流转（写事件留痕）。
 * 流转是核心业务规则所在："更新 applications + 追加 application_events" 必须在同一事务。
 */
/**
 * 投递生命周期服务：看板 / 创建 / 部分更新 / 状态流转（写事件留痕）。
 * 流转是核心业务规则所在："更新 applications + 追加 application_events" 必须在同一事务。
 *
 * 待办（next_action）维护策略：系统按阶段给"默认下一步"（无时间的自动待办，Dashboard 显示"尽快"），
 * 用户 PATCH 显式改过文案的视为自定义，流转时绝不覆盖；判定依据 = 当前文案是否等于 from 阶段的默认文案。
 */
@Service
public class ApplicationService {

    /** 各阶段的默认待办文案；终态（REJECTED/WITHDRAWN）无待办（不在表中即清空） */
    private static final Map<ApplicationStage, String> DEFAULT_NEXT_ACTION = Map.of(
            ApplicationStage.COLLECTED, "评估是否投递",
            ApplicationStage.PLANNED, "完成投递",
            ApplicationStage.APPLIED, "跟进进度，准备笔试",
            ApplicationStage.WRITTEN_TEST, "参加笔试",
            ApplicationStage.INTERVIEW, "参加面试",
            ApplicationStage.OFFER, "确认 offer");

    private final ApplicationRepository applicationRepository;
    private final ApplicationEventRepository eventRepository;
    private final JobRepository jobRepository;

    public ApplicationService(ApplicationRepository applicationRepository,
                              ApplicationEventRepository eventRepository,
                              JobRepository jobRepository) {
        this.applicationRepository = applicationRepository;
        this.eventRepository = eventRepository;
        this.jobRepository = jobRepository;
    }

    /** 看板数据源（GET /applications）：按 stage 分组，key 为小写阶段名（契约格式） */
    @Transactional(readOnly = true)
    public BoardResponse board() {
        Map<String, List<ApplicationCard>> groups = new LinkedHashMap<>();
        for (ApplicationStage stage : ApplicationStage.values()) {
            groups.put(stage.name().toLowerCase(java.util.Locale.ROOT), new java.util.ArrayList<>());
        }
        applicationRepository.findByJobActiveTrue().stream()
                .map(this::toCard)
                .forEach(card -> groups.get(card.stage().name().toLowerCase(java.util.Locale.ROOT))
                        .add(card));
        return new BoardResponse(groups);
    }

    /** 创建投递（POST /applications）：一岗一投递（job_id 唯一约束），重复收藏 409 */
    @Transactional
    public ApplicationCard create(ApplicationCreateRequest req) {
        Job job = jobRepository.findById(req.jobId())
                .orElseThrow(() -> new NotFoundException("岗位不存在: id=" + req.jobId()));
        applicationRepository.findByJobId(req.jobId()).ifPresent(a -> {
            throw new ConflictException("该岗位已有投递记录（application_id=" + a.getId() + "）");
        });

        ApplicationStage stage = req.stage() == null ? ApplicationStage.COLLECTED : req.stage();
        Application app = new Application();
        app.setJob(job);
        app.setStage(stage);
        if (req.priority() != null) app.setPriority(req.priority());
        app.setPlannedAt(req.plannedAt());
        app.setNotes(req.notes());
        if (stage == ApplicationStage.APPLIED) {
            app.setAppliedAt(Instant.now());
        }
        // 新建即给默认待办（无时间 = "尽快"），让 Dashboard 待办区开箱有内容
        app.setNextAction(DEFAULT_NEXT_ACTION.get(stage));
        app.setNextActionAt(null);
        Application saved = applicationRepository.save(app);

        // 首条事件：from_stage 为 NULL 表示"从无到有"的创建
        ApplicationEvent event = new ApplicationEvent();
        event.setApplication(saved);
        event.setFromStage(null);
        event.setToStage(stage);
        event.setNote("创建投递记录");
        eventRepository.save(event);
        return toCard(saved);
    }

    /** 部分更新（PATCH /applications/{id}）：备注/优先级/计划日/下一步动作 */
    @Transactional
    public ApplicationDetail patch(long id, ApplicationPatchRequest req) {
        Application app = findOr404(id);
        if (req.priority() != null) app.setPriority(req.priority());
        if (req.plannedAt() != null) app.setPlannedAt(req.plannedAt());
        if (req.nextAction() != null) app.setNextAction(req.nextAction());
        if (req.nextActionAt() != null) app.setNextActionAt(req.nextActionAt());
        if (req.notes() != null) app.setNotes(req.notes());
        if (req.channel() != null) app.setChannel(req.channel());
        return toDetail(app);
    }

    /**
     * 状态流转（POST /applications/{id}/stage）。
     * 幂等（契约 §3）：重复流转到同一阶段直接返回当前态，不重复写事件。
     * 事务内：更新阶段 + 追加事件；to_stage=applied 时回填 applied_at。
     */
    @Transactional
    public TransitionResponse transition(long id, StageTransitionRequest req) {
        Application app = findOr404(id);
        ApplicationStage from = app.getStage();
        ApplicationStage to = req.toStage();

        if (from == to) {
            return new TransitionResponse(toDetail(app), eventsOf(id));
        }
        if (to == ApplicationStage.APPLIED && (req.channel() == null || req.channel().isBlank())
                && app.getChannel() == null) {
            // 契约 §2.3：applied 时必填 channel（入参或既有值至少其一）
            throw new BadRequestException("to_stage=applied 时必须提供 channel（official/boss/niuke/email/referral/campus_talk）");
        }

        Instant occurredAt = parseOccurredAt(req.occurredAt());
        app.setStage(to);
        if (req.channel() != null && !req.channel().isBlank()) {
            app.setChannel(req.channel());
        }
        if (to == ApplicationStage.APPLIED && app.getAppliedAt() == null) {
            app.setAppliedAt(occurredAt);
        }
        applyAutoNextAction(app, from, to);

        ApplicationEvent event = new ApplicationEvent();
        event.setApplication(app);
        event.setFromStage(from);
        event.setToStage(to);
        event.setNote(req.note());
        event.setCreatedAt(occurredAt); // 支持补录历史投递（occurred_at 显式传入）
        eventRepository.save(event);

        return new TransitionResponse(toDetail(app), eventsOf(id));
    }

    @Transactional(readOnly = true)
    public List<EventItem> eventsOf(long applicationId) {
        if (!applicationRepository.existsById(applicationId)) {
            throw new NotFoundException("投递记录不存在: id=" + applicationId);
        }
        return eventRepository.findByApplicationIdOrderByCreatedAtDesc(applicationId).stream()
                .map(e -> new EventItem(e.getId(), e.getFromStage(), e.getToStage(),
                        e.getNote(), e.getCreatedAt()))
                .toList();
    }

    /**
     * occurred_at 宽容解析：优先按带时区的 ISO 8601（OffsetDateTime）；
     * 不带时区则按服务器本地时区解释（本机单用户部署，时区即用户所在）。
     */
    private Instant parseOccurredAt(String raw) {
        if (raw == null || raw.isBlank()) {
            return Instant.now();
        }
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(raw).atZone(ZoneId.systemDefault()).toInstant();
            } catch (DateTimeParseException e) {
                throw new BadRequestException("occurred_at 无法解析为 ISO 8601 时间: " + raw);
            }
        }
    }

    private Application findOr404(long id) {
        return applicationRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("投递记录不存在: id=" + id));
    }

    /**
     * 流转后维护自动待办：当前文案为空或仍是 from 阶段默认文案（= 未被用户改过）才替换；
     * 用户自定义文案原样保留。终态清空；自动待办一律不带时间（显示"尽快"）。
     */
    private void applyAutoNextAction(Application app, ApplicationStage from, ApplicationStage to) {
        String current = app.getNextAction();
        boolean isAuto = current == null || current.equals(DEFAULT_NEXT_ACTION.get(from));
        if (!isAuto) {
            return;
        }
        String next = DEFAULT_NEXT_ACTION.get(to); // 终态 absent → null = 清空
        app.setNextAction(next);
        app.setNextActionAt(null);
    }

    private ApplicationCard toCard(Application a) {
        Job j = a.getJob(); // LAZY 关联：在事务内触碰加载（OSIV 已关，出事务再碰就抛异常）
        return new ApplicationCard(a.getId(), j.getId(), j.getCompany().getName(), j.getTitle(),
                j.getCity(), a.getStage(), a.getPriority(), a.getPlannedAt(),
                a.getNextAction(), a.getNextActionAt(), a.getUpdatedAt());
    }

    private ApplicationDetail toDetail(Application a) {
        Job j = a.getJob();
        return new ApplicationDetail(a.getId(), j.getId(), j.getCompany().getName(), j.getTitle(),
                a.getStage(), a.getChannel(), a.getPriority(), a.getPlannedAt(),
                a.getNextAction(), a.getNextActionAt(), a.getNotes(), a.getAppliedAt(),
                a.getCreatedAt(), a.getUpdatedAt());
    }
}
