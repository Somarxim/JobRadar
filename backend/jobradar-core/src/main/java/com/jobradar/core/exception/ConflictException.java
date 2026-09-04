package com.jobradar.core.exception;

/** 状态冲突（重复收藏/重复录入）→ 409 */
public class ConflictException extends BizException {

    public ConflictException(String message) {
        super(message);
    }
}
