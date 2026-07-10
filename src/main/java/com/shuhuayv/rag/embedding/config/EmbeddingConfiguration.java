package com.shuhuayv.rag.embedding.config;

import com.shuhuayv.rag.embedding.service.EmbeddingService;
import com.shuhuayv.rag.embedding.service.impl.MockEmbeddingServiceImpl;
import com.shuhuayv.rag.embedding.service.impl.ZhipuEmbeddingServiceImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Embedding 服务装配配置。
 *
 * <p>依据 {@code ai.embedding.provider} 选择唯一一个 {@link EmbeddingService} Bean：
 * <ul>
 *   <li>{@code mock}（默认，缺省即 mock）：本地 SHA-256 伪向量，无需 Key。</li>
 *   <li>{@code zhipu}：真实智谱 Embedding API；构造前校验 Key，缺 Key 明确失败。</li>
 * </ul>
 * </p>
 *
 * <p>两个 {@code @Bean} 均带 {@code @ConditionalOnProperty}，保证 Spring 上下文中恰好存在一个
 * EmbeddingService Bean，避免歧义。</p>
 */
@Configuration
public class EmbeddingConfiguration {

    @Bean
    @ConditionalOnProperty(name = "ai.embedding.provider", havingValue = "mock", matchIfMissing = true)
    public EmbeddingService mockEmbeddingService(
            @Value("${app.embedding.dimension:384}") int vectorDimension) {
        return new MockEmbeddingServiceImpl(vectorDimension);
    }

    @Bean
    @ConditionalOnProperty(name = "ai.embedding.provider", havingValue = "zhipu")
    public EmbeddingService zhipuEmbeddingService(
            @Value("${ai.embedding.api-key:}") String apiKey,
            @Value("${ai.embedding.endpoint:https://open.bigmodel.cn/api/paas/v4/embeddings}") String endpoint,
            @Value("${ai.embedding.model:embedding-3}") String model,
            @Value("${ai.embedding.dimensions:1024}") int dimensions,
            @Value("${ai.embedding.batch-size:16}") int batchSize,
            @Value("${ai.embedding.max-retries:2}") int maxRetries,
            @Value("${ai.embedding.connect-timeout-seconds:5}") int connectTimeoutSeconds,
            @Value("${ai.embedding.read-timeout-seconds:30}") int readTimeoutSeconds) {

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException(
                    "真实 Embedding 模式（zhipu）需要配置 API Key。请设置环境变量 AI_API_KEY 或 ZHIPU_API_KEY。");
        }

        // 超时配置校验：构建 RestClient 前必须校验，非法直接启动失败（不静默 clamp）
        if (connectTimeoutSeconds <= 0) {
            throw new IllegalArgumentException(
                    "ai.embedding.connect-timeout-seconds 必须为正数，当前：" + connectTimeoutSeconds);
        }
        if (readTimeoutSeconds <= 0) {
            throw new IllegalArgumentException(
                    "ai.embedding.read-timeout-seconds 必须为正数，当前：" + readTimeoutSeconds);
        }

        // endpoint 形如 https://open.bigmodel.cn/api/paas/v4/embeddings，去掉尾部 /embeddings 作为 baseUrl
        String baseUrl = endpoint;
        if (baseUrl != null && baseUrl.endsWith("/embeddings")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - "/embeddings".length());
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("真实 Embedding endpoint 配置为空");
        }

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(connectTimeoutSeconds));
        requestFactory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));

        RestClient restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();

        return new ZhipuEmbeddingServiceImpl(
                restClient, model, dimensions, batchSize, maxRetries);
    }
}
