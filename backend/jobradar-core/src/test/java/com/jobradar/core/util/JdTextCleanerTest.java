package com.jobradar.core.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 噪音截断：标记词位置与保护线的边界行为 */
class JdTextCleanerTest {

    private static final String JD = "岗位职责：负责大模型应用平台后端开发……".repeat(40); // >500 字正文

    @Test
    void truncatesAtNoiseMarker() {
        String dirty = JD + "看过该职位的人还在看\n广告A\n广告B";
        assertThat(JdTextCleaner.truncateNoise(dirty)).isEqualTo(JD);
    }

    @Test
    void keepsEarliestMarker() {
        String dirty = JD + "猜你喜欢 xxx\n安全提示 yyy";
        assertThat(JdTextCleaner.truncateNoise(dirty)).isEqualTo(JD);
    }

    @Test
    void noMarkerReturnsOriginal() {
        assertThat(JdTextCleaner.truncateNoise(JD)).isEqualTo(JD);
    }

    @Test
    void markerTooEarlyDoesNotCut() {
        // 标记出现在保护线（500 字）之前：视为正文正常提及，不截断
        String text = "安全提示：入职不收取任何费用。" + JD;
        assertThat(JdTextCleaner.truncateNoise(text)).isEqualTo(text);
    }

    @Test
    void nullAndShortPassThrough() {
        assertThat(JdTextCleaner.truncateNoise(null)).isNull();
        assertThat(JdTextCleaner.truncateNoise("短文本 安全提示")).isEqualTo("短文本 安全提示");
    }
}
