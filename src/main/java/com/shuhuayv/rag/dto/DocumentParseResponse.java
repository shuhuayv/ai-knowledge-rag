package com.shuhuayv.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "文档解析响应")
public class DocumentParseResponse {

    @Schema(description = "文档 ID", example = "1")
    private Long documentId;

    @Schema(description = "文件名", example = "企业知识库使用手册.pdf")
    private String fileName;

    @Schema(description = "文档状态", example = "PARSED")
    private String status;

    @Schema(description = "Chunk 数量", example = "15")
    private int chunkCount;

    @Schema(description = "处理消息", example = "文档解析并切分完成")
    private String message;
}