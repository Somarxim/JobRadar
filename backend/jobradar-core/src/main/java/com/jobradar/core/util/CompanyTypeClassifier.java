package com.jobradar.core.util;

import com.jobradar.core.domain.CompanyType;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 企业性质规则分类器：公司名 → {@link CompanyType}。
 *
 * <p>为什么是规则而不是 LLM：公司分类是「公司创建时一次」的低频确定性任务，
 * 规则词典零成本、可解释、可单测；且实际库里的头部公司（运营商/银行/华为系/
 * 研究所系）名字里就带强信号，规则全命中。误判的尾部公司通过
 * {@code PATCH /api/companies/{id}} 手动纠正，纠正后分类器不会再覆盖
 * （只在该字段仍为 OTHER 时才回填）。
 *
 * <p>规则顺序即优先级——广匹配的规则必须放后面：
 * <ol>
 *   <li>「中国电信/移动」以「中国」开头，但必须先命中运营商；</li>
 *   <li>「中国工商银行」以「中国」开头，必须先命中银行；</li>
 *   <li>「中国航天科技集团第五研究院」以「中国」开头，必须先命中研究所。</li>
 * </ol>
 * 所以「中国/国家」前缀这个最宽的信号排在最后兜底。
 */
public final class CompanyTypeClassifier {

    /** 规则表：类型 → 关键词组（命中任一即归类）。匹配一律小写化后 contains */
    private static final List<Map.Entry<CompanyType, String[]>> RULES = List.of(
            // 运营商：秋招大户，单列一类（先于央企——三大运营商都是「中国」开头）
            Map.entry(CompanyType.OPERATOR, new String[]{
                    "中国移动", "中国联通", "中国电信", "中国铁塔", "中移动", "中联通", "中电信", "天翼"}),
            // 银行及软开中心（先于央企——国有大行都是「中国」开头）
            // 「基金」归入金融桶：秋招语境下基金公司（兴全/易方达等）与银行同属金融机构
            Map.entry(CompanyType.BANK, new String[]{
                    "银行", "农信", "银联", "邮储", "基金"}),
            // 军工/科研院所（先于央企——「中国航天科技集团第五研究院」这类名字兼有两个信号）
            Map.entry(CompanyType.INSTITUTE, new String[]{
                    "研究所", "研究院", "科学院", "工程院", "设计院",
                    "航空工业", "航天科技", "航天科工", "中国电科", "中电科",
                    "兵器工业", "兵器装备", "中国船舶", "核工业", "工程物理"}),
            // 外企：中文译名 + 英文名（大小写不敏感）
            Map.entry(CompanyType.FOREIGN, new String[]{
                    "微软", "microsoft", "谷歌", "google", "亚马逊", "amazon", "苹果", "apple",
                    "英特尔", "intel", "英伟达", "nvidia", "高通", "qualcomm", "特斯拉", "tesla",
                    "甲骨文", "oracle", "sap", "西门子", "siemens", "博世", "bosch",
                    "思科", "cisco", "戴尔", "dell", "惠普", "索尼", "sony", "三星", "samsung",
                    "爱立信", "ericsson", "诺基亚", "nokia", "发那科", "fanuc", "虾皮", "shopee",
                    "ibm", "meta", "facebook", "联合利华", "宝洁", "欧莱雅", "雀巢",
                    "丰田", "本田", "大众汽车", "宝马", "奔驰",
                    "麦肯锡", "波士顿咨询", "贝恩", "高盛", "摩根士丹利", "摩根大通",
                    "花旗", "汇丰", "渣打", "埃森哲", "accenture",
                    "德勤", "普华永道", "安永", "毕马威"}),
            // 互联网/科技大厂（本枚举无「民企」桶，科技民企归此）
            Map.entry(CompanyType.INTERNET, new String[]{
                    "阿里巴巴", "阿里", "蚂蚁", "淘天", "淘宝", "天猫", "支付宝",
                    "腾讯", "字节", "抖音", "百度", "京东", "美团", "滴滴", "快手", "网易",
                    "拼多多", "小米", "华为", "荣耀", "oppo", "vivo",
                    "携程", "去哪儿", "哔哩哔哩", "bilibili", "小红书",
                    "蔚来", "理想汽车", "小鹏", "大疆", "海康威视", "大华股份",
                    "科大讯飞", "商汤", "智谱", "月之暗面", "百川", "minimax", "深度求索", "deepseek",
                    "寒武纪", "地平线", "昆仑万维", "贝壳", "链家", "得物",
                    "米哈游", "鹰角", "莉莉丝", "奇安信", "金山", "用友", "金蝶",
                    "联想", "lenovo", "汽车之家", "车之家",
                    "新浪", "微博", "搜狐", "知乎"}),
            // 地方国企：市政/公用事业命名模式
            Map.entry(CompanyType.SOE_LOCAL, new String[]{
                    "城投", "交投", "地铁", "燃气", "水务", "自来水", "公交",
                    "文旅集团", "城建", "轨道交通", "机场集团", "港口集团", "高速集团"}),
            // 央企：最宽的信号放最后——「中国/国家」开头 + 知名简称
            Map.entry(CompanyType.SOE_CENTRAL, new String[]{
                    "中石油", "中石化", "中海油", "国家电网", "南方电网",
                    "华润", "招商局", "保利", "中粮", "中铁", "中建", "中交", "中车",
                    "中化", "五矿", "宝武", "三峡", "华能", "大唐", "华电",
                    "中核", "中广核", "东方航空", "南方航空", "中国国航"}));

    private CompanyTypeClassifier() {
    }

    /**
     * 分类公司名。命中第一条规则的类别；全部未命中返回 {@link CompanyType#OTHER}。
     * 前缀规则（「中国」「国家」开头）在关键词全部未命中后单独判断，避免抢先截胡
     * 运营商/银行/研究所的「中国X」名字。
     */
    public static CompanyType classify(String companyName) {
        if (companyName == null || companyName.isBlank()) {
            return CompanyType.OTHER;
        }
        String name = companyName.trim().toLowerCase(Locale.ROOT);
        for (var rule : RULES) {
            for (String keyword : rule.getValue()) {
                if (name.contains(keyword)) {
                    return rule.getKey();
                }
            }
        }
        if (name.startsWith("中国") || name.startsWith("国家")) {
            return CompanyType.SOE_CENTRAL;
        }
        return CompanyType.OTHER;
    }
}
