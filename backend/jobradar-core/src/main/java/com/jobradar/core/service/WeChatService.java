package com.jobradar.core.service;

import com.jobradar.core.crawl.PageFetcher;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微信公众号收藏机器人（明文被动回复模式）。
 *
 * <p>消息流：用户把岗位文章/截图/文字发给公众号 → 微信服务器 POST 到
 * /api/wechat/callback → 本服务校验签名 → 立即回复「解析中」（微信要求 5s 内响应，
 * LLM 解析 2-10s 不能同步等）→ 异步走 ingest 管线 → 结果暂存 →
 * 用户回复任意短消息时下发结果。
 *
 * <p>个人订阅号无「客服消息」主动推送权限（需企业认证），故用「暂存+回查」模式；
 * 若未来认证升级，结果下发可无缝切换为客服消息推送。
 *
 * <p>安全：所有请求必须过 SHA1 签名校验（token 只有服务端和微信后台知道）；
 * XML 解析禁用外部实体（XXE 防护）。
 */
@Service
public class WeChatService {

    private static final Logger log = LoggerFactory.getLogger(WeChatService.class);

    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s<>\"']+");
    /** 结果暂存有效期（毫秒）：超过视为过期，回查时提示重新发送 */
    private static final long RESULT_TTL_MS = 30 * 60 * 1000;
    /** 「查询结果」短消息的最大长度（「1」「结果」这类），长文本视为新 JD 收藏 */
    private static final int QUERY_MAX_LEN = 10;

    private final JobService jobService;
    private final PageFetcher pageFetcher;
    private final String token;
    private final String webBaseUrl;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "wechat-ingest");
        t.setDaemon(true);
        return t;
    });

    /** userOpenId → 最近一次解析结果（含错误） */
    private final Map<String, StoredResult> results = new ConcurrentHashMap<>();
    /** MsgId 去重（微信超时重发同一消息） */
    private final Map<Long, Long> seenMsgIds = new ConcurrentHashMap<>();

    private record StoredResult(String text, long at) {
    }

    public WeChatService(JobService jobService, PageFetcher pageFetcher,
                         org.springframework.core.env.Environment env) {
        this.jobService = jobService;
        this.pageFetcher = pageFetcher;
        this.token = env.getProperty("jobradar.wechat.token", "");
        this.webBaseUrl = env.getProperty("jobradar.wechat.web-base-url", "");
    }

    public boolean configured() {
        return token != null && !token.isBlank();
    }

    /** 微信签名验证：SHA1(sort(token, timestamp, nonce) 拼接) == signature */
    public boolean verifySignature(String signature, String timestamp, String nonce) {
        if (signature == null || timestamp == null || nonce == null || token == null || token.isBlank()) {
            return false;
        }
        try {
            String[] arr = {token, timestamp, nonce};
            Arrays.sort(arr);
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest(String.join("", arr).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString().equals(signature);
        } catch (Exception e) {
            return false;
        }
    }

    /** 处理一条消息，返回被动回复 XML */
    public String handleMessage(String xmlBody) {
        try {
            WeChatMessage msg = parseXml(xmlBody);
            String user = msg.get("FromUserName");
            String bot = msg.get("ToUserName");
            if (user == null || bot == null) {
                return replyXml(user, bot, "消息格式无法识别");
            }
            String msgType = msg.get("MsgType");

            // 订阅事件：欢迎语
            if ("event".equals(msgType) && "subscribe".equals(msg.get("Event"))) {
                return replyXml(user, bot, usageText());
            }

            // 清理过期结果
            results.entrySet().removeIf(e -> Instant.now().toEpochMilli() - e.getValue().at() > RESULT_TTL_MS);

            // 短消息 + 有暂存结果 → 回查结果（而非当作新 JD 收藏）
            String content = msg.get("Content") == null ? "" : msg.get("Content").trim();
            StoredResult stored = results.remove(user);
            if (stored != null && content.length() <= QUERY_MAX_LEN && !"image".equals(msgType) && !"link".equals(msgType)) {
                return replyXml(user, bot, stored.text());
            }

            // 消息去重（微信超时重发）
            Long msgId = msg.msgId();
            if (msgId != null && seenMsgIds.putIfAbsent(msgId, Instant.now().toEpochMilli()) != null) {
                return replyXml(user, bot, "该消息正在解析中，回复任意短消息查看结果");
            }

            // 按类型分发异步解析
            switch (msgType == null ? "" : msgType) {
                case "text" -> dispatch(user, msg, () -> ingestText(user, content));
                case "link" -> dispatch(user, msg, () -> ingestUrl(user, msg.get("Url")));
                case "image" -> dispatch(user, msg, () -> ingestImage(user, msg.get("PicUrl")));
                case "event" -> {
                    return replyXml(user, bot, usageText());
                }
                default -> {
                    return replyXml(user, bot, "目前只支持：文字 JD、文章链接、JD 截图三种收藏方式\n\n" + usageText());
                }
            }
            return replyXml(user, bot, "已收到，解析中（约 5-15 秒）…\n回复任意短消息（如「1」）查看结果");
        } catch (Exception e) {
            log.warn("微信消息处理失败: {}", e.getMessage());
            return null; // 返回空串让微信不再重试
        }
    }

    private void dispatch(String user, WeChatMessage msg, Runnable task) {
        executor.submit(() -> {
            try {
                task.run();
            } catch (Exception e) {
                log.warn("微信解析任务失败: {}", e.getMessage());
                results.put(user, new StoredResult("❌ 解析失败：" + e.getMessage(), Instant.now().toEpochMilli()));
            }
        });
    }

    private void ingestText(String user, String text) {
        // 文本中含 URL 时按链接模式走（用户直接粘贴了带链接的文字）
        Matcher m = URL_PATTERN.matcher(text);
        if (m.find()) {
            ingestUrl(user, m.group());
            return;
        }
        var res = jobService.ingest(new com.jobradar.core.dto.JobDtos.IngestRequest(
                "wechat", null, text, null, null, null, new com.jobradar.core.dto.JobDtos.IngestRequest.Hints(
                null, null, null, null, null)));
        results.put(user, new StoredResult(successText(res.jobId(), res.alreadyExists()),
                Instant.now().toEpochMilli()));
    }

    private void ingestUrl(String user, String url) {
        var res = jobService.ingest(new com.jobradar.core.dto.JobDtos.IngestRequest(
                "wechat", url, null, null, null, null, new com.jobradar.core.dto.JobDtos.IngestRequest.Hints(
                null, null, null, null, null)));
        results.put(user, new StoredResult(successText(res.jobId(), res.alreadyExists()),
                Instant.now().toEpochMilli()));
    }

    private void ingestImage(String user, String picUrl) {
        byte[] bytes;
        try {
            bytes = Jsoup.connect(picUrl).ignoreContentType(true).timeout(10_000).execute().bodyAsBytes();
        } catch (Exception e) {
            results.put(user, new StoredResult("❌ 图片下载失败，请改用截图上传到 " + webBaseUrl + "/m",
                    Instant.now().toEpochMilli()));
            return;
        }
        String base64 = java.util.Base64.getEncoder().encodeToString(bytes);
        var res = jobService.ingest(new com.jobradar.core.dto.JobDtos.IngestRequest(
                "wechat", null, null, null, base64, "image/jpeg",
                new com.jobradar.core.dto.JobDtos.IngestRequest.Hints(null, null, null, null, null)));
        results.put(user, new StoredResult(successText(res.jobId(), res.alreadyExists()),
                Instant.now().toEpochMilli()));
    }

    private String successText(Long jobId, boolean alreadyExists) {
        StringBuilder sb = new StringBuilder();
        sb.append(alreadyExists ? "ℹ️ 该岗位此前已收藏过\n" : "✅ 已收藏\n");
        if (webBaseUrl != null && !webBaseUrl.isBlank()) {
            sb.append("查看：").append(webBaseUrl).append("/jobs/").append(jobId);
        }
        return sb.toString();
    }

    private String usageText() {
        return "发给我以下内容即可收藏岗位：\n"
                + "1️⃣ JD 文字（长按文章复制后直接发）\n"
                + "2️⃣ 岗位文章链接\n"
                + "3️⃣ JD 截图（无法复制的页面用截图）\n\n"
                + "解析完成后回复任意短消息（如「1」）查看结果";
    }

    /** 被动回复文本消息 XML */
    static String replyXml(String toUser, String fromUser, String content) {
        if (toUser == null || fromUser == null || content == null) {
            return "";
        }
        return "<xml>"
                + "<ToUserName><![CDATA[" + toUser + "]]></ToUserName>"
                + "<FromUserName><![CDATA[" + fromUser + "]]></FromUserName>"
                + "<CreateTime>" + Instant.now().getEpochSecond() + "</CreateTime>"
                + "<MsgType><![CDATA[text]]></MsgType>"
                + "<Content><![CDATA[" + content + "]]></Content>"
                + "</xml>";
    }

    /** 解析微信消息 XML（禁用外部实体防 XXE） */
    static WeChatMessage parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);
        Document doc = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        WeChatMessage msg = new WeChatMessage();
        NodeList children = doc.getDocumentElement().getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element el) {
                msg.put(el.getTagName(), el.getTextContent());
            }
        }
        return msg;
    }

    /** 微信消息的简单封装 */
    static final class WeChatMessage extends java.util.HashMap<String, String> {
        Long msgId() {
            String id = get("MsgId");
            if (id != null) {
                try {
                    return Long.parseLong(id);
                } catch (NumberFormatException ignored) {
                }
            }
            return null;
        }
    }
}
