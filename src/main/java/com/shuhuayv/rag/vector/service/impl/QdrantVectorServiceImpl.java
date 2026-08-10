package com.shuhuayv.rag.vector.service.impl;

import com.shuhuayv.rag.dto.SearchResultItem;
import com.shuhuayv.rag.vector.model.QdrantOperationResult;
import com.shuhuayv.rag.vector.model.ScrollPage;
import com.shuhuayv.rag.vector.service.QdrantVectorService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@Service
public class QdrantVectorServiceImpl implements QdrantVectorService {

    /**
     * Qdrant Point ID 的 UUID 字面量格式校验。
     *
     * <p>有意<b>不校验 version 位</b>（第 3 段首字符）：历史数据中同时存在 UUID v3（确定性，
     * 由 {@code buildPointId} 生成）与 UUID v4（随机），二者都必须可被删除。
     * 用 {@code UUID.fromString} 代替本正则是不安全的——JDK 的实现较宽松，
     * 会把 {@code "1-1-1-1-1"} 这类残缺串也解析成功，进而把非法 ID 发给 Qdrant 触发整批 400。</p>
     */
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /** scroll 单页 limit 下界。 */
    private static final int SCROLL_LIMIT_MIN = 1;

    /** scroll 单页 limit 上界，防止一次拉爆内存。 */
    private static final int SCROLL_LIMIT_MAX = 1000;

    /** payload 中记录文档 ID 的字段名，与 {@code DocumentIndexServiceImpl} 写入时保持一致。 */
    private static final String PAYLOAD_KEY_DOCUMENT_ID = "documentId";

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

    // ==================== Lifecycle 治理能力（PR-1 新增） ====================

    @Override
    public QdrantOperationResult deletePoints(String collectionName, List<String> pointIds, boolean wait) {
        requireCollectionName(collectionName);

        int requestedCount = (pointIds == null) ? 0 : pointIds.size();
        List<String> validIds = filterValidPointIds(pointIds);
        int acceptedCount = validIds.size();

        if (validIds.isEmpty()) {
            // 过滤后为空：不发起任何 HTTP 请求。空 points 数组会被 Qdrant 判为无意义请求，
            // 且会让调用方误以为"发生过一次删除"。requestedCount 保留以便审计"请求了多少 / 接受了 0"。
            log.info("deletePoints skipped: no valid point id after filtering, collection={}, requested={}",
                    collectionName, requestedCount);
            return QdrantOperationResult.skipped(requestedCount);
        }

        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode points = body.putArray("points");
        validIds.forEach(points::add);

        String url = baseUrl() + "/collections/" + collectionName + "/points/delete?wait=" + wait;
        String responseBody = postJson(url, body, "按 ID 删除 Qdrant Point");
        QdrantOperationResult result = parseOperationResult(responseBody, requestedCount, acceptedCount);
        log.info("deletePoints done: collection={}, requested={}, accepted={}, wait={}, operationId={}, status={}",
                collectionName, requestedCount, acceptedCount, wait,
                result.operationId(), result.status());
        return result;
    }

    @Override
    public QdrantOperationResult deletePointsByDocumentId(String collectionName, Long documentId, boolean wait) {
        requireCollectionName(collectionName);
        if (documentId == null) {
            // 先于任何 HTTP 调用抛出：documentId 为 null 时若放行，过滤条件会退化成"全表匹配"，
            // 可能整库删空。这是必须 fail-fast 的场景。
            throw new IllegalArgumentException("documentId 不能为空（按文档删除向量点必须指定 documentId）");
        }

        ObjectNode body = objectMapper.createObjectNode();
        body.set("filter", buildDocumentIdFilter(documentId));

        String url = baseUrl() + "/collections/" + collectionName + "/points/delete?wait=" + wait;
        String responseBody = postJson(url, body, "按 documentId 删除 Qdrant Point");
        // documentId 路径无 pointId 列表可供客户端过滤，requested/accepted 不适用于此路径，记为 0。
        QdrantOperationResult result = parseOperationResult(responseBody, 0, 0);
        log.info("deletePointsByDocumentId done: collection={}, documentId={}, wait={}, operationId={}, status={}",
                collectionName, documentId, wait, result.operationId(), result.status());
        return result;
    }

    @Override
    public long countPoints(String collectionName) {
        requireCollectionName(collectionName);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("exact", true);

        String url = baseUrl() + "/collections/" + collectionName + "/points/count";
        return parseCount(postJson(url, body, "统计 Qdrant Point 总数"));
    }

    @Override
    public long countPointsByDocumentId(String collectionName, Long documentId) {
        requireCollectionName(collectionName);
        if (documentId == null) {
            throw new IllegalArgumentException("documentId 不能为空（按文档统计向量点必须指定 documentId）");
        }

        ObjectNode body = objectMapper.createObjectNode();
        body.put("exact", true);
        body.set("filter", buildDocumentIdFilter(documentId));

        String url = baseUrl() + "/collections/" + collectionName + "/points/count";
        return parseCount(postJson(url, body, "按 documentId 统计 Qdrant Point"));
    }

    /**
     * 遍历（scroll）某 Collection 的 Point，<b>单页</b>返回。
     *
     * <p><b>Point ID 契约</b>：本方法面向<b>UUID 字符串形式</b>的 Point ID 场景；
     * {@code offset} 为上一页返回的 {@code next_page_offset}（本项目为 UUID 字符串），
     * <b>不要求</b>其为数值，也不做数值化回溯。无符号整数 Point ID 不在本轮契约范围内。</p>
     *
     * @param collectionName 集合名（非空）
     * @param offset         上一页 {@code next_page_offset}；{@code null}/空表示从首页开始
     * @param limit          单页大小，范围 [{@code SCROLL_LIMIT_MIN}, {@code SCROLL_LIMIT_MAX}]
     * @return 单页结果（含本页点列表与下一页 offset）
     */
    @Override
    public ScrollPage scrollPoints(String collectionName, String offset, int limit) {
        requireCollectionName(collectionName);
        if (limit < SCROLL_LIMIT_MIN || limit > SCROLL_LIMIT_MAX) {
            throw new IllegalArgumentException("scroll limit 越界：期望 [" + SCROLL_LIMIT_MIN + ", "
                    + SCROLL_LIMIT_MAX + "]，实际 " + limit);
        }

        ObjectNode body = objectMapper.createObjectNode();
        body.put("limit", limit);
        body.put("with_payload", true);
        body.put("with_vector", false);
        if (offset != null && !offset.isBlank()) {
            body.put("offset", offset);
        }

        String url = baseUrl() + "/collections/" + collectionName + "/points/scroll";
        return parseScrollPage(postJson(url, body, "遍历 Qdrant Point"));
    }

    @Override
    public boolean collectionExists(String collectionName) {
        requireCollectionName(collectionName);

        // 只读端点：GET /collections/{c}/exists。不使用 ensureCollection 判存在性，后者有建库副作用。
        String url = baseUrl() + "/collections/" + collectionName + "/exists";
        String responseBody;
        try {
            responseBody = restClient.get().uri(url).retrieve().body(String.class);
        } catch (HttpStatusCodeException ex) {
            if (ex.getStatusCode().value() == 404) {
                // 老版本 Qdrant 无 /exists 端点，或 Collection 不存在：一律视为不存在。
                return false;
            }
            throw qdrantHttpFailure("查询 Qdrant Collection 是否存在", ex);
        } catch (Exception e) {
            throw qdrantUnavailable("查询 Qdrant Collection 是否存在", e);
        }

        if (responseBody == null) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            return root.path("result").path("exists").asBoolean(false);
        } catch (Exception e) {
            throw new RuntimeException("解析 Qdrant Collection 存在性响应失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<String> listCollections(String namePrefix) {
        String url = baseUrl() + "/collections";
        String responseBody;
        try {
            responseBody = restClient.get().uri(url).retrieve().body(String.class);
        } catch (HttpStatusCodeException ex) {
            throw qdrantHttpFailure("列出 Qdrant Collection", ex);
        } catch (Exception e) {
            throw qdrantUnavailable("列出 Qdrant Collection", e);
        }

        List<String> names = new ArrayList<>();
        if (responseBody == null) {
            return names;
        }
        try {
            JsonNode collections = objectMapper.readTree(responseBody).path("result").path("collections");
            if (!collections.isArray()) {
                return names;
            }
            boolean filtering = namePrefix != null && !namePrefix.isBlank();
            for (JsonNode collection : collections) {
                String name = collection.path("name").asText(null);
                if (name == null || name.isBlank()) {
                    continue;
                }
                if (!filtering || name.startsWith(namePrefix)) {
                    names.add(name);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("解析 Qdrant Collection 列表失败: " + e.getMessage(), e);
        }
        names.sort(String::compareTo);
        return names;
    }

    // ==================== 内部工具方法 ====================

    /**
     * 过滤出合法的 Point ID：跳过 null / 空白 / 非 UUID 字面量，不抛异常。
     *
     * <p>刻意不按 UUID version 过滤——UUID v3 与 v4 都是历史上真实写入过的 Point ID，
     * 都必须能被删除。</p>
     */
    private List<String> filterValidPointIds(List<String> pointIds) {
        if (pointIds == null) {
            return List.of();
        }
        // LinkedHashSet 去重且保留首次出现顺序：重复 ID（如 ["idA","idA","idB"]）会被合并为 2 个，
        // 使 acceptedCount 真实反映"实际将发送给 Qdrant 的去重后 ID 数"，避免重复 ID 被静默双重计数。
        LinkedHashSet<String> deduped = new LinkedHashSet<>();
        for (String pointId : pointIds) {
            if (pointId == null || pointId.isBlank()) {
                log.warn("deletePoints: 跳过空 point id");
                continue;
            }
            String trimmed = pointId.trim();
            if (!UUID_PATTERN.matcher(trimmed).matches()) {
                // 只记录长度，不回显完整 ID 内容，避免把非预期数据原样写进日志。
                log.warn("deletePoints: 跳过非法 point id（非 UUID 字面量），length={}", trimmed.length());
                continue;
            }
            deduped.add(trimmed);
        }
        return new ArrayList<>(deduped);
    }

    /**
     * 构造 documentId 过滤条件：{@code {"must":[{"key":"documentId","match":{"value":<number>}}]}}。
     *
     * <p><b>{@code value} 必须是 JSON number</b>。这里用 {@code ObjectNode#put(String, long)}
     * 而非字符串拼接，从类型层面杜绝写成 {@code "6"} 的可能——Qdrant 的 match 强类型匹配，
     * 字符串 {@code "6"} 命中数为 0，会导致"删除操作成功但一条都没删"的静默不一致。</p>
     */
    private ObjectNode buildDocumentIdFilter(long documentId) {
        ObjectNode match = objectMapper.createObjectNode();
        match.put("value", documentId);

        ObjectNode condition = objectMapper.createObjectNode();
        condition.put("key", PAYLOAD_KEY_DOCUMENT_ID);
        condition.set("match", match);

        ObjectNode filter = objectMapper.createObjectNode();
        filter.putArray("must").add(condition);
        return filter;
    }

    /** 统一的 POST JSON 调用，复用既有 RestClient 与错误分类语义。 */
    private String postJson(String url, ObjectNode body, String action) {
        try {
            return restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .body(String.class);
        } catch (HttpStatusCodeException ex) {
            throw qdrantHttpFailure(action, ex);
        } catch (Exception e) {
            throw qdrantUnavailable(action, e);
        }
    }

    /** Qdrant 在线但拒绝请求（4xx/5xx）：明确报 HTTP 状态码，不误报"不可用"。 */
    private RuntimeException qdrantHttpFailure(String action, HttpStatusCodeException ex) {
        log.error("{}失败：HTTP {}", action, ex.getStatusCode().value(), ex);
        return new RuntimeException("Qdrant 返回错误（HTTP " + ex.getStatusCode().value() + "）："
                + safeBody(ex), ex);
    }

    /** 连接层失败（连接拒绝 / 超时 / DNS 等）：报"Qdrant 不可用"。 */
    private RuntimeException qdrantUnavailable(String action, Exception e) {
        log.error("{}失败：{}", action, e.getMessage(), e);
        return new RuntimeException("Qdrant 不可用，请确保 Qdrant 已启动（端口 " + qdrantPort + "）: "
                + e.getMessage(), e);
    }

    private void requireCollectionName(String collectionName) {
        if (collectionName == null || collectionName.isBlank()) {
            throw new IllegalArgumentException("collectionName 不能为空");
        }
    }

    /**
     * 解析 {@code {"result":{"operation_id":N,"status":"completed"}}}。
     *
     * <p>{@code requestedCount} / {@code acceptedCount} 来自调用方（客户端侧已知事实），
     * 仅透传进返回值用于审计，<b>不作为</b> Qdrant 响应解析的一部分，也<b>不</b>表示删除条数。</p>
     *
     * @param responseBody   Qdrant 原始响应体（非空）
     * @param requestedCount 调用方原始 pointIds 数量（透传审计）
     * @param acceptedCount  经客户端 UUID 校验、去重后实际发送的数量（透传审计）
     */
    private QdrantOperationResult parseOperationResult(String responseBody, int requestedCount, int acceptedCount) {
        if (responseBody == null) {
            throw new IllegalStateException("Qdrant 响应为空，无法解析操作结果");
        }
        try {
            JsonNode result = objectMapper.readTree(responseBody).path("result");
            Long operationId = result.hasNonNull("operation_id") ? result.get("operation_id").asLong() : null;
            String status = result.path("status").asText(null);
            return new QdrantOperationResult(operationId, status, requestedCount, acceptedCount);
        } catch (Exception e) {
            throw new RuntimeException("解析 Qdrant 操作结果失败: " + e.getMessage(), e);
        }
    }

    /** 解析 {@code {"result":{"count":N}}}。 */
    private long parseCount(String responseBody) {
        if (responseBody == null) {
            throw new IllegalStateException("Qdrant 响应为空，无法解析 count");
        }
        try {
            JsonNode count = objectMapper.readTree(responseBody).path("result").path("count");
            if (count.isMissingNode() || !count.isNumber()) {
                throw new IllegalStateException("Qdrant count 响应缺少 result.count 数值字段");
            }
            return count.asLong();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("解析 Qdrant count 响应失败: " + e.getMessage(), e);
        }
    }

    /** 解析 {@code {"result":{"points":[...],"next_page_offset":...}}}。 */
    private ScrollPage parseScrollPage(String responseBody) {
        if (responseBody == null) {
            return ScrollPage.empty();
        }
        try {
            JsonNode result = objectMapper.readTree(responseBody).path("result");
            JsonNode pointsNode = result.path("points");

            List<ScrollPage.ScrollPoint> points = new ArrayList<>();
            if (pointsNode.isArray()) {
                for (JsonNode point : pointsNode) {
                    JsonNode idNode = point.path("id");
                    String id = idNode.isTextual() ? idNode.asText() : idNode.asText(null);
                    Map<String, Object> payload = new LinkedHashMap<>();
                    JsonNode payloadNode = point.path("payload");
                    if (payloadNode.isObject()) {
                        payloadNode.fields().forEachRemaining(
                                entry -> payload.put(entry.getKey(), toJavaValue(entry.getValue())));
                    }
                    points.add(new ScrollPage.ScrollPoint(id, payload));
                }
            }

            JsonNode offsetNode = result.path("next_page_offset");
            String nextOffset = offsetNode.isMissingNode() || offsetNode.isNull() ? null : offsetNode.asText();
            return new ScrollPage(points, nextOffset);
        } catch (Exception e) {
            throw new RuntimeException("解析 Qdrant scroll 响应失败: " + e.getMessage(), e);
        }
    }

    /** 将 payload 中的 JSON 值转为朴素 Java 值，保留数值/布尔的原始类型以便调用方做类型敏感比对。 */
    private Object toJavaValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isFloatingPointNumber()) {
            return node.asDouble();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        return node.toString();
    }
}