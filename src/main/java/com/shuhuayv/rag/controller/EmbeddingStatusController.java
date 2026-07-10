package com.shuhuayv.rag.controller;

import com.shuhuayv.rag.common.ApiResponse;
import com.shuhuayv.rag.embedding.service.EmbeddingService;
import com.shuhuayv.rag.service.impl.DocumentIndexServiceImpl;
import com.shuhuayv.rag.vector.service.CollectionNameResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Embedding 状态接口。
 *
 * <p>用于透明地暴露当前 Embedding 配置（provider / model / dimensions / mode / collectionName /
 * fallbackEnabled / apiKeyConfigured），便于诊断与演示。</p>
 *
 * <p>安全边界：
 * <ul>
 *   <li>绝不返回 API Key 本身、长度、前缀或后缀。</li>
 *   <li>{@code apiKeyConfigured} 仅根据环境变量是否存在判断（不持有 Key）。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Tag(name = "Embedding 状态接口", description = "查询当前 Embedding 配置与可用性（不暴露任何 Key）")
@RestController
@RequestMapping("/api/embedding")
public class EmbeddingStatusController {

    /** 索引版本，用于解析当前 Collection 名称。 */
    private static final String INDEX_VERSION = DocumentIndexServiceImpl.INDEX_VERSION;

    private final EmbeddingService embeddingService;
    private final CollectionNameResolver collectionNameResolver;
    private final boolean fallbackEnabled;

    public EmbeddingStatusController(EmbeddingService embeddingService,
                                     CollectionNameResolver collectionNameResolver,
                                     @Value("${ai.embedding.fallback-enabled:false}") boolean fallbackEnabled) {
        this.embeddingService = embeddingService;
        this.collectionNameResolver = collectionNameResolver;
        this.fallbackEnabled = fallbackEnabled;
    }

    @Operation(summary = "查询 Embedding 状态",
            description = "返回 provider/model/dimensions/mode/collectionName/fallbackEnabled/apiKeyConfigured。"
                    + "绝不返回 API Key 本身、长度、前缀或后缀。")
    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        String collectionName = collectionNameResolver.resolveForCurrentMode(embeddingService, INDEX_VERSION);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("provider", embeddingService.provider());
        data.put("model", embeddingService.model());
        data.put("dimensions", embeddingService.dimensions());
        data.put("mode", embeddingService.mode().name());
        data.put("collectionName", collectionName);
        data.put("fallbackEnabled", fallbackEnabled);
        data.put("apiKeyConfigured", computeApiKeyConfigured());

        return ApiResponse.success(data);
    }

    /**
     * 计算 API Key 是否已配置（仅判断环境变量是否存在，绝不持有/打印 Key）。
     */
    protected boolean computeApiKeyConfigured() {
        return System.getenv("ZHIPU_API_KEY") != null || System.getenv("AI_API_KEY") != null;
    }
}
