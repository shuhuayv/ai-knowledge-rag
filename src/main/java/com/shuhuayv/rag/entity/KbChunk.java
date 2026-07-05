package com.shuhuayv.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("kb_chunk")
@Schema(description = "文档分块")
public class KbChunk {

    @TableId(type = IdType.AUTO)
    @Schema(description = "Chunk ID", example = "1")
    private Long id;

    @Schema(description = "关联文档 ID", example = "1")
    private Long documentId;

    @Schema(description = "分块内容", example = "这是一段文档内容...")
    private String content;

    @Schema(description = "分块序号", example = "0")
    private Integer chunkIndex;

    @Schema(description = "页码（PDF）", example = "1")
    private Integer pageNo;

    @Schema(description = "Token 数量", example = "500")
    private Integer tokenCount;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}