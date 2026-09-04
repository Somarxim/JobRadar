package com.jobradar.core.exception;

/**
 * 业务异常基类。为什么不直接用 ResponseStatusException（面试点）：
 * 自定义异常体系让 Service 层不依赖 Spring Web API（core 模块可被 MCP stdio 宿主复用，
 * 那里没有 HTTP 语义）；状态码映射集中在 app 层的 @RestControllerAdvice 完成。
 */
public abstract class BizException extends RuntimeException {

    protected BizException(String message) {
        super(message);
    }
}
