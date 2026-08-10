package com.shuhuayv.rag.vector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shuhuayv.rag.vector.model.QdrantOperationResult;
import com.shuhuayv.rag.vector.model.ScrollPage;
import com.shuhuayv.rag.vector.service.impl.QdrantVectorServiceImpl;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QDRANT-01 ~ QDRANT-14：Qdrant lifecycle 治理能力的离线契约测试。
 *
 * <p>全部基于 {@link MockWebServer}，不依赖真实 Qdrant，可在无 Qdrant 的 CI 中运行。
 * 测试方法命名统一带 {@code qdrantNN_} 前缀，便于与测试报告对照表逐条比对。</p>
 */
@DisplayName("QDRANT lifecycle 契约（离线）")
class QdrantLifecycleOperationsTest {

    /** 与生产 buildPointId 同源的确定性 UUID v3（documentId:chunkId:indexVersion）。 */
    private static final String UUID_V3 =
            UUID.nameUUIDFromBytes("6:101:v1".getBytes(StandardCharsets.UTF_8)).toString();

    /** 固定的随机型 UUID v4 字面量（第 3 段首字符为 4）。 */
    private static final String UUID_V4 = "1b4e28ba-2fa1-4d3b-a3f5-ccb692a4d2b7";

    private static final String OK_DELETE_BODY =
            "{\"result\":{\"operation_id\":42,\"status\":\"completed\"},\"status\":\"ok\",\"time\":0.001}";

    private final ObjectMapper objectMapper = new ObjectMapper();

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
     * 绕过环境 HTTP 代理，确保 MockWebServer 请求不被代理拦截（与既有测试保持同一风格）。
     */
    private RestClient proxyBypassRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setProxy(Proxy.NO_PROXY);
        return RestClient.builder().requestFactory(factory).build();
    }

    /**
     * 构造指向 MockWebServer 的 service：生产代码通过 {@code baseUrl()} 读取 {@code qdrantUrl}
     * 字段，非 Spring 环境下该字段为 null，故用反射注入，保持生产代码零改动。
     */
    private QdrantVectorServiceImpl service() throws Exception {
        QdrantVectorServiceImpl svc = new QdrantVectorServiceImpl(proxyBypassRestClient(), objectMapper);
        var field = QdrantVectorServiceImpl.class.getDeclaredField("qdrantUrl");
        field.setAccessible(true);
        field.set(svc, server.url("/").toString().replaceAll("/$", ""));
        return svc;
    }

    private void enqueueJson(String body) {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body));
    }

    private JsonNode bodyOf(RecordedRequest request) throws Exception {
        return objectMapper.readTree(request.getBody().readUtf8());
    }

    // ==================== QDRANT-01 / 02 / 03 / 04：deletePoints ID 兼容性 ====================

    @Test
    @DisplayName("QDRANT-01 deletePoints 允许删除 UUID v3（确定性 ID）")
    void qdrant01_deletePointsAllowsUuidV3() throws Exception {
        enqueueJson(OK_DELETE_BODY);

        QdrantOperationResult result = service().deletePoints("col", List.of(UUID_V3), true);

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/collections/col/points/delete?wait=true");

        JsonNode points = bodyOf(request).path("points");
        assertThat(points.isArray()).isTrue();
        assertThat(points).hasSize(1);
        assertThat(points.get(0).asText()).isEqualTo(UUID_V3);
        // 断言确实是 v3：version nibble == 3
        assertThat(UUID.fromString(UUID_V3).version()).isEqualTo(3);
        assertThat(result.operationId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("QDRANT-02 deletePoints 允许删除 UUID v4（随机 ID，无法被 buildPointId 复算）")
    void qdrant02_deletePointsAllowsUuidV4() throws Exception {
        enqueueJson(OK_DELETE_BODY);

        QdrantOperationResult result = service().deletePoints("col", List.of(UUID_V4), true);

        JsonNode points = bodyOf(server.takeRequest()).path("points");
        assertThat(points).hasSize(1);
        assertThat(points.get(0).asText()).isEqualTo(UUID_V4);
        assertThat(UUID.fromString(UUID_V4).version()).isEqualTo(4);
        assertThat(result.status()).isEqualTo("completed");
    }

    @Test
    @DisplayName("QDRANT-03 deletePoints 跳过非法 id，不抛异常、不让整批 400")
    void qdrant03_deletePointsSkipsInvalidIdsWithoutFailingBatch() throws Exception {
        enqueueJson(OK_DELETE_BODY);

        List<String> mixed = new ArrayList<>(Arrays.asList(
                UUID_V3,
                "2_3_v1",        // 历史非法 ID 格式
                null,            // null
                "   ",           // 空白
                "1-1-1-1-1",     // UUID.fromString 会误放行，正则必须拦下
                UUID_V4));

        QdrantOperationResult result = service().deletePoints("col", mixed, true);

        // 只发了一次 HTTP，且请求体里只有两个合法 ID
        assertThat(server.getRequestCount()).isEqualTo(1);
        JsonNode points = bodyOf(server.takeRequest()).path("points");
        assertThat(points).hasSize(2);
        List<String> sent = new ArrayList<>();
        points.forEach(node -> sent.add(node.asText()));
        assertThat(sent).containsExactly(UUID_V3, UUID_V4);
        assertThat(result.isSkipped()).isFalse();
    }

    @Test
    @DisplayName("QDRANT-04 deletePoints 过滤后为空时不发 HTTP")
    void qdrant04_deletePointsSendsNoHttpWhenEmpty() throws Exception {
        QdrantVectorServiceImpl svc = service();

        QdrantOperationResult emptyList = svc.deletePoints("col", List.of(), true);
        QdrantOperationResult nullList = svc.deletePoints("col", null, true);
        QdrantOperationResult allInvalid = svc.deletePoints("col", Arrays.asList(null, "  ", "2_3_v1"), true);

        assertThat(server.getRequestCount()).isZero();
        assertThat(emptyList.isSkipped()).isTrue();
        assertThat(nullList.isSkipped()).isTrue();
        assertThat(allInvalid.isSkipped()).isTrue();
        assertThat(emptyList.operationId()).isNull();
    }

    // ==================== QDRANT-05 / 06：documentId filter 类型契约 ====================

    @Test
    @DisplayName("QDRANT-05 deletePointsByDocumentId 的 filter value 必须是 JSON number")
    void qdrant05_documentIdFilterIsJsonNumberNotString() throws Exception {
        enqueueJson(OK_DELETE_BODY);

        service().deletePointsByDocumentId("col", 6L, true);

        RecordedRequest request = server.takeRequest();
        String raw = request.getBody().readUtf8();
        JsonNode value = objectMapper.readTree(raw)
                .path("filter").path("must").get(0)
                .path("match").path("value");

        assertThat(value.isNumber()).as("filter value 必须是 JSON number").isTrue();
        assertThat(value.isTextual()).as("filter value 绝不能是 JSON string").isFalse();
        assertThat(value.asLong()).isEqualTo(6L);
        assertThat(raw).contains("\"value\":6");
        assertThat(raw).doesNotContain("\"value\":\"6\"");

        JsonNode condition = objectMapper.readTree(raw).path("filter").path("must").get(0);
        assertThat(condition.path("key").asText()).isEqualTo("documentId");
    }

    @Test
    @DisplayName("QDRANT-06 deletePointsByDocumentId(null) 抛 IllegalArgumentException 且 0 次 HTTP")
    void qdrant06_deleteByNullDocumentIdThrowsAndSendsNoHttp() throws Exception {
        QdrantVectorServiceImpl svc = service();

        assertThatThrownBy(() -> svc.deletePointsByDocumentId("col", null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("documentId 不能为空");

        assertThatThrownBy(() -> svc.countPointsByDocumentId("col", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("documentId 不能为空");

        assertThat(server.getRequestCount()).isZero();
    }

    // ==================== QDRANT-07 / 08：count ====================

    @Test
    @DisplayName("QDRANT-07 countPoints 使用 exact=true 并解析 result.count")
    void qdrant07_countPointsExact() throws Exception {
        enqueueJson("{\"result\":{\"count\":14},\"status\":\"ok\",\"time\":0.0002}");

        long count = service().countPoints("col");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/collections/col/points/count");
        assertThat(bodyOf(request).path("exact").asBoolean()).isTrue();
        assertThat(count).isEqualTo(14L);
    }

    @Test
    @DisplayName("QDRANT-08 countPointsByDocumentId 带数值 filter 且 exact=true")
    void qdrant08_countPointsByDocumentId() throws Exception {
        enqueueJson("{\"result\":{\"count\":3},\"status\":\"ok\",\"time\":0.0002}");

        long count = service().countPointsByDocumentId("col", 6L);

        JsonNode body = bodyOf(server.takeRequest());
        assertThat(body.path("exact").asBoolean()).isTrue();
        JsonNode value = body.path("filter").path("must").get(0).path("match").path("value");
        assertThat(value.isNumber()).isTrue();
        assertThat(value.asLong()).isEqualTo(6L);
        assertThat(count).isEqualTo(3L);
    }

    // ==================== QDRANT-09 / 10：scroll ====================

    @Test
    @DisplayName("QDRANT-09 scrollPoints 固定 with_payload=true / with_vector=false")
    void qdrant09_scrollUsesPayloadWithoutVector() throws Exception {
        enqueueJson("{\"result\":{\"points\":[{\"id\":\"" + UUID_V3
                + "\",\"payload\":{\"documentId\":6,\"chunkId\":101,\"content\":\"smoke-test-only\"}}],"
                + "\"next_page_offset\":null},\"status\":\"ok\"}");

        ScrollPage page = service().scrollPoints("col", null, 50);

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/collections/col/points/scroll");
        JsonNode body = bodyOf(request);
        assertThat(body.path("with_payload").asBoolean()).isTrue();
        assertThat(body.path("with_vector").isBoolean()).as("with_vector 必须是布尔字面量").isTrue();
        assertThat(body.path("with_vector").asBoolean()).as("scroll 不得拉回向量").isFalse();
        assertThat(body.path("limit").asInt()).isEqualTo(50);
        assertThat(body.has("offset")).as("offset 为 null 时不应出现在请求体").isFalse();

        assertThat(page.size()).isEqualTo(1);
        assertThat(page.points().get(0).id()).isEqualTo(UUID_V3);
        assertThat(page.points().get(0).payload()).containsEntry("documentId", 6L);
        assertThat(page.hasNext()).isFalse();
        assertThat(page.nextOffset()).isNull();
    }

    @Test
    @DisplayName("QDRANT-10 scrollPoints 支持 pagination 且 limit 有边界校验")
    void qdrant10_scrollPaginationAndLimitBounds() throws Exception {
        enqueueJson("{\"result\":{\"points\":[{\"id\":\"" + UUID_V4 + "\",\"payload\":{}}],"
                + "\"next_page_offset\":\"" + UUID_V3 + "\"},\"status\":\"ok\"}");

        QdrantVectorServiceImpl svc = service();
        ScrollPage first = svc.scrollPoints("col", null, 1);
        assertThat(first.hasNext()).isTrue();
        assertThat(first.nextOffset()).isEqualTo(UUID_V3);
        server.takeRequest();

        // 第二页：把上一页的 nextOffset 回传，必须出现在请求体
        enqueueJson("{\"result\":{\"points\":[],\"next_page_offset\":null},\"status\":\"ok\"}");
        ScrollPage second = svc.scrollPoints("col", first.nextOffset(), 1);
        assertThat(bodyOf(server.takeRequest()).path("offset").asText()).isEqualTo(UUID_V3);
        assertThat(second.hasNext()).isFalse();
        assertThat(second.size()).isZero();

        // limit 边界校验：越界不发 HTTP
        int before = server.getRequestCount();
        assertThatThrownBy(() -> svc.scrollPoints("col", null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scroll limit 越界");
        assertThatThrownBy(() -> svc.scrollPoints("col", null, 1001))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scroll limit 越界");
        assertThat(server.getRequestCount()).isEqualTo(before);
    }

    // ==================== QDRANT-11 / 12：collection 元信息 ====================

    @Test
    @DisplayName("QDRANT-11 collectionExists 只读命中 /exists，true 与 false 均正确")
    void qdrant11_collectionExistsReadOnly() throws Exception {
        enqueueJson("{\"result\":{\"exists\":true},\"status\":\"ok\"}");
        QdrantVectorServiceImpl svc = service();

        assertThat(svc.collectionExists("kb_chunks")).isTrue();
        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).as("必须是只读 GET，不得产生建库写副作用").isEqualTo("GET");
        assertThat(request.getPath()).isEqualTo("/collections/kb_chunks/exists");

        enqueueJson("{\"result\":{\"exists\":false},\"status\":\"ok\"}");
        assertThat(svc.collectionExists("kb_not_there")).isFalse();
        assertThat(server.takeRequest().getMethod()).isEqualTo("GET");
    }

    @Test
    @DisplayName("QDRANT-12 listCollections 前缀过滤正确且结果有序")
    void qdrant12_listCollectionsPrefixFilter() throws Exception {
        String body = "{\"result\":{\"collections\":["
                + "{\"name\":\"kb_chunks\"},"
                + "{\"name\":\"kb_chunks_zhipu_embedding_3_1024_v1\"},"
                + "{\"name\":\"kb_smoke_tmp_1\"},"
                + "{\"name\":\"other_collection\"}]},\"status\":\"ok\"}";

        enqueueJson(body);
        QdrantVectorServiceImpl svc = service();
        assertThat(svc.listCollections("kb_smoke_tmp_")).containsExactly("kb_smoke_tmp_1");
        assertThat(server.takeRequest().getPath()).isEqualTo("/collections");

        enqueueJson(body);
        assertThat(svc.listCollections("kb_chunks"))
                .containsExactly("kb_chunks", "kb_chunks_zhipu_embedding_3_1024_v1");

        enqueueJson(body);
        assertThat(svc.listCollections(null)).hasSize(4)
                .containsExactly("kb_chunks", "kb_chunks_zhipu_embedding_3_1024_v1",
                        "kb_smoke_tmp_1", "other_collection");

        enqueueJson(body);
        assertThat(svc.listCollections("   ")).as("空白前缀视为不过滤").hasSize(4);

        enqueueJson(body);
        assertThat(svc.listCollections("no_match_")).isEmpty();
    }

    // ==================== QDRANT-13 / 14：wait 与错误分类 ====================

    @Test
    @DisplayName("QDRANT-13 delete(wait=true) 的 operation_id/status 被正确解析")
    void qdrant13_parseDeleteWaitTrueResponse() throws Exception {
        enqueueJson("{\"result\":{\"operation_id\":7,\"status\":\"completed\"},\"status\":\"ok\",\"time\":0.02}");
        QdrantVectorServiceImpl svc = service();

        QdrantOperationResult waited = svc.deletePoints("col", List.of(UUID_V3), true);
        assertThat(server.takeRequest().getPath()).endsWith("?wait=true");
        assertThat(waited.operationId()).isEqualTo(7L);
        assertThat(waited.status()).isEqualTo("completed");
        assertThat(waited.isCompleted()).isTrue();
        assertThat(waited.isSkipped()).isFalse();

        // wait=false 走 acknowledged 分支，status 原样透传，不做任何"补齐"
        enqueueJson("{\"result\":{\"operation_id\":8,\"status\":\"acknowledged\"},\"status\":\"ok\"}");
        QdrantOperationResult acked = svc.deletePoints("col", List.of(UUID_V4), false);
        assertThat(server.takeRequest().getPath()).endsWith("?wait=false");
        assertThat(acked.operationId()).isEqualTo(8L);
        assertThat(acked.status()).isEqualTo("acknowledged");
        assertThat(acked.isCompleted()).isFalse();
    }

    @Test
    @DisplayName("QDRANT-14 HTTP 错误状态被分类为『Qdrant 返回错误』而非『Qdrant 不可用』")
    void qdrant14_httpErrorStatusClassification() throws Exception {
        QdrantVectorServiceImpl svc = service();

        server.enqueue(new MockResponse().setResponseCode(400)
                .setBody("{\"status\":{\"error\":\"Bad Request: invalid filter\"}}"));
        assertThatThrownBy(() -> svc.deletePointsByDocumentId("col", 6L, true))
                .isInstanceOf(RuntimeException.class)
                .satisfies(e -> {
                    assertThat(e.getMessage()).contains("Qdrant 返回错误（HTTP 400");
                    assertThat(e.getMessage()).doesNotContain("Qdrant 不可用");
                });

        server.enqueue(new MockResponse().setResponseCode(404)
                .setBody("{\"status\":{\"error\":\"Not found: Collection `col` doesn't exist\"}}"));
        assertThatThrownBy(() -> svc.countPoints("col"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Qdrant 返回错误（HTTP 404");

        server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"status\":{\"error\":\"internal\"}}"));
        assertThatThrownBy(() -> svc.scrollPoints("col", null, 10))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Qdrant 返回错误（HTTP 500");

        // 连接层失败才报"不可用"
        QdrantVectorServiceImpl unreachable =
                new QdrantVectorServiceImpl(proxyBypassRestClient(), objectMapper);
        var field = QdrantVectorServiceImpl.class.getDeclaredField("qdrantUrl");
        field.setAccessible(true);
        field.set(unreachable, "http://127.0.0.1:1");
        assertThatThrownBy(() -> unreachable.countPoints("col"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Qdrant 不可用");
    }

    // ==================== QDRANT-15 / 16 / 17 / 18：requestedCount / acceptedCount 可观测性（F-3 / F-4） ====================

    @Test
    @DisplayName("QDRANT-15 deletePoints 全合法输入：requestedCount == acceptedCount == 发送数")
    void qdrant15_allValidRequestedEqualsAccepted() throws Exception {
        enqueueJson(OK_DELETE_BODY);

        QdrantOperationResult result = service().deletePoints("col", List.of(UUID_V3, UUID_V4), true);

        assertThat(result.requestedCount()).isEqualTo(2);
        assertThat(result.acceptedCount()).isEqualTo(2);

        JsonNode points = bodyOf(server.takeRequest()).path("points");
        assertThat(points).hasSize(2);
        assertThat(result.isSkipped()).isFalse();
    }

    @Test
    @DisplayName("QDRANT-16 deletePoints 部分非法输入：requestedCount=4, acceptedCount=2（可观测非法被过滤）")
    void qdrant16_partialInvalidRequestedAndAccepted() throws Exception {
        enqueueJson(OK_DELETE_BODY);

        List<String> mixed = new ArrayList<>(Arrays.asList(
                UUID_V3,   // 合法
                "2_3_v1",  // 非法
                null,      // 非法
                UUID_V4)); // 合法

        QdrantOperationResult result = service().deletePoints("col", mixed, true);

        // 关键可观测性断言（F-3）：调用方现在能看到"4 个请求、2 个被接受"
        assertThat(result.requestedCount()).isEqualTo(4);
        assertThat(result.acceptedCount()).isEqualTo(2);
        assertThat(result.requestedCount())
                .as("requestedCount 必须 > acceptedCount 以暴露部分非法被过滤")
                .isGreaterThan(result.acceptedCount());

        JsonNode points = bodyOf(server.takeRequest()).path("points");
        assertThat(points).hasSize(2);
        List<String> sent = new ArrayList<>();
        points.forEach(node -> sent.add(node.asText()));
        assertThat(sent).containsExactly(UUID_V3, UUID_V4);
        assertThat(result.isSkipped()).isFalse();
    }

    @Test
    @DisplayName("QDRANT-17 deletePoints 重复合法 ID：requestedCount=3, acceptedCount=2（LinkedHashSet 去重）")
    void qdrant17_duplicateValidIdsDeduplicated() throws Exception {
        enqueueJson(OK_DELETE_BODY);

        // 两个 UUID_V3 是同一确定 ID，去重后应只剩 1 个
        List<String> dup = new ArrayList<>(Arrays.asList(UUID_V3, UUID_V3, UUID_V4));

        QdrantOperationResult result = service().deletePoints("col", dup, true);

        assertThat(result.requestedCount()).isEqualTo(3);
        assertThat(result.acceptedCount()).isEqualTo(2);

        JsonNode points = bodyOf(server.takeRequest()).path("points");
        assertThat(points).hasSize(2);
        List<String> sent = new ArrayList<>();
        points.forEach(node -> sent.add(node.asText()));
        assertThat(sent).containsExactly(UUID_V3, UUID_V4);
        assertThat(result.isSkipped()).isFalse();
    }

    @Test
    @DisplayName("QDRANT-18 deletePoints 全非法输入：requestedCount=N, acceptedCount=0, status=skipped, 0 次 HTTP")
    void qdrant18_allInvalidSkippedWithZeroHttp() throws Exception {
        QdrantVectorServiceImpl svc = service();

        QdrantOperationResult allInvalid =
                svc.deletePoints("col", Arrays.asList(null, "  ", "2_3_v1"), true);

        assertThat(server.getRequestCount()).isZero();
        assertThat(allInvalid.requestedCount()).isEqualTo(3);
        assertThat(allInvalid.acceptedCount()).isZero();
        assertThat(allInvalid.status()).isEqualTo("skipped");
        assertThat(allInvalid.isSkipped()).isTrue();
        assertThat(allInvalid.operationId()).isNull();
    }
}
