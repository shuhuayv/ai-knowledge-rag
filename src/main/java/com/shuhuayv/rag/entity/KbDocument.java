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

    // ===== 真实 Embedding 元数据（索引透明性增强） =====
    @Schema(description = "向量化使用的 Embedding Provider（mock / zhipu）", example = "zhipu")
    private String embeddingProvider;

    @Schema(description = "向量化使用的 Embedding 模型（embedding-3 / mock）", example = "embedding-3")
    private String embeddingModel;

    @Schema(description = "向量维度（384=mock, 1024=zhipu）", example = "1024")
    private Integer embeddingDimensions;

    @Schema(description = "向量所在 Qdrant Collection 名称", example = "kb_chunks_zhipu_embedding_3_1024_v1")
    private String vectorCollection;

    @Schema(description = "索引版本（用于确定性 pointId 与重索引识别）", example = "v1")
    private String indexVersion;

    @Schema(description = "最近一次成功索引时间")
    private LocalDateTime indexedAt;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}