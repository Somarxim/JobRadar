package com.jobradar.app.web;

import com.jobradar.core.service.WeChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 微信公众号服务器回调（/api/wechat/callback）。
 *
 * <p>公开端点（SecurityConfig 显式放行），真实安全性由微信 SHA1 签名保障：
 * 只有知道 token 的微信后台和我们自己能算出正确签名——伪造请求过不了校验。
 * 注意：此路径同时在 LocalTokenFilter 的豁免清单里（微信服务器没有 Origin/令牌）。
 */
@RestController
@RequestMapping("/api/wechat")
public class WeChatController {

    private static final Logger log = LoggerFactory.getLogger(WeChatController.class);

    private final WeChatService weChatService;

    public WeChatController(WeChatService weChatService) {
        this.weChatService = weChatService;
    }

    /** 微信后台「启用服务器配置」时的 URL 可达性验证：原样回显 echostr */
    @GetMapping("/callback")
    public ResponseEntity<String> verify(@RequestParam(required = false) String signature,
                                         @RequestParam(required = false) String timestamp,
                                         @RequestParam(required = false) String nonce,
                                         @RequestParam(required = false) String echostr) {
        if (!weChatService.configured()) {
            return ResponseEntity.status(503).body("wechat not configured");
        }
        if (weChatService.verifySignature(signature, timestamp, nonce)) {
            log.info("微信回调 URL 验证通过");
            return ResponseEntity.ok(echostr == null ? "" : echostr);
        }
        log.warn("微信回调 URL 验证失败：签名不符");
        return ResponseEntity.status(403).body("invalid signature");
    }

    /** 接收用户消息（文本/链接/图片），被动回复 XML */
    @PostMapping(value = "/callback",
            consumes = {MediaType.TEXT_XML_VALUE, MediaType.APPLICATION_XML_VALUE, MediaType.ALL_VALUE},
            produces = MediaType.TEXT_XML_VALUE)
    public ResponseEntity<String> onMessage(@RequestBody String body,
                                            @RequestParam(required = false) String signature,
                                            @RequestParam(required = false) String timestamp,
                                            @RequestParam(required = false) String nonce) {
        if (!weChatService.verifySignature(signature, timestamp, nonce)) {
            log.warn("微信消息签名校验失败，已丢弃");
            return ResponseEntity.status(403).body("invalid signature");
        }
        String reply = weChatService.handleMessage(body);
        // 微信约定：空 body 或 "success" 表示已处理，不再重试
        return ResponseEntity.ok(reply == null || reply.isBlank() ? "" : reply);
    }
}
