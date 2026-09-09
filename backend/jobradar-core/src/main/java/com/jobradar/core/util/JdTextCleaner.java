package com.jobradar.core.util;

import java.util.List;

/**
 * JD 文本清洗：截断招聘页正文之后的平台噪音（侧边推荐/安全提示/广告）。
 *
 * <p>背景（W2-5 实测）：插件/粘贴拿到的页面文本常混入「看过该职位的人还在看」
 * 「安全提示」等非岗位内容，直接入库会导致 JD 存档杂乱、匹配评估被稀释。
 *
 * <p>策略：命中噪音标记词即丢弃该处及之后的内容。依据是 innerText 按 DOM 顺序
 * 输出，推荐位/安全提示通常排在 JD 正文之后——所以这是「截断」而非「删除片段」。
 * 防线分工：插件 DOM 主容器提取是治本，本工具是服务端安全网（任何来源都受益，
 * 包括旧版插件与手动粘贴），LLM prompt 的噪音忽略指令是最后兜底。
 *
 * <p>注意与 extension/popup.js 的 NOISE_MARKERS 保持一致（两处独立演化风险已知，
 * W3 爬虫解析器上线后统一到配置）。
 */
public final class JdTextCleaner {

    /** 噪音边界词：命中即截断。按实测出现频率排序，高频在前（短路收益） */
    private static final List<String> NOISE_MARKERS = List.of(
            "看过该职位的人还在看", "看过该职位的人还看了",
            "安全提示", "防诈骗",
            "猜你喜欢", "为你推荐", "相似职位", "推荐职位", "热门职位",
            "相关职位推荐", "大家都在看", "精选职位", "最新推荐",
            "面试经验", "公司点评", "换一批"
    );

    /**
     * 截断保护线：标记命中位置太靠前时不截——说明它可能只是正文里正常提及
     * （如 JD 自带"安全提示"条款），截断会误伤正文。低于此长度也直接返回原文。
     */
    private static final int MIN_KEEP_CHARS = 500;

    private JdTextCleaner() {
    }

    /** 返回截断后的文本；无命中或命中过前时返回原文 */
    public static String truncateNoise(String text) {
        if (text == null || text.length() < MIN_KEEP_CHARS) {
            return text;
        }
        int cut = text.length();
        for (String marker : NOISE_MARKERS) {
            int i = text.indexOf(marker);
            if (i >= MIN_KEEP_CHARS && i < cut) {
                cut = i;
            }
        }
        return text.substring(0, cut);
    }
}
