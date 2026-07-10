package com.shuhuayv.rag.vector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shuhuayv.rag.vector.service.impl.QdrantVectorServiceImpl;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.Proxy;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QdrantVectorServiceImpl 纯单元测试（Mock RestClient，无真实 Qdrant）。
 *
 * <p>重点验证 ensureCollection 对已存在 Collection 的维度/距离一致性校验，
 * 以及 getVectorSize 读取维度。</p>
 */
class QdrantVectorServiceImplTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RestClient mockRestClientReturning(String body) {
        RestClient restClient = mock(RestClient.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(restClient.get().uri(anyString()).retrieve().body(String.class)).thenReturn(body);
        return restClient;
    }

    private RestClient mockRestClientThrowing() {
        RestClient restClient = mock(RestClient.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(restClient.get().uri(anyString()).retrieve().body(String.class))
                .thenThrow(new RuntimeException("404 Not Found"));
        return restClient;
    }

    @Test
    void shouldNotThrowWhenExistingCollectionMatchesDimension() {
        String body = "{\"result\":{\"config\":{\"params\":{\"vectors\":{\"size\":1024,\"distance\":\"Cosine\"}}}}}";
        QdrantVectorServiceImpl service = new QdrantVectorServiceImpl(mockRestClientReturning(body), objectMapper);

        // 不抛异常即通过
        service.ensureCollection("kb_chunks_zhipu_embedding_3_1024_v1", 1024);
    }

    @Test
    void shouldThrowWhenExistingCollectionDimensionMismatch() {
        String body = "{\"result\":{\"config\":{\"params\":{\"vectors\":{\"size\":384,\"distance\":\"Cosine\"}}}}}";
        QdrantVectorServiceImpl service = new QdrantVectorServiceImpl(mockRestClientReturning(body), objectMapper);

        assertThatThrownBy(() -> service.ensureCollection("kb_chunks_zhipu_embedding_3_1024_v1", 1024))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("向量维度不一致");
    }

    @Test
    void shouldThrowWhenExistingCollectionDistanceMismatch() {
        String body = "{\"result\":{\"config\":{\"params\":{\"vectors\":{\"size\":1024,\"distance\":\"Euclid\"}}}}}";
        QdrantVectorServiceImpl service = new QdrantVectorServiceImpl(mockRestClientReturning(body), objectMapper);

        assertThatThrownBy(() -> service.ensureCollection("kb_chunks_zhipu_embedding_3_1024_v1", 1024))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("距离度量不一致");
    }

    @Test
    void shouldCreateCollectionWhenNotExists() {
        QdrantVectorServiceImpl service = new QdrantVectorServiceImpl(mockRestClientThrowing(), objectMapper);

        // GET 抛异常 → 视为不存在 → 创建路径（Mock RestClient PUT 静默成功），不抛异常
        service.ensureCollection("kb_chunks", 384);
    }

    @Test
    void shouldReadVectorSize() {
        String body = "{\"result\":{\"config\":{\"params\":{\"vectors\":{\"size\":1024,\"distance\":\"Cosine\"}}}}}";
        QdrantVectorServiceImpl service = new QdrantVectorServiceImpl(mockRestClientReturning(body), objectMapper);

        assertThat(service.getVectorSize("kb_chunks_zhipu_embedding_3_1024_v1")).isEqualTo(1024);
    }

    // ---------- 错误文案精确化（情况 F：Qdrant 在线但拒绝请求时不应误报“Qdrant 不可用”）----------

    private MockWebServer server;

    @BeforeEach
    void startServer() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stopServer() throws Exception {
        server.shutdown();
    }

    /**
     * 绕过环境 HTTP 代理（HTTP_PROXY 可能拦截 localhost/MockWebServer 请求导致 502），
     * 强制 RestClient 直连，确保单测不依赖真实网络或代理配置。
     */
    private RestClient proxyBypassRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setProxy(Proxy.NO_PROXY);
        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    /**
     * 构造指向给定 baseUrl 的 service。生产代码的 {@code upsertPoint}/{@code ensureCollection}
     * 通过 {@code baseUrl()}（读取 {@code qdrantUrl/host/port} 字段）拼装完整 URL；非 Spring 注入时
     * 这些字段默认 null/0，故通过反射注入 {@code qdrantUrl} 使其指向 MockWebServer，保持生产改动最小。
     */
    private QdrantVectorServiceImpl serviceWithBaseUrl(String baseUrl) throws Exception {
        QdrantVectorServiceImpl svc = new QdrantVectorServiceImpl(proxyBypassRestClient(), objectMapper);
        var field = QdrantVectorServiceImpl.class.getDeclaredField("qdrantUrl");
        field.setAccessible(true);
        field.set(svc, baseUrl);
        return svc;
    }

    @Test
    void shouldReportQdrantRejectedErrorFor400NotUnavailable() throws Exception {
        // Qdrant 在线但拒绝非法 point ID（400），应精确报“Qdrant 返回错误（HTTP 400）”而非“Qdrant 不可用”
        server.enqueue(new MockResponse()
                .setResponseCode(400)
                .setBody("{\"status\":{\"error\":\"Bad Request: value 2_3_v1 is not a valid point ID\"}}"));

        QdrantVectorServiceImpl svc = serviceWithBaseUrl(server.url("/").toString().replaceAll("/$", ""));

        assertThatThrownBy(() -> svc.upsertPoint("col", "2_3_v1", List.of(0.1f, 0.2f), Map.of()))
                .isInstanceOf(RuntimeException.class)
                .satisfies(e -> {
                    String msg = e.getMessage();
                    assertThat(msg).contains("Qdrant 返回错误（HTTP 400");
                    assertThat(msg).doesNotContain("Qdrant 不可用");
                });
    }

    @Test
    void shouldReportUnavailableOnConnectionRefused() throws Exception {
        // 指向不可达端口，模拟“Qdrant 不可用”（连接拒绝），应报“Qdrant 不可用”
        QdrantVectorServiceImpl svc = serviceWithBaseUrl("http://127.0.0.1:1");

        assertThatThrownBy(() -> svc.upsertPoint("col", "2_3_v1", List.of(0.1f, 0.2f), Map.of()))
                .isInstanceOf(RuntimeException.class)
                .satisfies(e -> {
                    String msg = e.getMessage();
                    assertThat(msg).contains("Qdrant 不可用");
                });
    }
}
