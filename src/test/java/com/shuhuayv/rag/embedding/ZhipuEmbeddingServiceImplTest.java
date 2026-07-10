package com.shuhuayv.rag.embedding;

import com.shuhuayv.rag.embedding.service.EmbeddingMode;
import com.shuhuayv.rag.embedding.service.impl.ZhipuEmbeddingServiceImpl;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ZhipuEmbeddingServiceImpl 纯单元测试（MockWebServer 模拟真实 API，无真实 Key / 无真实网络）。
 */
class ZhipuEmbeddingServiceImplTest {

    private MockWebServer server;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private RestClient restClient() {
        return RestClient.builder()
                .baseUrl(server.url("/").toString())
                .defaultHeader("Authorization", "Bearer test-key")
                .build();
    }

    private String successBody(int count, int dims) {
        StringBuilder sb = new StringBuilder("{\"data\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"index\":").append(i).append(",\"embedding\":[");
            for (int j = 0; j < dims; j++) {
                if (j > 0) sb.append(',');
                sb.append("0.01");
            }
            sb.append("]}");
        }
        sb.append("],\"model\":\"embedding-3\"}");
        return sb.toString();
    }

    private ZhipuEmbeddingServiceImpl service(int dims, int batchSize, int maxRetries) {
        return new ZhipuEmbeddingServiceImpl(restClient(), "embedding-3", dims, batchSize, maxRetries);
    }

    // 使用非 embedding-3 的模型名构造，绕过 embedding-3 维度白名单，便于对任意维度做响应解析校验
    private ZhipuEmbeddingServiceImpl serviceWithModel(String model, int dims, int batchSize, int maxRetries) {
        return new ZhipuEmbeddingServiceImpl(restClient(), model, dims, batchSize, maxRetries);
    }

    private void enqueue200(String body) {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(body));
    }

    @Test
    void shouldCallEmbeddingsEndpointWithBearerAuth() throws Exception {
        enqueue200(successBody(1, 1024));
        ZhipuEmbeddingServiceImpl s = service(1024, 16, 2);
        s.embed("hello");

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/embeddings");
        assertThat(req.getHeader("Authorization")).isEqualTo("Bearer test-key");
    }

    @Test
    void shouldSendModelAndInputInRequestBody() throws Exception {
        enqueue200(successBody(1, 1024));
        ZhipuEmbeddingServiceImpl s = service(1024, 16, 2);
        s.embed("hello");

        RecordedRequest req = server.takeRequest();
        String body = req.getBody().readUtf8();
        assertThat(body).contains("embedding-3");
        assertThat(body).contains("input");
        assertThat(body).contains("hello");
    }

    @Test
    void shouldReturn1024DimVectorAndExposeMetadata() {
        enqueue200(successBody(1, 1024));
        ZhipuEmbeddingServiceImpl s = service(1024, 16, 2);

        List<Float> v = s.embed("hello");
        assertThat(v).hasSize(1024);
        assertThat(s.provider()).isEqualTo("zhipu");
        assertThat(s.model()).isEqualTo("embedding-3");
        assertThat(s.dimensions()).isEqualTo(1024);
        assertThat(s.mode()).isEqualTo(EmbeddingMode.REAL);
        assertThat(s.real()).isTrue();
    }

    @Test
    void shouldSplitIntoBatchesWhenExceedingBatchSize() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json").setBody(successBody(16, 1024)));
        server.enqueue(new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json").setBody(successBody(4, 1024)));

        ZhipuEmbeddingServiceImpl s = service(1024, 16, 2);
        List<String> inputs = new ArrayList<>();
        for (int i = 0; i < 20; i++) inputs.add("t" + i);
        List<List<Float>> result = s.embedBatch(inputs);

        assertThat(result).hasSize(20);
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void shouldRestoreOrderByDataIndex() {
        String body = "{\"data\":["
                + "{\"index\":1,\"embedding\":[0.3,0.4]},"
                + "{\"index\":0,\"embedding\":[0.1,0.2]}"
                + "],\"model\":\"embedding-3\"}";
        enqueue200(body);
        ZhipuEmbeddingServiceImpl s = serviceWithModel("parse-test-model", 2, 16, 2);

        List<List<Float>> result = s.embedBatch(List.of("a", "b"));
        assertThat(result).hasSize(2);
        assertThat(result.get(0)).containsExactly(0.1f, 0.2f);
        assertThat(result.get(1)).containsExactly(0.3f, 0.4f);
    }

    @Test
    void shouldThrowWhenDataCountMismatch() {
        enqueue200("{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2]}]}");
        ZhipuEmbeddingServiceImpl s = serviceWithModel("parse-test-model", 2, 16, 2);
        assertThatThrownBy(() -> s.embedBatch(List.of("a", "b")))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void shouldThrowWhenIndexDuplicate() {
        enqueue200("{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2]},"
                + "{\"index\":0,\"embedding\":[0.3,0.4]}]}");
        ZhipuEmbeddingServiceImpl s = serviceWithModel("parse-test-model", 2, 16, 2);
        assertThatThrownBy(() -> s.embedBatch(List.of("a", "b")))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void shouldThrowWhenIndexOutOfRange() {
        enqueue200("{\"data\":[{\"index\":5,\"embedding\":[0.1,0.2]}]}");
        ZhipuEmbeddingServiceImpl s = serviceWithModel("parse-test-model", 2, 16, 2);
        assertThatThrownBy(() -> s.embedBatch(List.of("a", "b")))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void shouldThrowWhenEmbeddingEmpty() {
        enqueue200("{\"data\":[{\"index\":0}]}");
        ZhipuEmbeddingServiceImpl s = serviceWithModel("parse-test-model", 2, 16, 2);
        assertThatThrownBy(() -> s.embedBatch(List.of("a", "b")))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void shouldThrowWhenDimensionMismatch() {
        enqueue200("{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2,0.3]}]}");
        ZhipuEmbeddingServiceImpl s = serviceWithModel("parse-test-model", 2, 16, 2);
        assertThatThrownBy(() -> s.embedBatch(List.of("a", "b")))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void shouldThrowWhenEmbeddingContainsNaN() {
        enqueue200("{\"data\":[{\"index\":0,\"embedding\":[0.1,NaN]}]}");
        ZhipuEmbeddingServiceImpl s = serviceWithModel("parse-test-model", 2, 16, 2);
        assertThatThrownBy(() -> s.embedBatch(List.of("a", "b")))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void shouldThrowWhenEmbeddingContainsInfinity() {
        enqueue200("{\"data\":[{\"index\":0,\"embedding\":[0.1,Infinity]}]}");
        ZhipuEmbeddingServiceImpl s = serviceWithModel("parse-test-model", 2, 16, 2);
        assertThatThrownBy(() -> s.embedBatch(List.of("a", "b")))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void shouldThrowWhenRestClientIsNull() {
        assertThatThrownBy(() -> new ZhipuEmbeddingServiceImpl(
                null, "embedding-3", 1024, 16, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldNotRetryOn400() {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("{\"error\":\"bad\"}"));
        ZhipuEmbeddingServiceImpl s = service(1024, 16, 2);
        assertThatThrownBy(() -> s.embed("x")).isInstanceOf(RuntimeException.class);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void shouldNotRetryOn401() {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("{\"error\":\"unauthorized\"}"));
        ZhipuEmbeddingServiceImpl s = service(1024, 16, 2);
        assertThatThrownBy(() -> s.embed("x")).isInstanceOf(RuntimeException.class);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void shouldNotRetryOn403() {
        server.enqueue(new MockResponse().setResponseCode(403).setBody("{\"error\":\"forbidden\"}"));
        ZhipuEmbeddingServiceImpl s = service(1024, 16, 2);
        assertThatThrownBy(() -> s.embed("x")).isInstanceOf(RuntimeException.class);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void shouldRetryOn429ThenSucceed() {
        server.enqueue(new MockResponse().setResponseCode(429).setBody("{\"error\":\"rate\"}"));
        enqueue200(successBody(1, 1024));
        ZhipuEmbeddingServiceImpl s = service(1024, 16, 2);
        List<Float> v = s.embed("x");
        assertThat(v).hasSize(1024);
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void shouldRetryOn500ThenSucceed() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\":\"boom\"}"));
        enqueue200(successBody(1, 1024));
        ZhipuEmbeddingServiceImpl s = service(1024, 16, 2);
        List<Float> v = s.embed("x");
        assertThat(v).hasSize(1024);
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void shouldFailAfterExceedingMaxRetries() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\":\"boom\"}"));
        server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\":\"boom\"}"));
        server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\":\"boom\"}"));
        ZhipuEmbeddingServiceImpl s = service(1024, 16, 2);
        assertThatThrownBy(() -> s.embed("x")).isInstanceOf(RuntimeException.class);
        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    // ---------- §3 RecordedRequest 守门：请求体必须含 dimensions ----------

    @Test
    void shouldSendDimensionsInRequestBody() throws Exception {
        enqueue200(successBody(2, 1024));
        ZhipuEmbeddingServiceImpl s = service(1024, 16, 2);
        List<List<Float>> result = s.embedBatch(List.of("a", "b"));
        assertThat(result).hasSize(2);

        RecordedRequest req = server.takeRequest();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/embeddings");

        String body = req.getBody().readUtf8();
        assertThat(body).contains("dimensions");
        assertThat(body).contains("1024");
        assertThat(body).contains("embedding-3");

        JsonNode bodyJson = objectMapper.readTree(body);
        JsonNode input = bodyJson.get("input");
        assertThat(input).isNotNull();
        assertThat(input.isArray()).isTrue();
        assertThat(input).hasSize(2);
        assertThat(input.get(0).asText()).isEqualTo("a");
    }

    // ---------- §4 严格输入校验 ----------

    @Test
    void shouldRejectNullTextInEmbed() {
        ZhipuEmbeddingServiceImpl s = service(1024, 16, 2);
        assertThatThrownBy(() -> s.embed(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectBlankTextInEmbed() {
        ZhipuEmbeddingServiceImpl s = service(1024, 16, 2);
        assertThatThrownBy(() -> s.embed(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.embed("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullListInEmbedBatch() {
        ZhipuEmbeddingServiceImpl s = service(1024, 16, 2);
        assertThatThrownBy(() -> s.embedBatch(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullElementInEmbedBatch() {
        ZhipuEmbeddingServiceImpl s = service(1024, 16, 2);
        assertThatThrownBy(() -> s.embedBatch(Arrays.asList("a", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1");
    }

    @Test
    void shouldRejectBlankElementInEmbedBatch() {
        ZhipuEmbeddingServiceImpl s = service(1024, 16, 2);
        assertThatThrownBy(() -> s.embedBatch(Arrays.asList("a", "  ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1");
    }

    // ---------- §5 构造期配置校验 ----------

    @Test
    void shouldRejectBatchSizeBelowLowerBound() {
        assertThatThrownBy(() -> new ZhipuEmbeddingServiceImpl(restClient(), "embedding-3", 1024, 0, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectBatchSizeAboveUpperBound() {
        assertThatThrownBy(() -> new ZhipuEmbeddingServiceImpl(restClient(), "embedding-3", 1024, 100, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNegativeMaxRetries() {
        assertThatThrownBy(() -> new ZhipuEmbeddingServiceImpl(restClient(), "embedding-3", 1024, 16, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectDisallowedDimensionsForEmbedding3() {
        assertThatThrownBy(() -> new ZhipuEmbeddingServiceImpl(restClient(), "embedding-3", 768, 16, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- §6 非重试 4xx（404 / 409 / 422）----------

    @Test
    void shouldNotRetryOn404() {
        server.enqueue(new MockResponse().setResponseCode(404)
                .setBody("{\"error\":{\"code\":\"not_found\",\"message\":\"resource missing\"}}"));
        ZhipuEmbeddingServiceImpl s = service(1024, 16, 2);
        assertThatThrownBy(() -> s.embed("x")).isInstanceOf(RuntimeException.class);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void shouldNotRetryOn409() {
        server.enqueue(new MockResponse().setResponseCode(409)
                .setBody("{\"error\":{\"code\":\"conflict\",\"message\":\"conflict detected\"}}"));
        ZhipuEmbeddingServiceImpl s = service(1024, 16, 2);
        assertThatThrownBy(() -> s.embed("x")).isInstanceOf(RuntimeException.class);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void shouldNotRetryOn422() {
        server.enqueue(new MockResponse().setResponseCode(422)
                .setBody("{\"error\":{\"code\":\"unprocessable\",\"message\":\"invalid input\"}}"));
        ZhipuEmbeddingServiceImpl s = service(1024, 16, 2);
        assertThatThrownBy(() -> s.embed("x")).isInstanceOf(RuntimeException.class);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    // ---------- §7 错误信息安全守门：绝不泄露原始 error body / 请求体 / Key ----------

    @Test
    void shouldNotLeakRawBodyOrKeyInErrorMessage() {
        // 唯一标记串只放在 error.message 字段里（message 字段本就允许展示）。
        // 原始 JSON 结构（"error":）与注入的 Key 片段（test-key）绝不应出现在异常信息中。
        String marker = "RAW_BODY_LEAK_MARKER_xyz";
        String leakyBody = "{\"error\":{\"code\":\"unprocessable\","
                + "\"message\":\"detail-contains RAW_BODY_LEAK_MARKER_xyz end\"}}";
        server.enqueue(new MockResponse().setResponseCode(422).setBody(leakyBody));
        ZhipuEmbeddingServiceImpl s = service(1024, 16, 2);

        assertThatThrownBy(() -> s.embed("x"))
                .isInstanceOf(RuntimeException.class)
                .satisfies(e -> {
                    String msg = e.getMessage();
                    // §7 允许：HTTP 状态码 + error.code + error.message
                    assertThat(msg).contains("HTTP 422");
                    assertThat(msg).contains(marker);
                    // §7 禁止：原始 error body 的 JSON 结构
                    assertThat(msg).doesNotContain("\"error\":");
                    // §7 禁止：Authorization / Key 片段（请求体 / 头中的 test-key 不应泄露）
                    assertThat(msg).doesNotContain("Bearer ");
                    assertThat(msg).doesNotContain("test-key");
                });
    }
}
