package com.shuhuayv.rag.service.impl;

import com.shuhuayv.rag.service.ChatModelService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongConsumer;

/**
 * OpenAI-compatible Chat Completions API 实现。
 * 支持智谱 AI、阿里百炼、DeepSeek、火山方舟等兼容 OpenAI 接口的平台。
 * 当 ai.mock-enabled=false 时启用。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "ai.mock-enabled", havingValue = "false")
public class OpenAiCompatibleChatModelServiceImpl implements ChatModelService {

    private final RestClient restClient;
    private final String provider;
    private final String model;
    private final String apiBaseUrl;
    private final double temperature;
    private final int maxTokens;
    private final String thinkingType;
    private final int maxRetries;
    private final Semaphore chatPermit = new Semaphore(1, true);
    /** 限流退避等待策略（可注入，便于单测；默认 Thread.sleep）。 */
    private LongConsumer backoffSleeper = ms -> {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("等待 AI 限流重试时被中断", e);
        }
    };

    /** 仅供单测注入零等待 / 计数型 sleeper，避免真实 sleep 拖慢测试。 */
    void setBackoffSleeper(LongConsumer sleeper) {
        this.backoffSleeper = sleeper;
    }

    public OpenAiCompatibleChatModelServiceImpl(
            @Value("${ai.api-key:${ZHIPU_API_KEY:}}") String apiKey,
            @Value("${ai.provider:zhipu}") String provider,
            @Value("${ai.model:glm-4.5-air}") String model,
            @Value("${ai.api-base-url:https://open.bigmodel.cn/api/paas/v4}") String apiBaseUrl,
            @Value("${ai.temperature:0.3}") double temperature,
            @Value("${ai.max-tokens:4096}") int maxTokens,
            @Value("${ai.thinking-type:disabled}") String thinkingType,
            @Value("${ai.chat-max-retries:3}") int maxRetries,
            @Value("${ai.timeout-seconds:90}") int timeoutSeconds) {

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException(
                    "真实 AI 模式需要配置 API Key。请设置环境变量 ZHIPU_API_KEY 或 AI_API_KEY。");
        }

        this.provider = provider;
        this.model = model;
        this.apiBaseUrl = apiBaseUrl.endsWith("/") ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1) : apiBaseUrl;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.thinkingType = "enabled".equalsIgnoreCase(thinkingType) ? "enabled" : "disabled";
        this.maxRetries = Math.max(0, Math.min(maxRetries, 5));

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(timeoutSeconds));
        requestFactory.setReadTimeout(Duration.ofSeconds(timeoutSeconds * 2));

        this.restClient = RestClient.builder()
                .baseUrl(this.apiBaseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();

        log.info("OpenAI-compatible Chat Model initialized, provider={}, model={}, baseUrl={}, timeout={}s",
                provider, model, this.apiBaseUrl, timeoutSeconds);
    }

    @Override
    public String generateAnswer(String prompt) {
        boolean acquired = false;
        try {
            // 本地只允许一个 Chat 请求占用智谱并发额度，避免用户连续点击触发 1302。
            chatPermit.acquire();
            acquired = true;
            return generateAnswerWithRetry(prompt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("等待 Chat 请求队列时被中断", e);
        } finally {
            if (acquired) {
                chatPermit.release();
            }
        }
    }

    private String generateAnswerWithRetry(String prompt) {
        Map<String, Object> requestBody = buildRequestBody(prompt);
        RuntimeException lastError = null;

        for (int attempt = 1; attempt <= maxRetries + 1; attempt++) {
            long startTime = System.currentTimeMillis();
            try {
                String response = restClient.post()
                        .uri("/chat/completions")
                        .body(requestBody)
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, (req, resp) -> {
                            String errorBody = new String(resp.getBody().readAllBytes());
                            log.error("AI API error, status={}, body={}", resp.getStatusCode(), errorBody);
                            throw new RuntimeException("AI API 调用失败，HTTP " + resp.getStatusCode() + ": " + errorBody);
                        })
                        .body(String.class);

                long costMs = System.currentTimeMillis() - startTime;
                String answer = extractContent(response);
                log.info("AI Chat completed, provider={}, model={}, attempt={}, costMs={}, answerLength={}",
                        provider, model, attempt, costMs, answer.length());
                return answer;

            } catch (RuntimeException e) {
                lastError = e;
                if (!isRateLimitError(e) || attempt > maxRetries) {
                    throw e;
                }
                long delayMs = retryDelayMs(attempt);
                log.warn("AI Chat rate limited, provider={}, model={}, attempt={}/{}, retryAfterMs={}",
                        provider, model, attempt, maxRetries + 1, delayMs);
                backoffSleeper.accept(delayMs);
            } catch (Exception e) {
                long costMs = System.currentTimeMillis() - startTime;
                log.error("AI API call failed, provider={}, model={}, costMs={}", provider, model, costMs, e);
                throw new RuntimeException("AI API 调用失败: " + e.getMessage(), e);
            }
        }
        throw lastError != null ? lastError : new RuntimeException("AI API 调用失败");
    }

    private boolean isRateLimitError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("429")
                        || normalized.contains("1302")
                        || normalized.contains("1305")
                        || normalized.contains("too_many_requests")
                        || normalized.contains("速率限制")
                        || normalized.contains("请求频率")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private long retryDelayMs(int attempt) {
        long baseMs = switch (attempt) {
            case 1 -> 5_000L;
            case 2 -> 12_000L;
            default -> 25_000L;
        };
        return baseMs + ThreadLocalRandom.current().nextLong(1_000L, 3_001L);
    }

    private void sleepBeforeRetry(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("等待 AI 限流重试时被中断", e);
        }
    }

    @Override
    public String getProvider() {
        return provider;
    }

    @Override
    public String getModel() {
        return model;
    }

    private Map<String, Object> buildRequestBody(String prompt) {
        List<Map<String, String>> messages = List.of(
                Map.of(
                        "role", "system",
                        "content", "你是一个严谨的企业知识库问答助手。请优先依据给定参考资料回答，不要编造。"
                ),
                Map.of(
                        "role", "user",
                        "content", prompt
                )
        );

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", messages);
        requestBody.put("temperature", temperature);
        requestBody.put("max_tokens", maxTokens);

        // GLM-4.7 默认会进行深度思考。RAG 演示优先稳定输出最终答案，
        // 避免推理内容耗尽 max_tokens 后 message.content 为空。
        if ("zhipu".equalsIgnoreCase(provider)) {
            requestBody.put("thinking", Map.of("type", thinkingType));
        }
        return requestBody;
    }

    @SuppressWarnings("unchecked")
    private String extractContent(String responseBody) {
        try {
            // 简单 JSON 解析，避免引入 Jackson 依赖（Spring Boot 已自带）
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> responseMap = mapper.readValue(responseBody, Map.class);

            List<Map<String, Object>> choices = (List<Map<String, Object>>) responseMap.get("choices");
            if (choices == null || choices.isEmpty()) {
                log.warn("AI API response has no choices, response={}", responseBody);
                throw new RuntimeException("AI API 返回结果为空，未包含 choices");
            }

            Map<String, Object> firstChoice = choices.get(0);
            Map<String, Object> message = (Map<String, Object>) firstChoice.get("message");
            if (message == null) {
                log.warn("AI API response has no message in choice, response={}", responseBody);
                throw new RuntimeException("AI API 返回结果格式异常，缺少 message 字段");
            }

            String content = (String) message.get("content");
            if (content == null || content.isBlank()) {
                log.warn("AI API response content is empty, response={}", responseBody);
                throw new RuntimeException("AI API 返回内容为空");
            }

            return content;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to parse AI API response, body={}", responseBody, e);
            throw new RuntimeException("AI API 响应解析失败: " + e.getMessage(), e);
        }
    }
}