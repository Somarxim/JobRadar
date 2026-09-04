package com.jobradar.core.dto;

import java.util.List;

/**
 * 统一分页响应（api-design.md §1 契约：{items,total,page,size}，page 从 1 起）。
 * 用 record（Java 16+）做 DTO：不可变、构造器/getter/equals 全免写，序列化天然友好。
 */
public record PageResponse<T>(List<T> items, long total, int page, int size) {

    public static <T> PageResponse<T> of(List<T> items, long total, int page, int size) {
        return new PageResponse<>(items, total, page, size);
    }
}
