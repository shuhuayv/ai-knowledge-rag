package com.shuhuayv.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "文档向量化响应")
public class DocumentIndexResponse {

    @Schema(description = "文档 ID", example = "1")
    private Long documentId;

    @Schema(description = "文档状态", example = "INDEXED")
    private String status;

    @Schema(description = "Chunk 数量", example = "15")
    private int chunkCount;

    @Schema(description = "向量数量", example = "15")
    private int vectorCount;

    @Schema(description = "Qdrant Collection 名称", example = "kb_chunks")
    private String collectionName;

    @Schema(description = "处理消息", example = "文档向量化完成，共写入 15 条向量")
    private String message;
}