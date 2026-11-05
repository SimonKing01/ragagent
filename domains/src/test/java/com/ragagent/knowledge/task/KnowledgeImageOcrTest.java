package com.ragagent.knowledge.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ragagent.knowledge.config.KnowledgeOcrProperties;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.span.SpanTracker;
import com.ragagent.knowledge.storage.TenantFileStorage;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class KnowledgeImageOcrTest {

    private static final long TENANT_ID = 11L;
    private static final String IMAGE_REF = "local://11/exports/page-1.jpg";

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void insertsRecognizedTextAfterImage() throws Exception {
        startServer(200, "{\"text\":\"第一行\\n第二行\"}");
        TenantFileStorage fileStorage = mock(TenantFileStorage.class);
        when(fileStorage.read(TENANT_ID, IMAGE_REF))
                .thenReturn("jpeg-bytes".getBytes(StandardCharsets.UTF_8));

        String markdown = "![page](local://11/exports/page-1.jpg)\n\n正文";
        String result = new KnowledgeImageOcr(fileStorage, properties(serverUrl()), mock(SpanTracker.class))
                .enrich(knowledge(), markdown, 0, "kid");

        assertThat(result).contains("第一行\n第二行");
        assertThat(result).contains("（图内文字）");
        assertThat(result.indexOf("第一行")).isGreaterThan(result.indexOf(IMAGE_REF));
        assertThat(result).contains("正文");
    }

    @Test
    void keepsContentWhenServiceFails() throws Exception {
        startServer(500, "{\"error\":\"boom\"}");
        TenantFileStorage fileStorage = mock(TenantFileStorage.class);
        when(fileStorage.read(TENANT_ID, IMAGE_REF))
                .thenReturn("jpeg-bytes".getBytes(StandardCharsets.UTF_8));

        String markdown = "![page](local://11/exports/page-1.jpg)";
        String result = new KnowledgeImageOcr(fileStorage, properties(serverUrl()), mock(SpanTracker.class))
                .enrich(knowledge(), markdown, 0, "kid");

        assertThat(result).isEqualTo(markdown);
    }

    @Test
    void skipsEverythingWhenKnowledgeBaseSwitchOff() throws Exception {
        // 服务已配置，但知识库关掉了「图片 OCR」：整篇跳过，不读图、不请求
        TenantFileStorage fileStorage = mock(TenantFileStorage.class);
        String markdown = "![page](local://11/exports/page-1.jpg)";

        // 开关关闭时在发请求前就返回，所以不需要真起 OCR 服务
        String result = new KnowledgeImageOcr(fileStorage, properties("http://127.0.0.1:9"), mock(SpanTracker.class))
                .enrich(knowledge(), markdown, 0, "kid", false);

        assertThat(result).isEqualTo(markdown);
        verify(fileStorage, never()).read(TENANT_ID, IMAGE_REF);
    }

    @Test
    void skipsEverythingWhenEndpointMissing() {
        TenantFileStorage fileStorage = mock(TenantFileStorage.class);
        String markdown = "![page](local://11/exports/page-1.jpg)";

        String result = new KnowledgeImageOcr(fileStorage, properties(""), mock(SpanTracker.class))
                .enrich(knowledge(), markdown, 0, "kid");

        assertThat(result).isEqualTo(markdown);
        verify(fileStorage, never()).read(TENANT_ID, IMAGE_REF);
    }

    private void startServer(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        server.createContext("/ocr", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
    }

    private String serverUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static KnowledgeOcrProperties properties(String endpoint) {
        return new KnowledgeOcrProperties(endpoint, 30, 5, 1, null);
    }

    private static Knowledge knowledge() {
        Knowledge k = new Knowledge();
        k.setId("kid");
        k.setTenantId(TENANT_ID);
        return k;
    }
}
