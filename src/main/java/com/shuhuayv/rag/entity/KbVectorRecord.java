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

    @Schema(description = "状态：INDEXED/FAILED", example = "INDEXED")
    private String status;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}