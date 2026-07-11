package com.shuhuayv.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
@Schema(description = "语义检索响应")
public class SearchResponse {

    @Schema(description = "查询内容", example = "什么是RAG？")
    private String query;

    @Schema(description = "请求的 TopK 数量", example = "5")
    private int topK;

    @Schema(description = "实际返回结果数量", example = "3")
    private int resultCount;

    @Schema(description = "检索结果列表")
    private List<SearchResultItem> results;

    @Schema(description = "检索耗时（毫秒）", example = "123")
    private long costMs;

    @Schema(description = "过滤前候选结果数量（min-score 过滤前）", example = "5")
    private int retrievalCandidateCount;

    @Schema(description = "过滤后实际返回结果数量（score >= minScore 后）", example = "3")
    private int retrievalReturnedCount;
}