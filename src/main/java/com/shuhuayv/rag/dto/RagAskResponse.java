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

    @Schema(description = "AI Provider 标识", example = "mock")
    private String provider;

    @Schema(description = "AI 模型名称", example = "glm-4.7-flash")
    private String model;

    // ===== 检索透明性增强字段（保留全部旧字段） =====

    @Schema(description = "Embedding Provider（mock / zhipu）", example = "zhipu")
    private String embeddingProvider;

    @Schema(description = "Embedding 模型（embedding-3 / mock）", example = "embedding-3")
    private String embeddingModel;

    @Schema(description = "Embedding 维度（384=mock, 1024=zhipu）", example = "1024")
    private Integer embeddingDimensions;

    @Schema(description = "Embedding 模式（MOCK / REAL）", example = "REAL")
    private String embeddingMode;

    @Schema(description = "检索使用的 Qdrant Collection 名称", example = "kb_chunks_zhipu_embedding_3_1024_v1")
    private String collectionName;

    @Schema(description = "检索 TopK（同 topK，检索诊断用）", example = "5")
    private Integer retrievalTopK;

    @Schema(description = "最小相似度阈值（rag.retrieval.min-score）", example = "0.0")
    private Double retrievalMinScore;

    @Schema(description = "min-score 过滤前候选数量", example = "5")
    private Integer retrievalCandidateCount;

    @Schema(description = "min-score 过滤后返回数量", example = "3")
    private Integer retrievalReturnedCount;

    @Schema(description = "是否发生降级（fallback）。默认 false，缺 Key 时明确失败不降级。", example = "false")
    private Boolean fallbackUsed;

    @Schema(description = "检索质量说明（如：无有效上下文 / 候选低于阈值被过滤 / 正常）", example = "正常")
    private String retrievalQualityNote;
}