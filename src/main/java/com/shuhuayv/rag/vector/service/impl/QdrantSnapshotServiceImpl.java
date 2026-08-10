package com.shuhuayv.rag.vector.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shuhuayv.rag.vector.model.QdrantSnapshotInfo;
import com.shuhuayv.rag.vector.service.QdrantSnapshotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link QdrantSnapshotService} 的 REST 实现。
 *
 * <p>刻意复用 {@link QdrantVectorServiceImpl} 相同的 {@link RestClient} 构建方式、
 * baseUrl 解析规则与错误分类语义（HTTP 状态码错误 vs 连接不可用），
 * 不引入第二套 HTTP 客户端，也不引入 Qdrant SDK。</p>
 */
@Slf4j
@Service
public class QdrantSnapshotServiceImpl implements QdrantSnapshotService {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Value("${app.qdrant.url:}")
    private String qdrantUrl;

    @Value("${app.qdrant.host:localhost}")
    private String qdrantHost;

    @Value("${app.qdrant.port:6333}")
    private int qdrantPort;

    public QdrantSnapshotServiceImpl() {
        this(RestClient.builder().build(), new ObjectMapper());
    }

    /**
     * 测试友好构造器：注入 Mock/自定义 RestClient 与 ObjectMapper，便于离线单测。
     *
     * @param restClient   HTTP 客户端
     * @param objectMapper JSON 解析器
     */
    public QdrantSnapshotServiceImpl(RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    private String baseUrl() {
        if (qdrantUrl != null && !qdrantUrl.isBlank()) {
            return qdrantUrl;
        }
        return "http://" + qdrantHost + ":" + qdrantPort;
    }

    @Override
    public QdrantSnapshotInfo createSnapshot(String collectionName) {
        requireCollectionName(collectionName);

        String url = baseUrl() + "/collections/" + collectionName + "/snapshots";
        String responseBody;
        try {
            responseBody = restClient.post()
                    .uri(url)
                    .retrieve()
                    .body(String.class);
        } catch (HttpStatusCodeException ex) {
            throw httpFailure("创建 Qdrant Snapshot", ex);
        } catch (Exception e) {
            throw unavailable("创建 Qdrant Snapshot", e);
        }

        if (responseBody == null) {
            throw new IllegalStateException("Qdrant 创建 Snapshot 响应为空：" + collectionName);
        }
        try {
            JsonNode result = objectMapper.readTree(responseBody).path("result");
            if (result.isMissingNode() || result.isNull()) {
                throw new IllegalStateException("Qdrant 创建 Snapshot 响应缺少 result 字段：" + collectionName);
            }
            QdrantSnapshotInfo info = toSnapshotInfo(result);
            log.info("Snapshot created: collection={}, name={}, size={}", collectionName, info.name(), info.size());
            return info;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("解析 Qdrant Snapshot 创建响应失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<QdrantSnapshotInfo> listSnapshots(String collectionName) {
        requireCollectionName(collectionName);

        String url = baseUrl() + "/collections/" + collectionName + "/snapshots";
        String responseBody;
        try {
            responseBody = restClient.get().uri(url).retrieve().body(String.class);
        } catch (HttpStatusCodeException ex) {
            throw httpFailure("列出 Qdrant Snapshot", ex);
        } catch (Exception e) {
            throw unavailable("列出 Qdrant Snapshot", e);
        }

        List<QdrantSnapshotInfo> snapshots = new ArrayList<>();
        if (responseBody == null) {
            return snapshots;
        }
        try {
            JsonNode result = objectMapper.readTree(responseBody).path("result");
            if (!result.isArray()) {
                return snapshots;
            }
            for (JsonNode node : result) {
                snapshots.add(toSnapshotInfo(node));
            }
        } catch (Exception e) {
            throw new RuntimeException("解析 Qdrant Snapshot 列表失败: " + e.getMessage(), e);
        }
        return snapshots;
    }

    private QdrantSnapshotInfo toSnapshotInfo(JsonNode node) {
        String name = node.path("name").asText(null);
        String creationTime = node.hasNonNull("creation_time") ? node.get("creation_time").asText() : null;
        long size = node.hasNonNull("size") ? node.get("size").asLong() : QdrantSnapshotInfo.SIZE_UNKNOWN;
        String checksum = node.hasNonNull("checksum") ? node.get("checksum").asText() : null;
        return new QdrantSnapshotInfo(name, creationTime, size, checksum);
    }

    private void requireCollectionName(String collectionName) {
        if (collectionName == null || collectionName.isBlank()) {
            throw new IllegalArgumentException("collectionName 不能为空");
        }
    }

    private RuntimeException httpFailure(String action, HttpStatusCodeException ex) {
        log.error("{}失败：HTTP {}", action, ex.getStatusCode().value(), ex);
        return new RuntimeException("Qdrant 返回错误（HTTP " + ex.getStatusCode().value() + "）："
                + safeBody(ex), ex);
    }

    private RuntimeException unavailable(String action, Exception e) {
        log.error("{}失败：{}", action, e.getMessage(), e);
        return new RuntimeException("Qdrant 不可用，请确保 Qdrant 已启动（端口 " + qdrantPort + "）: "
                + e.getMessage(), e);
    }

    /** 截断错误响应体，避免记录过大内容；Qdrant 错误体本身不含鉴权信息。 */
    private String safeBody(HttpStatusCodeException ex) {
        String b = ex.getResponseBodyAsString();
        if (b == null) {
            return "";
        }
        return b.length() > 300 ? b.substring(0, 300) : b;
    }
}
