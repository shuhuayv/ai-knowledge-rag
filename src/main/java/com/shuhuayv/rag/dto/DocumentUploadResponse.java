package com.shuhuayv.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
@Schema(description = "文档上传响应")
public class DocumentUploadResponse {

    @Schema(description = "文档 ID", example = "1")
    private Long id;

    @Schema(description = "文件名", example = "企业知识库使用手册.pdf")
    private String fileName;

    @Schema(description = "文件类型", example = "PDF")
    private String fileType;

    @Schema(description = "文件大小（字节）", example = "102400")
    private Long fileSize;

    @Schema(description = "状态", example = "UPLOADED")
    private String status;

    @Schema(description = "上传时间")
    private LocalDateTime createdAt;
}