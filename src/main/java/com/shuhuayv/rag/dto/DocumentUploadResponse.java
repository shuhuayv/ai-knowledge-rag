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

    /**
     * 是否为内容级重复上传。
     *
     * <p>首次上传该内容返回 {@code false}；当上传文件的 raw-byte SHA-256 与库中某个 active 文档相同时
     * 返回 {@code true}，此时上方 {@code id} 等字段描述的是<b>已存在</b>的那个文档。</p>
     *
     * <p>重复上传<b>不是业务错误</b>：endpoint 仍为 {@code POST /api/documents/upload}，HTTP 状态仍为 200。</p>
     */
    @Schema(description = "是否为内容重复上传（首次上传 false，重复上传 true）", example = "false")
    private boolean duplicate;
}