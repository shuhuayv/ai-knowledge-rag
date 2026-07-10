package com.shuhuayv.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("kb_vector_record")
@Schema(description = "向量记录")
public class KbVectorRecord {

    @TableId(type = IdType.AUTO)
    @Schema(description = "向量记录 ID", example = "1")
    private Long id;

    @Schema(description = "关联文档 ID", example = "1")
    private Long documentId;

    @Schema(description = "关联 Chunk ID", example = "1")
    private Long chunkId;

    @Schema(description = "Qdrant Point ID", example = "550e8400-e29b-41d4-a716-446655440000")
    private String qdrantPointId;

    @Schema(description = "Qdrant Collection 名称", example = "kb_chunks")
    private String collectionName;

    @Schema(description = "向量维度", example = "384")
    private Integer vectorDimension;

    // ===== 真实 Embedding 元数据（索引透明性增强） =====
    @Schema(description = "Embedding Provider（mock / zhipu）", example = "zhipu")
    private String embeddingProvider;

    @Schema(description = "Embedding 模型（embedding-3 / mock）", example = "embedding-3")
    private String embeddingModel;

    @Schema(description = "Embedding 维度（384=mock, 1024=zhipu）", example = "1024")
    private Integer embeddingDimensions;

    @Schema(description = "索引版本（与 pointId 关联，用于幂等/重索引识别）", example = "v1")
    private String indexVersion;

    @Schema(description = "状态：INDEXED/FAILED", example = "INDEXED")
    private String status;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}