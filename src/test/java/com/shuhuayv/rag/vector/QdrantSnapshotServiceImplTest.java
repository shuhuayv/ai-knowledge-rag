package com.shuhuayv.rag.vector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shuhuayv.rag.vector.model.QdrantSnapshotInfo;
import com.shuhuayv.rag.vector.service.impl.QdrantSnapshotServiceImpl;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SNAP-01 ~ SNAP-03：{@link QdrantSnapshotServiceImpl} 离线契约测试。
 *
 * <p>Mock 响应体<b>逐字取自真实 Qdrant 1.18.2 的实际响应</b>
 * （见 {@code evidence/smoke/curl_smoke_output.txt} step 16 / 16b），
 * 避免用臆造的报文结构自证正确。</p>
 */
@DisplayName("Qdrant Snapshot 契约（离线）")
class QdrantSnapshotServiceImplTest {

    /** 真实 Qdrant 1.18.2 的 create snapshot 响应（已脱去临时集合时间戳无关部分）。 */
    private static final String REAL_CREATE_BODY = "{\"result\":{"
            + "\"name\":\"kb_smoke_tmp_curl_1786349110-2215973243101698-2026-08-10-08-05-10.snapshot\","
            + "\"creation_time\":\"2026-08-10T08:05:10\","
            + "\"size\":105984,"
            + "\"checksum\":\"7877d97cfdfe41dae7e004b6bfddbe323cf7bea30c0c0679a198a6ca41ecd9e7\"},"
            + "\"status\":\"ok\",\"time\":0.020200084}";

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

    private QdrantSnapshotServiceImpl service() throws Exception {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setProxy(Proxy.NO_PROXY);
        QdrantSnapshotServiceImpl svc = new QdrantSnapshotServiceImpl(
                RestClient.builder().requestFactory(factory).build(), objectMapper);
        var field = QdrantSnapshotServiceImpl.class.getDeclaredField("qdrantUrl");
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

    @Test
    @DisplayName("SNAP-01 createSnapshot 解析真实响应的 name/creation_time/size/checksum")
    void snap01_createSnapshotParsesRealResponse() throws Exception {
        enqueueJson(REAL_CREATE_BODY);

        QdrantSnapshotInfo info = service().createSnapshot("kb_smoke_tmp_curl_1786349110");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/collections/kb_smoke_tmp_curl_1786349110/snapshots");

        assertThat(info.name())
                .isEqualTo("kb_smoke_tmp_curl_1786349110-2215973243101698-2026-08-10-08-05-10.snapshot");
        assertThat(info.creationTime()).isEqualTo("2026-08-10T08:05:10");
        assertThat(info.size()).isEqualTo(105984L);
        assertThat(info.checksum())
                .isEqualTo("7877d97cfdfe41dae7e004b6bfddbe323cf7bea30c0c0679a198a6ca41ecd9e7");
    }

    @Test
    @DisplayName("SNAP-02 listSnapshots 走只读 GET，空列表与缺字段均安全降级")
    void snap02_listSnapshotsReadOnlyAndNullSafe() throws Exception {
        QdrantSnapshotServiceImpl svc = service();

        enqueueJson("{\"result\":[{"
                + "\"name\":\"kb_chunks-1-2026-08-10-07-55-00.snapshot\","
                + "\"creation_time\":\"2026-08-10T07:55:00\","
                + "\"size\":105984,"
                + "\"checksum\":\"abc123\"}],\"status\":\"ok\"}");
        List<QdrantSnapshotInfo> one = svc.listSnapshots("kb_chunks");
        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).as("列快照必须是只读 GET").isEqualTo("GET");
        assertThat(request.getPath()).isEqualTo("/collections/kb_chunks/snapshots");
        assertThat(one).hasSize(1);
        assertThat(one.get(0).size()).isEqualTo(105984L);

        // 空列表
        enqueueJson("{\"result\":[],\"status\":\"ok\"}");
        assertThat(svc.listSnapshots("kb_chunks")).isEmpty();

        // 缺 size / checksum / creation_time → 降级为 SIZE_UNKNOWN / null，不抛异常
        enqueueJson("{\"result\":[{\"name\":\"partial.snapshot\"}],\"status\":\"ok\"}");
        List<QdrantSnapshotInfo> partial = svc.listSnapshots("kb_chunks");
        assertThat(partial).hasSize(1);
        assertThat(partial.get(0).name()).isEqualTo("partial.snapshot");
        assertThat(partial.get(0).size()).isEqualTo(QdrantSnapshotInfo.SIZE_UNKNOWN);
        assertThat(partial.get(0).checksum()).isNull();
        assertThat(partial.get(0).creationTime()).isNull();
    }

    @Test
    @DisplayName("SNAP-03 空集合名 fail-fast 且 0 次 HTTP；HTTP 错误归类为『Qdrant 返回错误』")
    void snap03_validationAndErrorClassification() throws Exception {
        QdrantSnapshotServiceImpl svc = service();

        assertThatThrownBy(() -> svc.createSnapshot("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("collectionName 不能为空");
        assertThatThrownBy(() -> svc.listSnapshots(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("collectionName 不能为空");
        assertThat(server.getRequestCount()).isZero();

        server.enqueue(new MockResponse().setResponseCode(404)
                .setBody("{\"status\":{\"error\":\"Not found: Collection `nope` doesn't exist\"}}"));
        assertThatThrownBy(() -> svc.createSnapshot("nope"))
                .isInstanceOf(RuntimeException.class)
                .satisfies(e -> {
                    assertThat(e.getMessage()).contains("Qdrant 返回错误（HTTP 404");
                    assertThat(e.getMessage()).doesNotContain("Qdrant 不可用");
                });
    }
}
