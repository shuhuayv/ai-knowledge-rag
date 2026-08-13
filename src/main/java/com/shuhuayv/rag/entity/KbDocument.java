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

    // ===== PR-2A 文档内容身份（Document Identity）=====
    // 说明：以下 4 列由 migration M1 引入（sql/migrations/20260810_m1_add_document_identity_columns.sql）。
    // 部署顺序硬约束（MIG-R1）：必须先执行 M1，再部署本版本 jar，否则 kb_document 查询会 Unknown column 失败。
    // 刻意不使用 @TableLogic：未来软删语义为 is_deleted = 该行自身 document id（非 0/1），
    // MyBatis-Plus 的逻辑删除假定常量 0/1，与本语义不符。

    @Schema(description = "文档内容身份：SHA-256(raw file bytes)，lowercase hex 64 字符",
            example = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    private String contentSha256;

    /**
     * 软删标记。0 = active；非 0 = 已软删（未来取值为该行自身 document id）。
     *
     * <p><b>类型红线</b>：必须是 {@code Long}，绝不可声明为 {@code boolean}/{@code Boolean}。
     * 若为 boolean，Lombok 生成的 getter 为 {@code isDeleted()}，MyBatis-Plus 会把属性名推导为
     * {@code deleted} 并映射到不存在的 {@code deleted} 列，导致映射错误。</p>
     */
    @Schema(description = "软删标记：0=active；非 0=已软删（值为该行自身 document id）", example = "0")
    private Long isDeleted;

    @Schema(description = "去重治理：指向保留下来的 canonical document id（PR-3 写入）", example = "2")
    private Long canonicalDocumentId;

    @Schema(description = "去重治理批次号（PR-3 写入），用于审计回溯", example = "dedup-20260810-01")
    private String dedupBatch;
}