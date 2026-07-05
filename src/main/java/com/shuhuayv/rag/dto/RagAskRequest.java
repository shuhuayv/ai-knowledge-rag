package com.shuhuayv.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "RAG 问答请求")
public class RagAskRequest {

    @NotBlank(message = "问题不能为空")
    @Schema(description = "用户问题", requiredMode = Schema.RequiredMode.REQUIRED, example = "这份文档主要讲了什么？")
    private String question;

    @Min(1)
    @Max(20)
    @Schema(description = "检索 TopK 数量，默认 5，范围 1-20", defaultValue = "5", example = "5")
    private Integer topK = 5;

    public int getEffectiveTopK() {
        if (topK == null || topK < 1) return 5;
        return Math.min(topK, 20);
    }
}