package com.jobradar.app.web;

import com.jobradar.core.dto.ResumeDtos.ResumeDetail;
import com.jobradar.core.dto.ResumeDtos.ResumeListResponse;
import com.jobradar.core.service.ResumeService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * 简历 API（/api/resumes）。multipart 上传的拆包在 Controller 完成——core 的
 * ResumeService 只收 byte[]，保持 web 无关（ADR-5）。
 */
@RestController
@RequestMapping("/api/resumes")
public class ResumeController {

    private final ResumeService resumeService;

    public ResumeController(ResumeService resumeService) {
        this.resumeService = resumeService;
    }

    /** 上传简历 PDF（multipart/form-data，字段名 file） */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ResumeDetail upload(@RequestParam("file") MultipartFile file) {
        try {
            return resumeService.upload(file.getOriginalFilename(), file.getBytes());
        } catch (IOException e) {
            // MultipartFile 读流失败（临时文件被清理等极端情况）
            throw new UncheckedIOException("上传文件读取失败", e);
        }
    }

    @GetMapping
    public ResumeListResponse list() {
        return new ResumeListResponse(resumeService.list());
    }

    @GetMapping("/{id}")
    public ResumeDetail detail(@PathVariable long id) {
        return resumeService.detail(id);
    }

    /** 重新解析（首次解析失败或模型升级后手动触发） */
    @PostMapping("/{id}/reparse")
    public ResumeDetail reparse(@PathVariable long id) {
        return resumeService.reparse(id);
    }

    /** 设为默认简历（投递/匹配时使用的版本） */
    @PatchMapping("/{id}/default")
    public ResumeDetail setDefault(@PathVariable long id) {
        return resumeService.setDefault(id);
    }

    /** 归档简历（软删）：有匹配报告等外键关联时物理删除会破坏历史数据 */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        resumeService.delete(id);
    }
}
