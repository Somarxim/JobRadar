package com.jobradar.app.web;

import com.jobradar.core.exception.BadRequestException;
import com.jobradar.core.exception.ConflictException;
import com.jobradar.core.exception.NotFoundException;
import com.jobradar.core.exception.UnprocessableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 全局异常 → HTTP 响应映射（契约 §3：错误体统一 {"detail": ...}）。
 *
 * <p>@RestControllerAdvice 教学点：把"异常→响应"的横切逻辑从 Controller 里剥离，
 * 集中在一处。core 层只抛语义化的业务异常（不依赖 HTTP），这里完成状态码翻译。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(NotFoundException e) {
        return build(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, String>> conflict(ConflictException e) {
        return build(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(UnprocessableException.class)
    public ResponseEntity<Map<String, String>> unprocessable(UnprocessableException e) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<Map<String, String>> badRequest(BadRequestException e) {
        return build(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** Bean Validation 校验失败（@Valid 触发）：聚合所有字段错误为一条 detail */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> validation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, detail.isBlank() ? "参数校验失败" : detail);
    }

    private ResponseEntity<Map<String, String>> build(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(Map.of("detail", detail));
    }
}
