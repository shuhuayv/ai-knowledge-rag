package com.shuhuayv.rag.embedding.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonParser;
import com.shuhuayv.rag.embedding.service.EmbeddingMode;
import com.shuhuayv.rag.embedding.service.EmbeddingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongConsumer;

/**
 * 智谱（Zhipu）真实 Embedding 实现。
 *
 * <p>调用 OpenAI-compatible 的 Embeddings API（默认 {@code /embeddings}，模型 {@code embedding-3}，1024 维）。
 * 构造器接收已构建好的 {@link RestClient}（其内部已携带 Bearer Key 与超时配置），便于在单测中注入
 * MockWebServer 的 RestClient，从而隔离真实网络与真实 Key。</p>
 *
 * <p>关键行为：
 * <ul>
 *   <li>批量：按 batch-size（默认 16）分批，单批上限 64。</li>
 *   <li>重试：对 429 / 5xx 指数退避重试（默认 maxRetries=2，退避 500ms * 2^attempt）。</li>
 *   <li>不重试：所有 4xx（含 400/401/403/404/405/409/422 等）直接抛异常；仅 429 / 5xx 才重试。</li>
 *   <li>响应校验：data 数量、index 范围/唯一性、向量维度、NaN/Inf 全部校验通过才返回；任一失败整批抛异常。</li>
 * </ul>
 * </p>
 *
 * <p>安全：Key 仅存在于所注入的 RestClient 头中，本类不持有、不打印、不落库任何 Key。</p>
 */
@Slf4j
public class ZhipuEmbeddingServiceImpl implements EmbeddingService {

    /** 智谱 Embeddings 接口路径（相对 RestClient baseUrl）。 */
    private static final String EMBEDDINGS_URI = "/embeddings";

    /** 单批最大条数上限（防止超过上游限制）。 */
    private static final int MAX_BATCH_SIZE = 64;

    /** embedding-3 允许的维度集合。 */
    private static final Set<Integer> ALLOWED_EMBEDDING3_DIMENSIONS = Set.of(256, 512, 1024, 2048);

    /** 重试退避基数（毫秒）。 */
    private static final long RETRY_BASE_BACKOFF_MS = 500L;

    private final RestClient restClient;
    private final String model;
    private final int dimensions;
    private final int batchSize;
    private final int maxRetries;
    private final ObjectMapper objectMapper;
    /** 限流退避等待策略（可注入，便于单测；默认 Thread.sleep）。 */
    private final LongConsumer backoffSleeper;

    /**
     * 默认构造（向后兼容）：使用 {@link Thread#sleep(long)} 作为退避等待策略。
     */
    public ZhipuEmbeddingServiceImpl(RestClient restClient,
                                     String model,
                                     int dimensions,
                                     int batchSize,
                                     int maxRetries) {
        this(restClient, model, dimensions, batchSize, maxRetries, defaultSleeper());
    }

    /**
     * 可注入退避策略的构造：便于在单测中传入零等待 / 计数型 sleeper，避免真实 sleep 拖慢测试。
     */
    public ZhipuEmbeddingServiceImpl(RestClient restClient,
                                     String model,
                                     int dimensions,
                                     int batchSize,
                                     int maxRetries,
                                     LongConsumer backoffSleeper) {
        if (restClient == null) {
            throw new IllegalArgumentException("RestClient 不能为空（真实 Embedding 需要有效的 API 配置与 Key）");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Embedding 模型名称不能为空");
        }
        if (dimensions <= 0) {
            throw new IllegalArgumentException("Embedding 维度必须为正数，当前：" + dimensions);
        }
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "batchSize 必须在 [1, " + MAX_BATCH_SIZE + "] 范围内，当前：" + batchSize);
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries 必须 >= 0，当前：" + maxRetries);
        }
        if ("embedding-3".equals(model) && !ALLOWED_EMBEDDING3_DIMENSIONS.contains(dimensions)) {
            throw new IllegalArgumentException(
                    "embedding-3 维度必须在 " + ALLOWED_EMBEDDING3_DIMENSIONS + " 中，当前：" + dimensions);
        }
        this.restClient = restClient;
        this.model = model;
        this.dimensions = dimensions;
        this.batchSize = batchSize;
        this.maxRetries = maxRetries;
        this.backoffSleeper = backoffSleeper != null ? backoffSleeper : defaultSleeper();
        this.objectMapper = new ObjectMapper();
        // 允许解析 NaN/Infinity 字面量，以便随后显式校验并拒绝（真实 API 不应返回此类值）。
        this.objectMapper.enable(JsonParser.Feature.ALLOW_NON_NUMERIC_NUMBERS);
    }

    /** 默认退避等待策略：Thread.sleep，遇到中断时恢复中断标志并抛出异常。 */
    private static LongConsumer defaultSleeper() {
        return ms -> {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Embedding 重试被中断", ie);
            }
        };
    }

    @Override
    public List<Float> embed(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("embed 的输入文本不能为 null/空串/空白");
        }
        return embedBatch(List.of(text)).get(0);
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts) {
        if (texts == null) {
            throw new IllegalArgumentException("embedBatch 的输入列表不能为 null");
        }
        if (texts.isEmpty()) {
            return List.of();
        }
        for (int i = 0; i < texts.size(); i++) {
            String t = texts.get(i);
            if (t == null || t.isBlank()) {
                throw new IllegalArgumentException(
                        "embedBatch 第 " + i + " 个元素为空（null/空串/空白），已拒绝发送");
            }
        }

        int effectiveBatchSize = Math.min(batchSize, MAX_BATCH_SIZE);

        List<List<Float>> all = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += effectiveBatchSize) {
            List<String> subBatch = texts.subList(i, Math.min(i + effectiveBatchSize, texts.size()));
            List<List<Float>> batchResult = embedBatchWithRetry(subBatch);
            all.addAll(batchResult);
        }
        return all;
    }

    @Override
    public String provider() {
        return "zhipu";
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public EmbeddingMode mode() {
        return EmbeddingMode.REAL;
    }

    @Override
    public boolean real() {
        return true;
    }

    /**
     * 带重试地调用 Embeddings API 处理一个子批次。
     * 对 429 / 5xx 指数退避重试，超过 maxRetries 后抛出；对所有 4xx 不重试直接抛出。
     */
    private List<List<Float>> embedBatchWithRetry(List<String> batch) {
        int attempt = 0;
        RuntimeException lastError = null;
        while (attempt <= maxRetries) {
            try {
                return callEmbeddingsApi(batch);
            } catch (NonRetryableEmbeddingException e) {
                // 400/401/403：明确不重试
                throw e;
            } catch (RetryableEmbeddingException e) {
                lastError = e;
                if (attempt >= maxRetries) {
                    break;
                }
                long backoff = RETRY_BASE_BACKOFF_MS * (1L << attempt); // 500 * 2^attempt
                log.warn("智谱 Embedding 调用可重试失败（第 {} 次），{}ms 后重试：{}",
                        attempt + 1, backoff, e.getMessage());
                backoffSleeper.accept(backoff);
                attempt++;
            }
        }
        throw new RuntimeException("智谱 Embedding 调用失败，已重试 " + maxRetries + " 次：" + lastError.getMessage(), lastError);
    }

    /**
     * 调用单次 Embeddings API 并解析校验响应。
     */
    private List<List<Float>> callEmbeddingsApi(List<String> batch) {
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "input", batch,
                "dimensions", dimensions
        );

        String responseBody;
        try {
            responseBody = restClient.post()
                    .uri(EMBEDDINGS_URI)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, resp) -> {
                        int code = resp.getStatusCode().value();
                        String errorBody;
                        try {
                            errorBody = new String(resp.getBody().readAllBytes());
                        } catch (Exception ex) {
                            errorBody = "<response body unreadable>";
                        }
                        // 分类：429 / 5xx 可重试；其余（含所有 4xx）不可重试
                        boolean retryable = (code == 429) || (code >= 500 && code <= 599);
                        String safeMessage = extractErrorMessage(errorBody, code);
                        if (retryable) {
                            throw new RetryableEmbeddingException(safeMessage);
                        }
                        throw new NonRetryableEmbeddingException(safeMessage);
                    })
                    .body(String.class);
        } catch (NonRetryableEmbeddingException | RetryableEmbeddingException e) {
            throw e;
        } catch (Exception e) {
            // 网络异常等也视为可重试
            throw new RetryableEmbeddingException("智谱 Embedding API 调用异常：" + e.getMessage(), e);
        }

        return parseAndValidate(responseBody, batch.size());
    }

    /**
     * 解析响应并按校验清单恢复顺序。
     *
     * @throws RuntimeException 任一校验失败则整批抛异常（不写 Qdrant 的责任由调用方控制）
     */
    private List<List<Float>> parseAndValidate(String responseBody, int inputSize) {
        if (responseBody == null || responseBody.isBlank()) {
            throw new RuntimeException("智谱 Embedding 响应为空");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (Exception e) {
            throw new RuntimeException("智谱 Embedding 响应 JSON 解析失败：" + e.getMessage(), e);
        }

        JsonNode data = root.get("data");
        if (data == null || !data.isArray()) {
            throw new RuntimeException("智谱 Embedding 响应缺少 data 数组");
        }
        if (data.size() != inputSize) {
            throw new RuntimeException("智谱 Embedding 返回数量与输入不一致：返回 " + data.size() + "，输入 " + inputSize);
        }

        List<Float>[] ordered = new List[inputSize];
        Set<Integer> seenIndices = new HashSet<>();
        for (JsonNode item : data) {
            JsonNode indexNode = item.get("index");
            if (indexNode == null) {
                throw new RuntimeException("智谱 Embedding 返回项缺少 index 字段");
            }
            int index = indexNode.asInt();
            if (index < 0 || index >= inputSize) {
                throw new RuntimeException("智谱 Embedding 返回 index 越界：" + index + "（应在 [0," + (inputSize - 1) + "]）");
            }
            if (!seenIndices.add(index)) {
                throw new RuntimeException("智谱 Embedding 返回 index 重复：" + index);
            }

            JsonNode embedding = item.get("embedding");
            if (embedding == null || !embedding.isArray()) {
                throw new RuntimeException("智谱 Embedding 返回项（index=" + index + "）的 embedding 为空");
            }
            if (embedding.size() != dimensions) {
                throw new RuntimeException("智谱 Embedding 维度不匹配：返回 " + embedding.size() + "，期望 " + dimensions);
            }

            List<Float> vector = new ArrayList<>(dimensions);
            for (JsonNode valueNode : embedding) {
                float value = (float) valueNode.asDouble();
                if (Float.isNaN(value) || Float.isInfinite(value)) {
                    throw new RuntimeException("智谱 Embedding 返回包含 NaN/Inf（index=" + index + "）");
                }
                vector.add(value);
            }
            ordered[index] = vector;
        }

        for (int i = 0; i < inputSize; i++) {
            if (ordered[i] == null) {
                throw new RuntimeException("智谱 Embedding 响应缺少 index=" + i + " 的结果");
            }
        }

        return Arrays.asList(ordered);
    }

    /**
     * 从错误响应体提取一条**安全**的简明错误信息。
     *
     * <p>仅包含 HTTP 状态码 + error.code + 截断到 ≤500 字的 error.message；
     * 绝不包含原始 error body、请求体、Authorization 或任何 API Key。</p>
     */
    private String extractErrorMessage(String errorBody, int code) {
        String errorCode = "";
        String errorMessage = "";
        try {
            JsonNode root = objectMapper.readTree(errorBody);
            JsonNode errorNode = root.get("error");
            if (errorNode != null && errorNode.isObject()) {
                JsonNode codeNode = errorNode.get("code");
                if (codeNode != null) {
                    errorCode = codeNode.asText("");
                }
                JsonNode messageNode = errorNode.get("message");
                if (messageNode != null) {
                    errorMessage = messageNode.asText("");
                }
            } else {
                JsonNode messageNode = root.get("message");
                if (messageNode != null) {
                    errorMessage = messageNode.asText("");
                }
            }
        } catch (Exception ex) {
            // 解析失败时不泄漏原始 body，errorCode/errorMessage 保持为空
        }
        if (errorMessage.length() > 500) {
            errorMessage = errorMessage.substring(0, 500);
        }
        StringBuilder sb = new StringBuilder("智谱 Embedding API 返回错误 HTTP ").append(code);
        if (!errorCode.isEmpty()) {
            sb.append("，error.code=").append(errorCode);
        }
        if (!errorMessage.isEmpty()) {
            sb.append("，error.message=").append(errorMessage);
        }
        return sb.toString();
    }

    /** 不可重试异常（所有 4xx 及非 429/5xx 的其他错误）。 */
    private static final class NonRetryableEmbeddingException extends RuntimeException {
        NonRetryableEmbeddingException(String message) {
            super(message);
        }
    }

    /** 可重试异常（429 / 5xx / 网络异常）。 */
    private static final class RetryableEmbeddingException extends RuntimeException {
        RetryableEmbeddingException(String message) {
            super(message);
        }

        RetryableEmbeddingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
