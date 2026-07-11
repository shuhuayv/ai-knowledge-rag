package com.shuhuayv.rag.vector.service.impl;

import com.shuhuayv.rag.dto.SearchResultItem;
import com.shuhuayv.rag.vector.service.QdrantVectorService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class QdrantVectorServiceImpl implements QdrantVectorService {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Value("${app.qdrant.url:}")
    private String qdrantUrl;

    @Value("${app.qdrant.host:localhost}")
    private String qdrantHost;

    @Value("${app.qdrant.port:6333}")
    private int qdrantPort;

    public QdrantVectorServiceImpl() {
        this(RestClient.builder().build(), new ObjectMapper());
    }

    /**
     * 测试友好构造器：注入 Mock/自定义 RestClient 与 ObjectMapper，便于离线单测。
     */
    public QdrantVectorServiceImpl(RestClient restClient, ObjectMapper objectMapper) {
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
    public void ensureCollection(String collectionName, int vectorDimension) {
        String url = baseUrl() + "/collections/" + collectionName;

        // 已存在：读取远端向量维度与距离，校验一致；不一致明确抛异常，绝不自动删除/重建。
        try {
            String responseBody = restClient.get().uri(url).retrieve().body(String.class);
            if (responseBody != null) {
                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode result = root.get("result");
                if (result != null) {
                    JsonNode vectors = result.path("config").path("params").path("vectors");
                    if (!vectors.isMissingNode() && vectors.has("size")) {
                        int remoteSize = vectors.get("size").asInt();
                        String remoteDistance = vectors.has("distance") ? vectors.get("distance").asText() : null;
                        if (remoteSize != vectorDimension) {
                            throw new IllegalStateException("Qdrant Collection [" + collectionName
                                    + "] 已存在，但向量维度不一致：期望 " + vectorDimension
                                    + "，实际 " + remoteSize
                                    + "。请检查是否混用了不同 Embedding 模型（384=mock / 1024=zhipu），"
                                    + "或手动重建该 Collection 后重试。");
                        }
                        if (remoteDistance != null && !"Cosine".equalsIgnoreCase(remoteDistance)) {
                            throw new IllegalStateException("Qdrant Collection [" + collectionName
                                    + "] 已存在，但距离度量不一致：期望 Cosine，实际 " + remoteDistance + "。");
                        }
                        log.info("Collection 已存在且维度/距离一致：{}, dimension={}, distance={}",
                                collectionName, remoteSize, remoteDistance);
                        return;
                    }
                }
            }
        } catch (IllegalStateException e) {
            // 维度/距离不一致：明确抛出，不吞掉
            throw e;
        } catch (Exception e) {
            log.info("Collection 不存在或配置不可读，将创建：{}, error={}", collectionName, e.getMessage());
        }

        // 不存在或配置不可读：创建
        String body = String.format(
                "{\"vectors\":{\"size\":%d,\"distance\":\"Cosine\"}}",
                vectorDimension
        );

        try {
            restClient.put()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Collection created: {}, dimension={}, distance=Cosine", collectionName, vectorDimension);
        } catch (HttpStatusCodeException ex) {
            String errorMsg = "创建 Qdrant Collection 失败: " + ex.getMessage();
            log.error(errorMsg, ex);
            throw new RuntimeException("Qdrant 返回错误（HTTP " + ex.getStatusCode().value() + "）：" + safeBody(ex), ex);
        } catch (Exception e) {
            String errorMsg = "创建 Qdrant Collection 失败: " + e.getMessage();
            log.error(errorMsg, e);
            throw new RuntimeException("Qdrant 不可用，请确保 Qdrant 已启动（端口 " + qdrantPort + "）: " + e.getMessage(), e);
        }
    }

    @Override
    public int getVectorSize(String collectionName) {
        String url = baseUrl() + "/collections/" + collectionName;
        try {
            String responseBody = restClient.get().uri(url).retrieve().body(String.class);
            if (responseBody == null) {
                throw new IllegalStateException("无法读取 Collection 信息（响应为空）：" + collectionName);
            }
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode result = root.get("result");
            if (result == null) {
                throw new IllegalStateException("Collection 不存在：" + collectionName);
            }
            JsonNode vectors = result.path("config").path("params").path("vectors");
            if (vectors.isMissingNode() || !vectors.has("size")) {
                throw new IllegalStateException("Collection 缺少向量维度信息：" + collectionName);
            }
            return vectors.get("size").asInt();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("读取 Qdrant Collection 维度失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void upsertPoint(String collectionName, String pointId, List<Float> vector, Map<String, Object> payload) {
        String url = baseUrl() + "/collections/" + collectionName + "/points";

        StringBuilder sb = new StringBuilder();
        sb.append("{\"points\":[{\"id\":\"").append(pointId).append("\",\"vector\":[");
        for (int i = 0; i < vector.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(vector.get(i));
        }
        sb.append("],\"payload\":{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            Object value = entry.getValue();
            if (value instanceof String) {
                sb.append("\"").append(entry.getKey()).append("\":\"").append(escapeJson((String) value)).append("\"");
            } else if (value instanceof Number) {
                sb.append("\"").append(entry.getKey()).append("\":").append(value);
            } else {
                sb.append("\"").append(entry.getKey()).append("\":\"").append(value).append("\"");
            }
        }
        sb.append("}}]}");

        try {
            restClient.put()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(sb.toString())
                    .retrieve()
                    .toBodilessEntity();
            log.info("Point upserted: collection={}, pointId={}", collectionName, pointId);
        } catch (HttpStatusCodeException ex) {
            String errorMsg = "写入 Qdrant Point 失败: " + ex.getMessage();
            log.error(errorMsg, ex);
            throw new RuntimeException("Qdrant 返回错误（HTTP " + ex.getStatusCode().value() + "）：" + safeBody(ex), ex);
        } catch (Exception e) {
            String errorMsg = "写入 Qdrant Point 失败: " + e.getMessage();
            log.error(errorMsg, e);
            throw new RuntimeException("Qdrant 不可用，请确保 Qdrant 已启动（端口 " + qdrantPort + "）: " + e.getMessage(), e);
        }
    }

    /**
     * 安全提取 Qdrant HTTP 错误响应体前 300 字符，避免记录过大响应体或任何敏感信息。
     * Qdrant 错误体本身不含 API Key / 鉴权信息，此处仅做长度截断。
     */
    private String safeBody(HttpStatusCodeException ex) {
        String b = ex.getResponseBodyAsString();
        if (b == null) {
            return "";
        }
        return b.length() > 300 ? b.substring(0, 300) : b;
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    @Override
    public List<SearchResultItem> search(String collectionName, List<Float> queryVector, int topK) {
        String url = baseUrl() + "/collections/" + collectionName + "/points/search";

        StringBuilder sb = new StringBuilder();
        sb.append("{\"vector\":[");
        for (int i = 0; i < queryVector.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(queryVector.get(i));
        }
        sb.append("],\"limit\":").append(topK);
        sb.append(",\"with_payload\":true");
        sb.append(",\"with_vector\":false}");
        String body = sb.toString();

        String responseBody;
        try {
            responseBody = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            String errorMsg = "Qdrant 搜索失败: " + e.getMessage();
            log.error(errorMsg, e);
            throw new RuntimeException("Qdrant 不可用，请确保 Qdrant 已启动（端口 " + qdrantPort + "）: " + e.getMessage(), e);
        }

        return parseSearchResults(responseBody, collectionName);
    }

    private List<SearchResultItem> parseSearchResults(String responseBody, String collectionName) {
        List<SearchResultItem> results = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode points = root.get("result");
            if (points == null || !points.isArray()) {
                return results;
            }

            for (JsonNode point : points) {
                String pointId = point.get("id") instanceof com.fasterxml.jackson.databind.node.TextNode
                        ? point.get("id").asText()
                        : String.valueOf(point.get("id").asLong());

                double score = point.get("score").asDouble();

                JsonNode payload = point.get("payload");
                Long documentId = payload.get("documentId").asLong();
                Long chunkId = payload.get("chunkId").asLong();
                Integer chunkIndex = payload.get("chunkIndex").asInt();
                String content = payload.get("content").asText();

                SearchResultItem item = SearchResultItem.builder()
                        .documentId(documentId)
                        .chunkId(chunkId)
                        .chunkIndex(chunkIndex)
                        .content(content)
                        .score(score)
                        .collectionName(collectionName)
                        .build();
                results.add(item);
            }
        } catch (Exception e) {
            log.error("Failed to parse Qdrant search results", e);
            throw new RuntimeException("解析 Qdrant 搜索结果失败: " + e.getMessage(), e);
        }
        return results;
    }
}