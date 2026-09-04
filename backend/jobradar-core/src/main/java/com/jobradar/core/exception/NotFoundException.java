package com.jobradar.core.exception;

/** 资源不存在 → 404 */
public class NotFoundException extends BizException {

    public NotFoundException(String message) {
        super(message);
    }
}
