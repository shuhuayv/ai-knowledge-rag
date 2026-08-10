package com.shuhuayv.rag.vector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shuhuayv.rag.vector.model.QdrantOperationResult;
import com.shuhuayv.rag.vector.model.ScrollPage;
import com.shuhuayv.rag.vector.service.impl.QdrantVectorServiceImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Qdrant lifecycle 真实冒烟测试（打真实本地 Qdrant）。
 *
 * <p><b>默认不运行</b>：两重门控保证 CI（无 Qdrant）不会因此失败——
 * <ol>
 *   <li>类名以 {@code IT} 结尾，不匹配 surefire 默认 include（{@code *Test} / {@code Test*} / {@code *Tests}），
 *       故 {@code mvn test} 根本不会挑中它；</li>
 *   <li>{@link EnabledIfSystemProperty} 门控，即使被显式 {@code -Dtest=} 选中，
 *       未加 {@code -Dqdrant.smoke=true} 也会被 JUnit 跳过（skipped，不是 failed）。</li>
 * </ol>
 * 本地运行：{@code mvn -B test -Dtest=QdrantLifecycleSmokeIT -Dqdrant.smoke=true}</p>
 *
 * <p><b>数据安全边界</b>：只操作运行时生成的临时集合 {@code kb_smoke_tmp_<timestamp>}，
 * 类结束时无条件删除。绝不触碰 {@code kb_chunks} 与 {@code kb_chunks_zhipu_embedding_3_1024_v1}。
 * 写入 payload 的文本固定为 {@code "smoke-test-only"}，不含任何真实文档内容。
 * 向量为本地合成的确定性小向量，<b>不调用任何 embedding / chat 模型 API</b>。</p>
 */
@DisplayName("Qdrant lifecycle 真实冒烟（需 -Dqdrant.smoke=true）")
@EnabledIfSystemProperty(named = "qdrant.smoke", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class QdrantLifecycleSmokeIT {

    /** 临时集合名：带时间戳，与任何生产集合前缀不冲突。 */
    private static final String TMP_COLLECTION = "kb_smoke_tmp_" + System.currentTimeMillis();

    /** 合成向量维度，刻意用极小维度避免任何真实 embedding 调用。 */
    private static final int DIM = 4;

    /** payload 中唯一允许出现的文本内容。 */
    private static final String SAFE_CONTENT = "smoke-test-only";

    /** 受保护集合：本测试任何路径都不得写入或删除。 */
    private static final List<String> PROTECTED_COLLECTIONS =
            List.of("kb_chunks", "kb_chunks_zhipu_embedding_3_1024_v1");

    private static final long DOC_A = 900001L;
    private static final long DOC_B = 900002L;

    /** documentId=DOC_A 的 3 个点：混用 UUID v3（确定性）与 UUID v4（随机）。 */
    private static final String P1_V3 = deterministicId(DOC_A, 1L);
    private static final String P2_V3 = deterministicId(DOC_A, 2L);
    private static final String P3_V4 = UUID.randomUUID().toString();

    /** documentId=DOC_B 的 2 个点。 */
    private static final String P4_V3 = deterministicId(DOC_B, 1L);
    private static final String P5_V4 = UUID.randomUUID().toString();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String deterministicId(long documentId, long chunkId) {
        String source = documentId + ":" + chunkId + ":v1";
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String baseUrl() {
        String env = System.getenv("QDRANT_URL");
        return (env == null || env.isBlank()) ? "http://localhost:6333" : env;
    }

    private static RestClient rawClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setProxy(Proxy.NO_PROXY);
        return RestClient.builder().requestFactory(factory).build();
    }

    /** 用真实实现类打真实 Qdrant；通过反射注入 baseUrl（非 Spring 上下文，@Value 不生效）。 */
    private static QdrantVectorServiceImpl realService() {
        try {
            QdrantVectorServiceImpl svc = new QdrantVectorServiceImpl(rawClient(), MAPPER);
            var field = QdrantVectorServiceImpl.class.getDeclaredField("qdrantUrl");
            field.setAccessible(true);
            field.set(svc, baseUrl());
            return svc;
        } catch (Exception e) {
            throw new IllegalStateException("无法构造真实 QdrantVectorServiceImpl: " + e.getMessage(), e);
        }
    }

    /** 硬性护栏：任何操作前确认目标集合不是受保护集合。 */
    private static void assertNotProtected(String collectionName) {
        assertThat(PROTECTED_COLLECTIONS)
                .as("冒烟测试严禁触碰生产集合")
                .doesNotContain(collectionName);
        assertThat(collectionName).startsWith("kb_smoke_tmp_");
    }

    private static List<Float> syntheticVector(int seed) {
        return List.of(0.1f * seed, 0.2f * seed, 0.3f * seed, 0.4f * seed);
    }

    private static Map<String, Object> payload(long documentId, long chunkId, int chunkIndex) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("documentId", documentId);
        p.put("chunkId", chunkId);
        p.put("chunkIndex", chunkIndex);
        p.put("content", SAFE_CONTENT);
        return p;
    }

    @AfterAll
    static void dropTemporaryCollection() {
        assertNotProtected(TMP_COLLECTION);
        try {
            rawClient().delete()
                    .uri(baseUrl() + "/collections/" + TMP_COLLECTION)
                    .retrieve()
                    .toBodilessEntity();
            System.out.println("[SMOKE][cleanup] dropped temp collection: " + TMP_COLLECTION);
        } catch (Exception e) {
            System.out.println("[SMOKE][cleanup] drop failed (可能已不存在): " + e.getMessage());
        }
        boolean stillExists = realService().collectionExists(TMP_COLLECTION);
        System.out.println("[SMOKE][cleanup] collectionExists after drop = " + stillExists);
        assertThat(stillExists).as("临时集合必须已删除").isFalse();
    }

    @Test
    @Order(1)
    @DisplayName("SMOKE-1 create temp collection + upsert 5 synthetic points (UUID v3 & v4)")
    void step1_createAndUpsert() {
        assertNotProtected(TMP_COLLECTION);
        QdrantVectorServiceImpl svc = realService();

        assertThat(svc.collectionExists(TMP_COLLECTION)).as("临时集合创建前不应存在").isFalse();
        svc.ensureCollection(TMP_COLLECTION, DIM);
        assertThat(svc.collectionExists(TMP_COLLECTION)).isTrue();
        assertThat(svc.getVectorSize(TMP_COLLECTION)).isEqualTo(DIM);

        svc.upsertPoint(TMP_COLLECTION, P1_V3, syntheticVector(1), payload(DOC_A, 1L, 0));
        svc.upsertPoint(TMP_COLLECTION, P2_V3, syntheticVector(2), payload(DOC_A, 2L, 1));
        svc.upsertPoint(TMP_COLLECTION, P3_V4, syntheticVector(3), payload(DOC_A, 3L, 2));
        svc.upsertPoint(TMP_COLLECTION, P4_V3, syntheticVector(4), payload(DOC_B, 1L, 0));
        svc.upsertPoint(TMP_COLLECTION, P5_V4, syntheticVector(5), payload(DOC_B, 2L, 1));

        System.out.println("[SMOKE][1] collection=" + TMP_COLLECTION
                + " ids(v3)=" + List.of(P1_V3, P2_V3, P4_V3)
                + " ids(v4)=" + List.of(P3_V4, P5_V4));
    }

    @Test
    @Order(2)
    @DisplayName("SMOKE-2 count + countByDocumentId + scroll")
    void step2_countAndScroll() {
        QdrantVectorServiceImpl svc = realService();

        long total = svc.countPoints(TMP_COLLECTION);
        long docA = svc.countPointsByDocumentId(TMP_COLLECTION, DOC_A);
        long docB = svc.countPointsByDocumentId(TMP_COLLECTION, DOC_B);
        System.out.println("[SMOKE][2] count total=" + total + " docA=" + docA + " docB=" + docB);
        assertThat(total).isEqualTo(5L);
        assertThat(docA).isEqualTo(3L);
        assertThat(docB).isEqualTo(2L);

        ScrollPage page = svc.scrollPoints(TMP_COLLECTION, null, 2);
        System.out.println("[SMOKE][2] scroll page1 size=" + page.size() + " nextOffset=" + page.nextOffset());
        assertThat(page.size()).isEqualTo(2);
        assertThat(page.hasNext()).isTrue();
        page.points().forEach(p -> {
            assertThat(p.payload()).containsEntry("content", SAFE_CONTENT);
            assertThat(p.payload()).containsKey("documentId");
        });

        ScrollPage page2 = svc.scrollPoints(TMP_COLLECTION, page.nextOffset(), 10);
        System.out.println("[SMOKE][2] scroll page2 size=" + page2.size() + " nextOffset=" + page2.nextOffset());
        assertThat(page2.size()).isEqualTo(3);
    }

    @Test
    @Order(3)
    @DisplayName("SMOKE-3 delete by exact IDs (v3 + v4) with wait=true, count immediately visible")
    void step3_deleteByExactIds() {
        QdrantVectorServiceImpl svc = realService();

        long before = svc.countPointsByDocumentId(TMP_COLLECTION, DOC_A);
        assertThat(before).isEqualTo(3L);

        // 混入 1 个非法 ID，验证不会导致整批 400
        QdrantOperationResult result = svc.deletePoints(
                TMP_COLLECTION, java.util.Arrays.asList(P1_V3, P3_V4, "2_3_v1", null), true);
        System.out.println("[SMOKE][3] deletePoints operationId=" + result.operationId()
                + " status=" + result.status());
        assertThat(result.isSkipped()).isFalse();

        // wait=true 语义验证：返回后立刻 count，无 sleep、无重试
        long after = svc.countPointsByDocumentId(TMP_COLLECTION, DOC_A);
        System.out.println("[SMOKE][3] wait=true visibility: before=" + before + " after=" + after
                + " deleted=" + (before - after) + " (no sleep, no retry)");
        assertThat(after).as("delete(wait=true) 返回后 count 必须立即可见新状态").isEqualTo(1L);
        assertThat(before - after).isEqualTo(2L);
    }

    @Test
    @Order(4)
    @DisplayName("SMOKE-4 delete by documentId (number filter) with wait=true → 0")
    void step4_deleteByDocumentId() {
        QdrantVectorServiceImpl svc = realService();

        long beforeA = svc.countPointsByDocumentId(TMP_COLLECTION, DOC_A);
        QdrantOperationResult ra = svc.deletePointsByDocumentId(TMP_COLLECTION, DOC_A, true);
        long afterA = svc.countPointsByDocumentId(TMP_COLLECTION, DOC_A);
        System.out.println("[SMOKE][4] docA before=" + beforeA + " after=" + afterA
                + " operationId=" + ra.operationId() + " status=" + ra.status());
        assertThat(afterA).isZero();

        long beforeB = svc.countPointsByDocumentId(TMP_COLLECTION, DOC_B);
        QdrantOperationResult rb = svc.deletePointsByDocumentId(TMP_COLLECTION, DOC_B, true);
        long afterB = svc.countPointsByDocumentId(TMP_COLLECTION, DOC_B);
        System.out.println("[SMOKE][4] docB before=" + beforeB + " after=" + afterB
                + " operationId=" + rb.operationId() + " status=" + rb.status());
        assertThat(beforeB).isEqualTo(2L);
        assertThat(afterB).isZero();

        long total = svc.countPoints(TMP_COLLECTION);
        System.out.println("[SMOKE][4] total after all deletes = " + total);
        assertThat(total).isZero();
    }

    @Test
    @Order(5)
    @DisplayName("SMOKE-5 number vs string filter 类型实证（同一临时集合）")
    void step5_numberVsStringFilter() {
        QdrantVectorServiceImpl svc = realService();

        // 重新写入 1 个点用于类型对比，payload 仍只含安全文本
        svc.upsertPoint(TMP_COLLECTION, P2_V3, syntheticVector(2), payload(DOC_A, 2L, 1));

        long numberHit = rawCount("{\"exact\":true,\"filter\":{\"must\":[{\"key\":\"documentId\","
                + "\"match\":{\"value\":" + DOC_A + "}}]}}");
        long stringHit = rawCount("{\"exact\":true,\"filter\":{\"must\":[{\"key\":\"documentId\","
                + "\"match\":{\"value\":\"" + DOC_A + "\"}}]}}");

        System.out.println("[SMOKE][5] filter value as NUMBER -> count=" + numberHit);
        System.out.println("[SMOKE][5] filter value as STRING -> count=" + stringHit);
        assertThat(numberHit).as("数值 filter 必须命中").isEqualTo(1L);
        assertThat(stringHit).as("字符串 filter 必须 0 命中（证明类型契约不可违反）").isZero();

        // 清空
        svc.deletePointsByDocumentId(TMP_COLLECTION, DOC_A, true);
        assertThat(svc.countPoints(TMP_COLLECTION)).isZero();
    }

    @Test
    @Order(6)
    @DisplayName("SMOKE-6 collectionExists + listCollections(prefix) 且生产集合未被触碰")
    void step6_collectionMetadata() {
        QdrantVectorServiceImpl svc = realService();

        assertThat(svc.collectionExists(TMP_COLLECTION)).isTrue();
        assertThat(svc.collectionExists("kb_definitely_not_exists_" + System.nanoTime())).isFalse();

        List<String> tmp = svc.listCollections("kb_smoke_tmp_");
        System.out.println("[SMOKE][6] listCollections('kb_smoke_tmp_') = " + tmp);
        assertThat(tmp).contains(TMP_COLLECTION);

        List<String> all = svc.listCollections(null);
        System.out.println("[SMOKE][6] listCollections(null) = " + all);
        assertThat(all).contains(TMP_COLLECTION);

        // 只读确认生产集合仍在且未被本测试写入（不做任何写操作）
        for (String protectedName : PROTECTED_COLLECTIONS) {
            if (svc.collectionExists(protectedName)) {
                System.out.println("[SMOKE][6] untouched " + protectedName
                        + " count=" + svc.countPoints(protectedName) + " (read-only)");
            }
        }
    }

    /** 直接用原始 HTTP 打 count 端点，用于对比 number/string filter 的类型语义。 */
    private long rawCount(String jsonBody) {
        String response = rawClient().post()
                .uri(baseUrl() + "/collections/" + TMP_COLLECTION + "/points/count")
                .contentType(MediaType.APPLICATION_JSON)
                .body(jsonBody)
                .retrieve()
                .body(String.class);
        try {
            return MAPPER.readTree(response).path("result").path("count").asLong();
        } catch (Exception e) {
            throw new IllegalStateException("解析 count 响应失败: " + e.getMessage(), e);
        }
    }
}
