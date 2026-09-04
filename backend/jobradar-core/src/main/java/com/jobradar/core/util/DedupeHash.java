package com.jobradar.core.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 岗位去重指纹：company + title + city 归一化后取 SHA-256。
 * 归一化规则：trim + 去全部空白 + 小写——" 华为 "与"华为"视为同一公司。
 * 与 jobs.dedupe_hash 唯一约束配合：应用层先查（友好提示），约束兜底并发。
 */
public final class DedupeHash {

    private DedupeHash() {
    }

    public static String of(String company, String title, String city) {
        String raw = normalize(company) + "|" + normalize(title) + "|" + normalize(city);
        try {
            // SHA-256 截断到 64 hex 字符（前 128 bit），碰撞概率对个人量级数据可忽略
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            // JDK 保证 SHA-256 存在，永远不会到这里
            throw new IllegalStateException(e);
        }
    }

    private static String normalize(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "").toLowerCase();
    }
}
