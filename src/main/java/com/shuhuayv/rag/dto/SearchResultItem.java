package com.shuhuayv.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "检索结果项")
public class SearchResultItem {

    @Schema(description = "文档 ID", example = "1")
    private Long documentId;

    @Schema(description = "Chunk ID", example = "2")
    private Long chunkId;

    @Schema(description = "Chunk 序号", example = "0")
    private Integer chunkIndex;

    @Schema(description = "Chunk 内容", example = "RAG（Retrieval-Augmented Generation）是一种...")
    private String content;

    @Schema(description = "相似度分数", example = "0.82")
    private Double score;

    @Schema(description = "Qdrant Collection 名称", example = "kb_chunks")
    private String collectionName;
}