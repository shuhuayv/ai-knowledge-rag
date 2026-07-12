package com.shuhuayv.rag.service.impl;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OpenAiCompatibleChatModelServiceImpl 限流退避单元测试（MockWebServer 模拟 Chat API，无真实 Key / 网络）。
 *
 * <p>通过 {@code setBackoffSleeper} 注入零等待 sleeper，验证：
 * <ul>
 *   <li>429 命中限流识别并退避重试一次后成功；</li>
 *   <li>400（非限流 4xx）不重试、直接失败。</li>
 * </ul>
 * </p>
 */
class OpenAiCompatibleChatModelServiceImplTest {

    private MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private OpenAiCompatibleChatModelServiceImpl service(int maxRetries, LongConsumer sleeper) {
        OpenAiCompatibleChatModelServiceImpl s = new OpenAiCompatibleChatModelServiceImpl(
                "fake-test-key",
                "zhipu",
                "glm-4.5-air",
                server.url("/").toString(),
                0.3,
                100,
                "disabled",
                maxRetries,
                5);
        s.setBackoffSleeper(sleeper);
        return s;
    }

    private void enqueue200(String content) {
        server.enqueue(new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"content\":\"" + content + "\"}}]}"));
    }

    @Test
    void shouldRetryOn429ThenSucceed() {
        AtomicInteger sleepCount = new AtomicInteger();
        LongConsumer sleeper = ms -> sleepCount.incrementAndGet();

        server.enqueue(new MockResponse().setResponseCode(429)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"code\":\"rate_limit\",\"message\":\"rate limited\"}}"));
        enqueue200("answer after retry");

        OpenAiCompatibleChatModelServiceImpl s = service(2, sleeper);
        String answer = s.generateAnswer("hi");

        assertThat(answer).isEqualTo("answer after retry");
        assertThat(server.getRequestCount()).isEqualTo(2);
        assertThat(sleepCount.get()).isEqualTo(1);
    }

    @Test
    void shouldNotRetryOnNonRateLimit400() {
        AtomicInteger sleepCount = new AtomicInteger();
        LongConsumer sleeper = ms -> sleepCount.incrementAndGet();

        server.enqueue(new MockResponse().setResponseCode(400)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"code\":\"invalid\",\"message\":\"bad request\"}}"));

        OpenAiCompatibleChatModelServiceImpl s = service(2, sleeper);
        assertThatThrownBy(() -> s.generateAnswer("hi")).isInstanceOf(RuntimeException.class);
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(sleepCount.get()).isEqualTo(0);
    }
}
