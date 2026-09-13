package com.jobradar.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobradar.core.domain.Resume;
import com.jobradar.core.dto.ResumeDtos.ResumeDetail;
import com.jobradar.core.dto.ResumeDtos.ResumeSummary;
import com.jobradar.core.exception.BadRequestException;
import com.jobradar.core.exception.NotFoundException;
import com.jobradar.core.llm.LlmService;
import com.jobradar.core.llm.ParsedResume;
import com.jobradar.core.repository.ResumeRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 简历服务：PDF 上传 → 本地存原件 → PDFBox 抽文本 → LLM 结构化为 ResumeProfile。
 *
 * <p>设计要点（教学）：
 * <ul>
 *   <li><b>core 不碰 web 类型</b>：入参是 byte[] 而非 MultipartFile——core 模块不依赖
 *       spring-web（ADR-5：可被 app/mcp-server 两种宿主复用），MultipartFile 的拆包
 *       由 Controller 完成。这也是「分层隔离」的典型手法。</li>
 *   <li><b>PDFBox 文本提取的局限</b>：PDFTextStripper 只抽「文字层」，扫描件（图片型 PDF）
 *       抽出来是空串——此时走降级：保存原件、parsed 留空、提示用户人工填写或后续
 *       走多模态路线（与海报导入同一套降级哲学）。</li>
 *   <li><b>parsed 列是 JSONB</b>：ResumeProfile 结构会随匹配 Agent 演进（加字段），
 *       用半结构化 JSONB 存储就不用每次改 schema 写迁移——灵活性 vs 强约束的取舍，
 *       面试常问「为什么不用关系表展开」。</li>
 *   <li><b>LLM 解析失败不阻断上传</b>：原件已存，解析可事后 reparse 重试——
 *       上传是用户动作必须成功，解析是增值能力允许异步补偿。</li>
 * </ul>
 *
 * <p>非 Spring 注解组件：与 LlmService 同模式，由 app 层显式构造
 * （构造参数含配置值 resumeDir，组件扫描表达不了，显式 @Bean 更直观）。
 * @Transactional 注解依然生效——事务代理由 BeanPostProcessor 织入，与注册方式无关。
 */
public class ResumeService {

    private static final Logger log = LoggerFactory.getLogger(ResumeService.class);

    /** 单份简历上限 10MB：应届简历通常 <2MB，超限大概率是误传 */
    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;

    private final ResumeRepository resumeRepository;
    private final LlmService llmService;
    private final ObjectMapper objectMapper;
    private final Path resumeDir;

    public ResumeService(ResumeRepository resumeRepository, LlmService llmService,
                         ObjectMapper objectMapper, String resumeDir) {
        this.resumeRepository = resumeRepository;
        this.llmService = llmService;
        this.objectMapper = objectMapper;
        // ~ 展开：yml 默认值 ~/.jobradar/resumes 是 shell 惯例，Java 不自动展开
        String expanded = resumeDir.startsWith("~")
                ? System.getProperty("user.home") + resumeDir.substring(1)
                : resumeDir;
        this.resumeDir = Path.of(expanded);
    }

    /**
     * 上传简历 PDF：存原件 → 抽文本 → LLM 解析（失败降级为 pending，可 reparse）。
     * 第一份简历自动设为默认。
     */
    @Transactional
    public ResumeDetail upload(String originalFilename, byte[] bytes) {
        if (bytes.length == 0) {
            throw new BadRequestException("文件为空");
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw new BadRequestException("文件超过 10MB 上限，请压缩后再传");
        }
        // 只认 PDF：魔数校验（%PDF-）比扩展名可靠——扩展名可伪造，文件头不会说谎
        if (!isPdf(bytes)) {
            throw new BadRequestException("只支持 PDF 格式简历");
        }

        String name = displayName(originalFilename);
        Path stored = store(bytes);

        Resume resume = new Resume();
        resume.setName(name);
        resume.setFilePath(stored.toString());
        // 首份简历自动默认，后续需显式 PATCH default（归档的不算）
        resume.setDefault(resumeRepository.findByIsDefaultTrue().isEmpty());

        parseAndFill(resume, bytes);
        return toDetail(resumeRepository.save(resume));
    }

    @Transactional(readOnly = true)
    public List<ResumeSummary> list() {
        return resumeRepository.findByArchivedFalse().stream()
                .map(r -> new ResumeSummary(r.getId(), r.getName(), r.isDefault(),
                        parseStatus(r), summaryOf(r), r.getCreatedAt()))
                .toList();
    }

    /** 归档简历：有 match_reports 等外键关联时物理删除会破坏历史数据，软删更安全 */
    @Transactional
    public void delete(long id) {
        Resume resume = findOr404(id);
        if (resume.isArchived()) {
            return; // 幂等：已归档直接忽略
        }
        // 若删的是默认简历，需把默认资格转给下一个未归档简历（如果有的话）
        boolean wasDefault = resume.isDefault();
        resume.setArchived(true);
        resume.setDefault(false);
        resumeRepository.save(resume);
        if (wasDefault) {
            resumeRepository.findByArchivedFalse().stream()
                    .findFirst()
                    .ifPresent(next -> {
                        next.setDefault(true);
                        resumeRepository.save(next);
                    });
        }
    }

    @Transactional(readOnly = true)
    public ResumeDetail detail(long id) {
        return toDetail(findOr404(id));
    }

    /**
     * 设为默认简历：同一时刻只能有一份默认——先清后设，事务保证不会出现
     * 「零默认」或「双默认」的中间态被读到。
     */
    @Transactional
    public ResumeDetail setDefault(long id) {
        Resume target = findOr404(id);
        resumeRepository.findByIsDefaultTrue().ifPresent(current -> {
            current.setDefault(false);
            resumeRepository.save(current);
        });
        target.setDefault(true);
        return toDetail(resumeRepository.save(target));
    }

    /** 重新解析：从存储的 PDF 原件重新抽文本+LLM 解析（首次失败或模型升级后用） */
    @Transactional
    public ResumeDetail reparse(long id) {
        Resume resume = findOr404(id);
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(Path.of(resume.getFilePath()));
        } catch (IOException e) {
            throw new UncheckedIOException("简历原件读取失败: " + resume.getFilePath(), e);
        }
        parseAndFill(resume, bytes);
        return toDetail(resumeRepository.save(resume));
    }

    // ---------- 内部实现 ----------

    private Resume findOr404(long id) {
        return resumeRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("简历不存在: id=" + id));
    }

    private static boolean isPdf(byte[] bytes) {
        return bytes.length > 5
                && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F';
    }

    /** 展示名：去扩展名的原文件名，便于用户辨认版本 */
    private static String displayName(String filename) {
        String base = filename == null || filename.isBlank() ? "未命名简历" : filename.trim();
        int dot = base.lastIndexOf('.');
        return dot > 0 ? base.substring(0, dot) : base;
    }

    /** 存原件：UUID 文件名防撞名与路径穿越（绝不用用户文件名直接落盘） */
    private Path store(byte[] bytes) {
        try {
            Files.createDirectories(resumeDir);
            Path target = resumeDir.resolve(java.util.UUID.randomUUID() + ".pdf");
            Files.write(target, bytes);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("简历文件写入失败: " + resumeDir, e);
        }
    }

    /** PDFBox 抽文本 → LLM 结构化；任一环失败仅记日志，parsed 留空走 pending */
    private void parseAndFill(Resume resume, byte[] bytes) {
        String text = extractText(bytes);
        if (text == null || text.isBlank()) {
            log.warn("简历 {} 未抽取到文本层（可能是扫描件），跳过 LLM 解析", resume.getName());
            return;
        }
        Optional<ParsedResume> parsed = llmService.parseResume(text);
        parsed.ifPresent(p -> {
            try {
                resume.setParsed(objectMapper.writeValueAsString(p));
            } catch (IOException e) {
                // 序列化 record 几乎不会失败；真失败也降级为 pending 而非阻断上传
                log.warn("ResumeProfile 序列化失败: {}", e.getMessage());
            }
        });
    }

    private static String extractText(byte[] bytes) {
        // PDFBox 3.x：加载入口从 PDDocument.load 移到 Loader.loadPDF（2.x 写法已移除）。
        // try-with-resources 保证 PDDocument 关闭（PDFBox 会持有内存映射缓冲区）
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(doc);
        } catch (IOException e) {
            log.warn("PDF 文本抽取失败: {}", e.getMessage());
            return null;
        }
    }

    private static String parseStatus(Resume r) {
        return r.getParsed() != null ? "parsed" : "pending";
    }

    /** 列表页摘要：从 JSONB 里只取 summary 字段，失败容忍（数据演进期字段可能缺失） */
    private String summaryOf(Resume r) {
        if (r.getParsed() == null) {
            return null;
        }
        try {
            return objectMapper.readTree(r.getParsed()).path("summary").asText(null);
        } catch (IOException e) {
            return null;
        }
    }

    private ResumeDetail toDetail(Resume r) {
        ParsedResume parsed = null;
        if (r.getParsed() != null) {
            try {
                parsed = objectMapper.readValue(r.getParsed(), ParsedResume.class);
            } catch (IOException e) {
                log.warn("简历 {} 的 parsed JSON 损坏: {}", r.getId(), e.getMessage());
            }
        }
        return new ResumeDetail(r.getId(), r.getName(), r.isDefault(),
                parseStatus(r), parsed, r.getCreatedAt());
    }
}
