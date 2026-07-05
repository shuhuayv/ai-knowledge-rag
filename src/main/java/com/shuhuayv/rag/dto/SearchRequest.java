package com.shuhuayv.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "语义检索请求")
public class SearchRequest {

    @NotBlank(message = "查询内容不能为空")
    @Schema(description = "查询内容", requiredMode = Schema.RequiredMode.REQUIRED, example = "什么是RAG？")
    private String query;

    @Schema(description = "返回结果数量，默认 5，最小 1，最大 20", defaultValue = "5", example = "5")
    private Integer topK = 5;

    public int getEffectiveTopK() {
        if (topK == null || topK < 1) return 5;
        return Math.min(topK, 20);
    }
}