package com.shuhuayv.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
@Schema(description = "RAG 问答响应")
public class RagAskResponse {

    @Schema(description = "用户问题", example = "这份文档主要讲了什么？")
    private String question;

    @Schema(description = "AI 回答", example = "根据已检索到的文档片段，这份文档主要介绍了...")
    private String answer;

    @Schema(description = "检索 TopK 数量", example = "5")
    private int topK;

    @Schema(description = "引用来源数量", example = "3")
    private int referenceCount;

    @Schema(description = "引用来源列表")
    private List<RagReferenceItem> references;

    @Schema(description = "Prompt 预览（前 500 字符）", example = "你是企业知识库问答助手...")
    private String promptPreview;

    @Schema(description = "问答耗时（毫秒）", example = "456")
    private long costMs;
}