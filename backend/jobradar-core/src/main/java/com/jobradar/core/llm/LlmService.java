package com.jobradar.core.llm;

import com.jobradar.core.domain.LlmUsage;
import com.jobradar.core.repository.LlmUsageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.MimeTypeUtils;

import java.util.Base64;
import java.util.Optional;

/**
 * LLM 门面服务：模型路由 + 结构化输出 + 调用记账。
 *
 * <p>教学点：
 * <ul>
 *   <li><b>OpenAI 兼容协议</b>：DeepSeek、阿里 DashScope（兼容模式）等国产模型
 *       都实现了 OpenAI 的 /chat/completions 协议，因此一套 OpenAiChatModel
 *       换 base-url/api-key 即可切换供应商，无需引入各家 SDK——这就是
 *       「面向协议编程」对「面向供应商编程」的优势。</li>
 *   <li><b>结构化输出</b>：BeanOutputConverter 根据目标 record 生成 JSON Schema
 *       说明附进 prompt，再把响应文本解析回对象。比手写正则稳，但本质是
 *       「约定+解析」而非强约束，所以解析失败要优雅降级（Optional.empty）。</li>
 *   <li><b>记账</b>：成功失败都写 llm_usage——token 用量算成本，error 摘要助排查。
 *       后续成本闸（每日限额）直接对该表聚合。</li>
 * </ul>
 *
 * <p>非 Spring 注解组件：由 app 层 LlmConfig 用配置手工构造（多模型实例，
 * 自动装配表达力不够，显式构造更直观）。
 */
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    /** JD 解析任务的低温度：抽取式任务要确定性，不要创造性 */
    private static final double PARSE_TEMPERATURE = 0.1;

    private final LlmUsageRepository usageRepository;
    private final PlatformTransactionManager txManager;
    private final LlmModelConfig parseConfig;
    private final LlmModelConfig visionConfig;
    private final OpenAiChatModel parseModel;
    private final OpenAiChatModel visionModel;

    public LlmService(LlmUsageRepository usageRepository, PlatformTransactionManager txManager,
                      LlmModelConfig parseConfig, LlmModelConfig visionConfig) {
        this.usageRepository = usageRepository;
        this.txManager = txManager;
        this.parseConfig = parseConfig;
        this.visionConfig = visionConfig;
        this.parseModel = parseConfig.available() ? buildModel(parseConfig) : null;
        this.visionModel = visionConfig.available() ? buildModel(visionConfig) : null;
        log.info("LLM 配置：parse={}, vision={}",
                parseConfig.available() ? parseConfig.model() : "未启用",
                visionConfig.available() ? visionConfig.model() : "未启用");
    }

    private static OpenAiChatModel buildModel(LlmModelConfig cfg) {
        OpenAiApi api = OpenAiApi.builder().baseUrl(cfg.baseUrl()).apiKey(cfg.apiKey()).build();
        return OpenAiChatModel.builder().openAiApi(api).defaultOptions(
                OpenAiChatOptions.builder().model(cfg.model()).build()).build();
    }

    /** JD 文本解析是否可用（未配置 key 时前端应提示仍需手填） */
    public boolean parseAvailable() {
        return parseModel != null;
    }

    /**
     * JD 全文 → 结构化岗位信息。失败（未配置/网络/解析错误）返回 Optional.empty()，
     * 由调用方降级为人工填写流程（契约 422）。
     */
    public Optional<ParsedJob> parseJd(String rawText) {
        if (parseModel == null) {
            return Optional.empty();
        }
        var converter = new BeanOutputConverter<>(ParsedJob.class);
        String prompt = """
                你是招聘信息解析助手。请从下面的招聘 JD 文本中提取关键字段。

                要求：
                - company：公司官方全称（去掉"招聘""人力资源部"等后缀）
                - title：岗位名称（含校招/实习等批次后缀，如"（2026校招）"）
                - city：工作城市（多个城市取第一个）
                - salary_range：薪资范围原文（如"18-25万/年"），无则 null
                - deadline：投递截止日期，格式 yyyy-MM-dd，无则 null
                - publish_date：发布日期，格式 yyyy-MM-dd，无则 null
                - 除上述 JSON 外不要输出任何其他内容
                %s

                JD 文本：
                ---
                %s
                ---
                """.formatted(converter.getFormat(), rawText);

        long start = System.currentTimeMillis();
        try {
            ChatResponse resp = parseModel.call(new Prompt(prompt,
                    OpenAiChatOptions.builder().model(parseConfig.model())
                            .temperature(PARSE_TEMPERATURE).build()));
            String content = resp.getResult().getOutput().getText();
            ParsedJob parsed = converter.convert(content);
            recordUsage("jd_parse", parseConfig.model(), resp.getMetadata().getUsage(),
                    true, null, elapsed(start));
            return Optional.ofNullable(parsed);
        } catch (Exception e) {
            log.warn("JD 解析失败: {}", e.getMessage());
            recordUsage("jd_parse", parseConfig.model(), null, false,
                    truncate(e.getMessage()), elapsed(start));
            return Optional.empty();
        }
    }

    /** 海报图片解析是否可用 */
    public boolean visionAvailable() {
        return visionModel != null;
    }

    /**
     * 海报图片 → 结构化岗位信息（多模态视觉理解，替代 OCR：
     * 模型同时读懂版式与文字，还能把图中信息整理成 JD 文本沉淀）。
     * 失败返回 Optional.empty()，调用方降级人工填写。
     */
    public Optional<ParsedJob> parsePoster(String imageBase64, String mediaType) {
        if (visionModel == null) {
            return Optional.empty();
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(imageBase64);
        } catch (IllegalArgumentException e) {
            log.warn("海报图片 base64 解码失败");
            return Optional.empty();
        }
        var converter = new BeanOutputConverter<>(ParsedJob.class);
        String promptText = """
                你是招聘信息解析助手。这是一张招聘海报图片，请从中提取关键字段。

                要求：
                - company：公司/单位官方全称
                - title：岗位名称（含校招/实习等批次后缀；多个岗位取最主要的一个）
                - city：工作城市（多个城市取第一个）
                - salary_range：薪资范围原文，无则 null
                - deadline：投递截止日期，格式 yyyy-MM-dd，无则 null
                - publish_date：发布日期，格式 yyyy-MM-dd，无则 null
                - jd_text：把海报中与岗位相关的信息（职责、要求、福利、投递方式等）
                  整理成连贯的中文文本，供存档检索
                - 除上述 JSON 外不要输出任何其他内容
                %s
                """.formatted(converter.getFormat());

        long start = System.currentTimeMillis();
        try {
            var media = new Media(MimeTypeUtils.parseMimeType(
                    mediaType != null && !mediaType.isBlank() ? mediaType : "image/png"),
                    new ByteArrayResource(bytes));
            var msg = UserMessage.builder().text(promptText).media(media).build();
            ChatResponse resp = visionModel.call(new Prompt(msg,
                    OpenAiChatOptions.builder().model(visionConfig.model())
                            .temperature(PARSE_TEMPERATURE).build()));
            ParsedJob parsed = converter.convert(resp.getResult().getOutput().getText());
            recordUsage("poster_parse", visionConfig.model(), resp.getMetadata().getUsage(),
                    true, null, elapsed(start));
            return Optional.ofNullable(parsed);
        } catch (Exception e) {
            log.warn("海报解析失败: {}", e.getMessage());
            recordUsage("poster_parse", visionConfig.model(), null, false,
                    truncate(e.getMessage()), elapsed(start));
            return Optional.empty();
        }
    }

    /** 视觉模型（海报图片解析，W2 后续切片接入） */
    public Optional<OpenAiChatModel> visionModel() {
        return Optional.ofNullable(visionModel);
    }

    /**
     * 简历文本 → ResumeProfile 结构化档案（复用 parse 模型：抽取式任务，文本模型足够，
     * 无需动用更贵的视觉模型）。失败返回 Optional.empty()，调用方保留原文待人工/重试。
     */
    public Optional<ParsedResume> parseResume(String resumeText) {
        if (parseModel == null) {
            return Optional.empty();
        }
        var converter = new BeanOutputConverter<>(ParsedResume.class);
        String prompt = """
                你是简历解析助手。请从下面的简历文本中提取结构化信息。

                要求：
                - name：候选人姓名，无则 null
                - education：教育经历列表，每项含 school/degree（本科/硕士/博士）/major/period
                  （如"2022.09-2026.06"）；is985/is211 按公开高校名单推断 true/false
                - skills：技能关键词列表（语言、框架、工具，去重）
                - experiences：经历列表，每项含 type（internship|project|competition|research）、
                  org、role、period、highlights（量化成果要点，每条一句话）
                - target_positions：意向岗位列表（简历中明确写了才提取，否则空列表）
                - target_cities：意向城市列表（同上）
                - awards：获奖/证书列表，无则空列表
                - summary：用 2-3 句话概括候选人画像（学历、技术栈、亮点）
                - 除上述 JSON 外不要输出任何其他内容
                %s

                简历文本：
                ---
                %s
                ---
                """.formatted(converter.getFormat(), resumeText);

        long start = System.currentTimeMillis();
        try {
            ChatResponse resp = parseModel.call(new Prompt(prompt,
                    OpenAiChatOptions.builder().model(parseConfig.model())
                            .temperature(PARSE_TEMPERATURE).build()));
            ParsedResume parsed = converter.convert(resp.getResult().getOutput().getText());
            recordUsage("resume_parse", parseConfig.model(), resp.getMetadata().getUsage(),
                    true, null, elapsed(start));
            return Optional.ofNullable(parsed);
        } catch (Exception e) {
            log.warn("简历解析失败: {}", e.getMessage());
            recordUsage("resume_parse", parseConfig.model(), null, false,
                    truncate(e.getMessage()), elapsed(start));
            return Optional.empty();
        }
    }

    public LlmModelConfig visionConfig() {
        return visionConfig;
    }

    /**
     * 记账必须独立于业务事务（REQUIRES_NEW）：调用方（如 ingest）在解析失败时抛 422 回滚，
     * 若记账混在同一事务里，失败记录会随回滚一并丢失——而失败账恰恰是排查与成本控制的依据。
     */
    private void recordUsage(String task, String model, Usage usage,
                             boolean success, String error, int latencyMs) {
        try {
            new TransactionTemplate(txManager, new DefaultTransactionDefinition(
                    TransactionDefinition.PROPAGATION_REQUIRES_NEW)).executeWithoutResult(tx -> {
                LlmUsage u = new LlmUsage();
                u.setTask(task);
                u.setModel(model);
                if (usage != null) {
                    u.setPromptTokens(usage.getPromptTokens());
                    u.setCompletionTokens(usage.getCompletionTokens());
                    u.setTotalTokens(usage.getTotalTokens());
                }
                u.setSuccess(success);
                u.setError(error);
                u.setLatencyMs(latencyMs);
                usageRepository.save(u);
            });
        } catch (Exception e) {
            // 记账失败不应阻断主流程
            log.warn("LLM 记账失败: {}", e.getMessage());
        }
    }

    private static int elapsed(long start) {
        return (int) (System.currentTimeMillis() - start);
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 900 ? s : s.substring(0, 900);
    }
}
