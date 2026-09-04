package com.jobradar.core.exception;

/** 语义可理解但无法处理（LLM 解析失败引导人工录入等）→ 422 */
public class UnprocessableException extends BizException {

    public UnprocessableException(String message) {
        super(message);
    }
}
