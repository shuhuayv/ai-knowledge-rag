package com.shuhuayv.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("kb_document")
@Schema(description = "知识库文档")
public class KbDocument {

    @TableId(type = IdType.AUTO)
    @Schema(description = "文档 ID", example = "1")
    private Long id;

    @Schema(description = "文件名", example = "企业知识库使用手册.pdf")
    private String fileName;

    @Schema(description = "文件类型", example = "PDF")
    private String fileType;

    @Schema(description = "文件存储路径", example = "uploads/企业知识库使用手册.pdf")
    private String filePath;

    @Schema(description = "文件大小（字节）", example = "102400")
    private Long fileSize;

    @Schema(description = "状态：UPLOADED/PARSING/PARSED/FAILED", example = "UPLOADED")
    private String status;

    @Schema(description = "备注", example = "企业知识库核心文档")
    private String remark;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}