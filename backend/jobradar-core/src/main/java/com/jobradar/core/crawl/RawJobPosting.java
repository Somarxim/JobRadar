package com.jobradar.core.crawl;

/**
 * 爬虫解析出的原始岗位条目（未归一化、未落库）。
 * 字段全部字符串承接（站点日期格式五花八门），归一化在管线层做。
 */
public record RawJobPosting(
        String company,
        String title,
        String city,
        String salaryRange,
        String publishDate,
        String deadline,
        /** 岗位详情页 URL（绝对地址，溯源与二次抓取用） */
        String url,
        /** 列表页能拿到的 JD 摘要/正文（多数站点列表页没有详情，可空） */
        String jdText) {
}
