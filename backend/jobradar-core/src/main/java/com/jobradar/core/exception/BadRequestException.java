package com.jobradar.core.exception;

/** 参数或请求体不合法 → 400 */
public class BadRequestException extends BizException {

    public BadRequestException(String message) {
        super(message);
    }
}
