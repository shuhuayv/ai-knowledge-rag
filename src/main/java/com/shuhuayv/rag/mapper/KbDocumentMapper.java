package com.shuhuayv.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shuhuayv.rag.entity.KbDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface KbDocumentMapper extends BaseMapper<KbDocument> {

    /**
     * 统计 M1 引入的四列在 {@code kb_document} 中实际存在多少列（只读，fail-closed 前置条件①）。
     *
     * @return 存在列数；M1 已执行时应为 4
     */
    @Select("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_document' "
            + "AND COLUMN_NAME IN ('content_sha256','is_deleted','canonical_document_id','dedup_batch')")
    int countM1IdentityColumns();

    /**
     * 统计以 {@code content_sha256} 为首列的唯一索引数量（只读，fail-closed 前置条件②）。
     *
     * <p>M2 的 {@code UNIQUE(content_sha256, is_deleted)} 建成后，此查询应返回 &gt; 0；
     * PR-3 要求 M2 <b>未执行</b>，因此 &gt; 0 即 HARD FAIL。</p>
     *
     * @return 匹配的唯一索引条目数
     */
    @Select("SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS "
            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_document' "
            + "AND NON_UNIQUE = 0 AND COLUMN_NAME = 'content_sha256' AND SEQ_IN_INDEX = 1")
    int countUniqueIndexOnContentIdentity();

    /**
     * 统计 active（is_deleted=0）且 content_sha256 为 NULL 的行数（只读，fail-closed 前置条件③）。
     *
     * @return active NULL hash 行数
     */
    @Select("SELECT COUNT(*) FROM kb_document WHERE is_deleted = 0 AND content_sha256 IS NULL")
    long countActiveNullHash();
}
