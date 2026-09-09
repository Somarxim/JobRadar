package com.jobradar.core.crawl;

import com.jobradar.core.domain.CrawlSource;
import org.jsoup.Connection;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PageFetcher 请求构造（buildRequest 是纯函数，不触网可直测）：
 * meta.request 的 method/contentType/params/headers 与 pagination 页码注入。
 */
class PageFetcherTest {

    private static CrawlSource source(String meta) {
        CrawlSource s = new CrawlSource();
        s.setName("t");
        s.setUrl("https://api.example.com/jobs");
        s.setMeta(meta);
        return s;
    }

    @Test
    void noRequestSectionMeansPlainGet() {
        Connection conn = PageFetcher.buildRequest(source(null), -1);
        assertThat(conn.request().method()).isEqualTo(Connection.Method.GET);
        assertThat(conn.request().url().toString()).isEqualTo("https://api.example.com/jobs");
    }

    @Test
    void postFormInjectsParamsAndPage() {
        var s = source("""
                {"request":{"method":"POST","contentType":"form",
                             "headers":{"Origin":"https://www.nowcoder.com"},
                             "params":{"query":"大模型","recruitType":"1"}},
                 "pagination":{"pageParam":"page","start":1,"pages":3}}
                """);
        Connection conn = PageFetcher.buildRequest(s, 2);

        assertThat(conn.request().method()).isEqualTo(Connection.Method.POST);
        assertThat(conn.request().ignoreContentType()).isTrue(); // JSON 响应必须放开 content-type 白名单
        assertThat(conn.request().headers().get("Origin")).isEqualTo("https://www.nowcoder.com");
        var data = conn.request().data();
        assertThat(data).anyMatch(kv -> kv.key().equals("query") && kv.value().equals("大模型"));
        assertThat(data).anyMatch(kv -> kv.key().equals("recruitType") && kv.value().equals("1"));
        assertThat(data).anyMatch(kv -> kv.key().equals("page") && kv.value().equals("2"));
    }

    @Test
    void postJsonBuildsRequestBody() {
        var s = source("""
                {"request":{"method":"POST","contentType":"json","params":{"keyword":"开发"}},
                 "pagination":{"start":1,"pages":2}}
                """);
        Connection conn = PageFetcher.buildRequest(s, 1);
        assertThat(conn.request().requestBody()).contains("\"keyword\":\"开发\"")
                .contains("\"page\":1"); // pageParam 缺省为 page
    }

    @Test
    void staticPageWithPaginationAppendsQueryParam() {
        var s = source("{\"pagination\":{\"pageParam\":\"p\",\"start\":1,\"pages\":2}}");
        Connection conn = PageFetcher.buildRequest(s, 3);
        assertThat(conn.request().method()).isEqualTo(Connection.Method.GET);
        // Jsoup GET 的 data() 在执行时才拼进 URL，这里直接断言键值对
        assertThat(conn.request().data()).anyMatch(kv -> kv.key().equals("p") && kv.value().equals("3"));
    }
}
