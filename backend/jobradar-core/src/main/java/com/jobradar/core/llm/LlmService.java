package com.jobradar.core.llm;

import com.jobradar.core.domain.CompanyType;
import com.jobradar.core.domain.LlmUsage;
import com.jobradar.core.repository.LlmUsageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.MimeTypeUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
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

    /** 当前 parse 模型名（match_reports.model_used 落库用，效果回溯） */
    public String parseModelName() {
        return parseConfig.model();
    }

    private final LlmUsageRepository usageRepository;
    private final PlatformTransactionManager txManager;
    private final LlmModelConfig parseConfig;
    private final LlmModelConfig visionConfig;
    private final OpenAiChatModel parseModel;
    private final OpenAiChatModel visionModel;
    /** 每日 token 成本闸：null = 不限。超闸后所有 LLM 调用直接拒发并记失败账 */
    private final Long dailyTokenLimit;

    public LlmService(LlmUsageRepository usageRepository, PlatformTransactionManager txManager,
                      LlmModelConfig parseConfig, LlmModelConfig visionConfig, Long dailyTokenLimit) {
        this.usageRepository = usageRepository;
        this.txManager = txManager;
        this.parseConfig = parseConfig;
        this.visionConfig = visionConfig;
        this.dailyTokenLimit = dailyTokenLimit;
        this.parseModel = parseConfig.available() ? buildModel(parseConfig) : null;
        this.visionModel = visionConfig.available() ? buildModel(visionConfig) : null;
        log.info("LLM 配置：parse={}, vision={}, 每日token闸={}",
                parseConfig.available() ? parseConfig.model() : "未启用",
                visionConfig.available() ? visionConfig.model() : "未启用",
                dailyTokenLimit == null ? "不限" : dailyTokenLimit);
    }

    private static OpenAiChatModel buildModel(LlmModelConfig cfg) {
        OpenAiApi api = OpenAiApi.builder().baseUrl(cfg.baseUrl()).apiKey(cfg.apiKey()).build();
        return OpenAiChatModel.builder().openAiApi(api).defaultOptions(
                OpenAiChatOptions.builder().model(cfg.model()).build()).build();
    }

    /**
     * 成本闸：今日累计 token 超限时拒发请求（记一条失败账便于排查），返回 true。
     * 所有 LLM 任务入口先过此闸——限额是唯一事实来源，调用方无需各自判断。
     */
    private boolean overBudget(String task, String model) {
        if (dailyTokenLimit == null) {
            return false;
        }
        // 「今日」按服务器本地时区零点切（单用户本地应用，时区直觉与使用者一致）
        Instant dayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant();
        long used = usageRepository.sumTokensSince(dayStart);
        if (used < dailyTokenLimit) {
            return false;
        }
        log.warn("LLM 每日 token 限额已用完（{}/{}），任务 {} 被拒发", used, dailyTokenLimit, task);
        recordUsage(task, model, null, false,
                "daily token budget exceeded (" + used + "/" + dailyTokenLimit + ")", 0);
        return true;
    }

    /** JD 文本解析是否可用（未配置 key 时前端应提示仍需手填） */
    public boolean parseAvailable() {
        return parseModel != null;
    }

    /**
     * JD 全文 → 结构化岗位信息。失败（未配置/网络/解析错误）返回 Optional.empty()，
     * 由调用方降级为人工填写流程（契约 422）。
     *
     * <p>职责定位（W2-1 修正）：公司/岗位名主要由调用方 hints 提供（人工手填或插件
     * 从页面 DOM 提取），本方法的主职是补全 city/salary/deadline 等 enrichment
     * 字段——JD 正文经常根本不出现公司名，强求 AI 识别只会诱发幻觉。因此 prompt
     * 明确「未出现则 null，禁止猜测」。
     */
    public Optional<ParsedJob> parseJd(String rawText) {
        if (parseModel == null || overBudget("jd_parse", parseConfig.model())) {
            return Optional.empty();
        }
        var converter = new BeanOutputConverter<>(ParsedJob.class);
        String prompt = """
                你是招聘信息解析助手。请从下面的招聘 JD 文本中提取关键字段。

                要求：
                - company：公司官方全称（去掉"招聘""人力资源部"等后缀）。
                  仅在文本中明确出现时提取，否则输出 null，禁止猜测或编造
                - title：岗位名称（含校招/实习等批次后缀，如"（2026校招）"）。
                  同样仅在明确出现时提取，否则 null
                - city：工作城市（多个城市取第一个）
                - salary_range：薪资范围原文（如"18-25万/年"），无则 null
                - deadline：投递截止日期，格式 yyyy-MM-dd，无则 null
                - publish_date：发布日期，格式 yyyy-MM-dd，无则 null
                - 忽略文本中与本岗位无关的内容：平台安全提示/防诈骗声明、
                  "看过该职位的人还在看"等职位推荐、广告、网站导航与页脚
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
     * 海报图片 → 结构化岗位信息（两段式管线，W2-2 修正）：
     * <ol>
     *   <li><b>转录</b>（视觉模型）：忠实转录海报全部文字。海报信息密度远高于纯文本 JD，
     *       一步到位直出 JSON 会约束模型发挥；转录稿本身即海报的「文字层存档」（落 jd_text）。</li>
     *   <li><b>结构化</b>（文本模型）：从转录稿提取字段。后续调 prompt 迭代只花文本模型
     *       的 token，不必反复烧视觉模型重跑同一张图——调试成本差一个数量级。</li>
     * </ol>
     * 任一阶段失败返回 Optional.empty()，调用方降级人工填写。
     */
    public Optional<ParsedJob> parsePoster(String imageBase64, String mediaType) {
        return transcribePoster(imageBase64, mediaType)
                .flatMap(transcription -> structurePosterText(transcription)
                        .map(p -> new ParsedJob(p.company(), p.title(), p.city(),
                                p.salaryRange(), p.deadline(), p.publishDate(), transcription)));
    }

    /** 第一阶段：视觉模型忠实转录海报文字（替代 OCR：模型同时读懂版式与文字） */
    public Optional<String> transcribePoster(String imageBase64, String mediaType) {
        if (visionModel == null || overBudget("poster_transcribe", visionConfig.model())) {
            return Optional.empty();
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(imageBase64);
        } catch (IllegalArgumentException e) {
            log.warn("海报图片 base64 解码失败");
            return Optional.empty();
        }
        String promptText = """
                请忠实转录这张招聘海报的全部文字内容，按海报的版块结构组织输出
                （标题、公司/单位介绍、岗位信息、任职要求、福利、投递方式等）。

                要求：
                - 只转录海报中真实出现的文字，不要补充、推测或改写
                - 保留数字、日期、联系方式、二维码旁说明等关键信息的原始写法
                - 按阅读顺序输出纯文本，不要输出任何转录之外的内容
                """;

        long start = System.currentTimeMillis();
        try {
            var media = new Media(MimeTypeUtils.parseMimeType(
                    mediaType != null && !mediaType.isBlank() ? mediaType : "image/png"),
                    new ByteArrayResource(bytes));
            var msg = UserMessage.builder().text(promptText).media(media).build();
            ChatResponse resp = visionModel.call(new Prompt(msg,
                    OpenAiChatOptions.builder().model(visionConfig.model())
                            .temperature(PARSE_TEMPERATURE).build()));
            String text = resp.getResult().getOutput().getText();
            recordUsage("poster_transcribe", visionConfig.model(), resp.getMetadata().getUsage(),
                    true, null, elapsed(start));
            return Optional.ofNullable(text).filter(t -> !t.isBlank());
        } catch (Exception e) {
            log.warn("海报转录失败: {}", e.getMessage());
            recordUsage("poster_transcribe", visionConfig.model(), null, false,
                    truncate(e.getMessage()), elapsed(start));
            return Optional.empty();
        }
    }

    /**
     * 第二阶段：文本模型把转录稿结构化为 ParsedJob。
     * 海报特有问题：宣讲会/校招启动海报常只有单位没有具体岗位——此时输出
     * 「<批次>校园招聘」占位岗位名（如"2026届校园招聘"），后续看到具体 JD 再人工拆分。
     */
    public Optional<ParsedJob> structurePosterText(String transcription) {
        if (parseModel == null || overBudget("poster_struct", parseConfig.model())) {
            return Optional.empty();
        }
        var converter = new BeanOutputConverter<>(ParsedJob.class);
        String prompt = """
                你是招聘信息解析助手。下面是一段招聘海报的文字转录稿，请提取关键字段。

                要求：
                - company：公司/单位官方全称（海报一定有招聘单位，务必提取）
                - title：岗位名称（含校招/实习等批次后缀）。多个具体岗位取最主要的一个；
                  若转录稿未列出具体岗位（如校招启动/宣讲会海报），输出「<批次>校园招聘」
                  （批次从转录稿识别，如"2026届校园招聘"；识别不到批次就写"校园招聘"）
                - city：工作城市（多个城市取第一个），无则 null
                - salary_range：薪资范围原文，无则 null
                - deadline：投递截止日期，格式 yyyy-MM-dd，无则 null
                - publish_date：发布日期，格式 yyyy-MM-dd，无则 null
                - jd_text 输出 null（转录稿全文由调用方存档，无需重复）
                - 除上述 JSON 外不要输出任何其他内容
                %s

                海报转录稿：
                ---
                %s
                ---
                """.formatted(converter.getFormat(), transcription);

        long start = System.currentTimeMillis();
        try {
            ChatResponse resp = parseModel.call(new Prompt(prompt,
                    OpenAiChatOptions.builder().model(parseConfig.model())
                            .temperature(PARSE_TEMPERATURE).build()));
            ParsedJob parsed = converter.convert(resp.getResult().getOutput().getText());
            recordUsage("poster_struct", parseConfig.model(), resp.getMetadata().getUsage(),
                    true, null, elapsed(start));
            return Optional.ofNullable(parsed);
        } catch (Exception e) {
            log.warn("海报转录稿结构化失败: {}", e.getMessage());
            recordUsage("poster_struct", parseConfig.model(), null, false,
                    truncate(e.getMessage()), elapsed(start));
            return Optional.empty();
        }
    }

    /** 视觉模型（海报图片解析，W2 后续切片接入） */
    public Optional<OpenAiChatModel> visionModel() {
        return Optional.ofNullable(visionModel);
    }

    /** 匹配评估的 prompt 版本：改 prompt 时递增，match_reports 落库可回溯对比效果（A/B 叙事点） */
    public static final String MATCH_PROMPT_VERSION = "match-v1";

    /** 叙事类任务温度：复盘需要一点表达多样性，但内容仍是数据驱动，不宜放飞 */
    private static final double NARRATIVE_TEMPERATURE = 0.6;

    /**
     * 周报叙事（W4-2）：统计数据 JSON → Markdown 复盘文本。与抽取式任务不同，
     * 这里是自由文本生成（不做结构化约束），质量靠「只许用给定数据」的 prompt 约束。
     * 失败/未启用返回 Optional.empty()，调用方降级为纯数据版周报（功能不中断）。
     */
    public Optional<String> narrateWeeklyReport(String statsJson) {
        if (parseModel == null || overBudget("weekly_report", parseConfig.model())) {
            return Optional.empty();
        }
        String systemPrompt = """
                你是一位秋招求职复盘教练。根据用户一周的求职统计数据（JSON），写一份简短的 Markdown 周报。

                要求：
                - 结构固定四节：## 本周概览（关键数字点评）/ ## 亮点 / ## 风险与问题 / ## 下周建议
                - 只使用给定 JSON 中的数据，禁止编造不存在的公司、岗位或数字
                - 下周建议要具体到动作（如「跟进 XX 的笔试安排」），不要说正确的废话
                - 语气务实直接，像有经验的师兄复盘；全文 300-500 字
                - 直接输出 Markdown 正文，不要用代码块包裹
                """;
        long start = System.currentTimeMillis();
        try {
            ChatResponse resp = parseModel.call(new Prompt(
                    List.of(new SystemMessage(systemPrompt), new UserMessage(statsJson)),
                    OpenAiChatOptions.builder().model(parseConfig.model())
                            .temperature(NARRATIVE_TEMPERATURE).build()));
            String text = resp.getResult().getOutput().getText();
            recordUsage("weekly_report", parseConfig.model(), resp.getMetadata().getUsage(),
                    true, null, elapsed(start));
            return Optional.ofNullable(text).filter(t -> !t.isBlank());
        } catch (Exception e) {
            log.warn("周报叙事生成失败: {}", e.getMessage());
            recordUsage("weekly_report", parseConfig.model(), null, false,
                    truncate(e.getMessage()), elapsed(start));
            return Optional.empty();
        }
    }

    /** JD 原文截断上限：超长 JD 截断防 token 爆炸（设计文档 §4：截断 3000 字） */
    private static final int JD_MAX_CHARS = 3000;

    /**
     * 匹配精评：岗位上下文 + JD 原文 + ResumeProfile JSON → MatchDetail。
     * 质量敏感任务：解析/校验失败重试 1 次（附上次错误让模型自我修正），仍失败返回
     * Optional.empty() 由调用方 422 降级。
     */
    public Optional<MatchDetail> evaluateMatch(String jobHeader, String jdText, String resumeProfileJson) {
        if (parseModel == null || overBudget("match_eval", parseConfig.model())) {
            return Optional.empty();
        }
        var converter = new BeanOutputConverter<>(MatchDetail.class);
        String jd = jdText == null ? "" : jdText.strip();
        if (jd.length() > JD_MAX_CHARS) {
            jd = jd.substring(0, JD_MAX_CHARS) + "\n……（原文过长已截断）";
        }
        String systemPrompt = """
                你是一位资深校招求职顾问，熟悉军工研究所、央国企、银行的校园招聘惯例。
                请根据「岗位信息 + JD 原文」与「候选人简历档案」评估匹配度。

                评估要求：
                - hard_checks 必须逐条核对硬性条件：学历层次、专业对口、应届身份、
                  工作地点，以及 JD 中出现的政治面貌/保密要求（如"能适应封闭管理"）/
                  性别或年龄限制等，每一条给出简历对应情况与是否通过
                - 先输出 hard_checks 再打分：hard_pass=false 时 score_total 不得超过 39，
                  且 one_liner 必须点明未满足的硬性条件
                - 打分维度：技能重合 40 分 + 经历契合 40 分 + 综合契合 20 分
                  （含城市匹配、公司类型与候选人意向的契合度），score_total 为三项之和；
                  score_breakdown 的键固定为英文：skill / experience / fit
                - 分数锚点：≥85 强匹配必投 / 70-84 推荐 / 55-69 可投 / <55 不推荐
                - matched_skills/missing_skills 与简历技能同词表（用简历中的写法）
                - highlights 引用简历中最契合的 2-3 个经历要点
                - suggestion 给出明确投递建议：是否建议投递 + 简历侧重点 + 注意事项
                - 除 JSON 外不要输出任何其他内容
                %s
                """.formatted(converter.getFormat());
        String userPrompt = """
                【岗位信息】
                %s

                【JD 原文】
                ---
                %s
                ---

                【候选人简历档案（结构化 JSON）】
                ---
                %s
                ---
                """.formatted(jobHeader, jd, resumeProfileJson);

        // 失败重试 1 次：结构化输出任务偶发 JSON 截断/格式漂移，重试命中率很高
        String lastError = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            long start = System.currentTimeMillis();
            try {
                String user = lastError == null ? userPrompt
                        : userPrompt + "\n（上次你的输出解析失败：" + lastError + "，请严格按 JSON Schema 重新输出）";
                ChatResponse resp = parseModel.call(new Prompt(
                        List.of(new SystemMessage(systemPrompt), new UserMessage(user)),
                        OpenAiChatOptions.builder().model(parseConfig.model())
                                .temperature(PARSE_TEMPERATURE).build()));
                MatchDetail detail = converter.convert(resp.getResult().getOutput().getText());
                if (detail == null || detail.scoreTotal() == null) {
                    throw new IllegalStateException("模型输出缺少 score_total");
                }
                recordUsage("match_eval", parseConfig.model(), resp.getMetadata().getUsage(),
                        true, null, elapsed(start));
                return Optional.of(detail);
            } catch (Exception e) {
                lastError = truncate(e.getMessage());
                log.warn("匹配评估第 {} 次尝试失败: {}", attempt, e.getMessage());
                recordUsage("match_eval", parseConfig.model(), null, false, lastError, elapsed(start));
            }
        }
        return Optional.empty();
    }

    /**
     * 简历文本 → ResumeProfile 结构化档案（复用 parse 模型：抽取式任务，文本模型足够，
     * 无需动用更贵的视觉模型）。失败返回 Optional.empty()，调用方保留原文待人工/重试。
     */
    public Optional<ParsedResume> parseResume(String resumeText) {
        if (parseModel == null || overBudget("resume_parse", parseConfig.model())) {
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

    /**
     * 公司名称 → 企业性质（LLM fallback）：规则分类器命中 OTHER 时调用。
     * 轻量分类任务，用 parse 模型即可；结果不持久化，由调用方缓存。
     */
    public Optional<CompanyType> classifyCompanyType(String companyName) {
        if (parseModel == null || overBudget("company_type_classify", parseConfig.model())) {
            return Optional.empty();
        }
        var converter = new BeanOutputConverter<>(CompanyTypeResult.class);
        String prompt = """
                你是中国企业性质识别专家。请根据公司名称判断其所属类型。

                可选类型（严格从中选择其一，输出小写英文）：
                - internet：互联网/科技/软件/IT/AI/电商/游戏公司（如阿里、腾讯、字节、百度、美团、小米、华为、蔚来、小鹏、商汤、科大讯飞、亿通国际、阿丘科技）
                - soe_central：央企/中央直属国企（名称常带"中国"前缀，如国家电网、中石油、中国移动、中国航天）
                - soe_local：地方国企/城投/地铁/燃气/水务/公交（名称常带城市名前缀）
                - institute：军工/科研院所/设计院（如中国电科、航天科技、中科院、工程物理）
                - operator：电信运营商（中国移动、中国联通、中国电信、中国铁塔）
                - bank：银行/金融机构（如工商银行、招商银行、银联、农信社）
                - foreign：外资/合资企业（如微软、谷歌、IBM、高通、英特尔、爱立信）
                - other：以上均不符合（如传统制造业、零售、餐饮、物流、房地产、教育培训）

                规则：只看公司名判断；科技公司（含软件、AI、SaaS）一律 internet；只输出 JSON 不要解释。
                %s

                公司名称：%s
                """.formatted(converter.getFormat(), companyName);

        long start = System.currentTimeMillis();
        try {
            ChatResponse resp = parseModel.call(new Prompt(prompt,
                    OpenAiChatOptions.builder().model(parseConfig.model())
                            .temperature(PARSE_TEMPERATURE).build()));
            CompanyTypeResult r = converter.convert(resp.getResult().getOutput().getText());
            CompanyType type = mapCompanyType(r != null ? r.type() : null);
            recordUsage("company_type_classify", parseConfig.model(), resp.getMetadata().getUsage(),
                    true, null, elapsed(start));
            return Optional.of(type);
        } catch (Exception e) {
            log.warn("公司类型 LLM 分类失败: {}", e.getMessage());
            recordUsage("company_type_classify", parseConfig.model(), null, false,
                    truncate(e.getMessage()), elapsed(start));
            return Optional.empty();
        }
    }

    private static CompanyType mapCompanyType(String raw) {
        if (raw == null || raw.isBlank()) return CompanyType.OTHER;
        try {
            return CompanyType.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return CompanyType.OTHER;
        }
    }

    /** LLM 分类原始输出结构（仅用于 classifyCompanyType） */
    public record CompanyTypeResult(String type) {}

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
