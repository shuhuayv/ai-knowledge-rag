package com.shuhuayv.rag.vector.service.impl;

import com.shuhuayv.rag.vector.service.QdrantVectorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class QdrantVectorServiceImpl implements QdrantVectorService {

    private final RestClient restClient;

    @Value("${app.qdrant.host:localhost}")
    private String qdrantHost;

    @Value("${app.qdrant.port:6333}")
    private int qdrantPort;

    public QdrantVectorServiceImpl() {
        this.restClient = RestClient.builder().build();
    }

    private String baseUrl() {
        return "http://" + qdrantHost + ":" + qdrantPort;
    }

    @Override
    public void ensureCollection(String collectionName, int vectorDimension) {
        String url = baseUrl() + "/collections/" + collectionName;

        try {
            var response = restClient.get()
                    .uri(url)
                    .retrieve()
                    .toBodilessEntity();

            if (response.getStatusCode() == HttpStatus.OK) {
                log.info("Collection already exists: {}", collectionName);
                return;
            }
        } catch (Exception e) {
            log.info("Collection not found, creating: {}", collectionName);
        }

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
        } catch (Exception e) {
            String errorMsg = "创建 Qdrant Collection 失败: " + e.getMessage();
            log.error(errorMsg, e);
            throw new RuntimeException("Qdrant 不可用，请确保 Qdrant 已启动（端口 " + qdrantPort + "）: " + e.getMessage(), e);
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
        } catch (Exception e) {
            String errorMsg = "写入 Qdrant Point 失败: " + e.getMessage();
            log.error(errorMsg, e);
            throw new RuntimeException("Qdrant 不可用，请确保 Qdrant 已启动（端口 " + qdrantPort + "）: " + e.getMessage(), e);
        }
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}