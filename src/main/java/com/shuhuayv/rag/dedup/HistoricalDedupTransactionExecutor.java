package com.shuhuayv.rag.dedup;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.shuhuayv.rag.entity.KbDocument;
import com.shuhuayv.rag.mapper.KbDocumentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * PR-3 Phase A：MySQL 单事务 canonicalization（K 组测试契约）。
 *
 * <p><b>事务边界</b>：三组重复数据在 <b>同一个 MySQL 事务</b>内完成，
 * 任一行更新 affected != 1 即抛异常并<b>整体回滚</b>，避免 partial state。</p>
 *
 * <p><b>写入语义（D2 / D3 / D4 / K）</b>：</p>
 * <ul>
 *   <li>duplicate：{@code is_deleted = 自身 id}、{@code canonical_document_id = winner.id}、
 *       {@code dedup_batch = batch}；</li>
 *   <li>canonical：<b>不更新</b>（保持 active、canonical_document_id NULL、dedup_batch NULL）；</li>
 *   <li>UPDATE 必须带乐观守卫：{@code WHERE id=? AND is_deleted=0 AND canonical_document_id IS NULL
 *       AND dedup_batch IS NULL}，affected rows 必须 exactly 1，否则 throw + rollback。</li>
 * </ul>
 */
@Slf4j
@Service
public class HistoricalDedupTransactionExecutor {

    private final KbDocumentMapper kbDocumentMapper;

    public HistoricalDedupTransactionExecutor(KbDocumentMapper kbDocumentMapper) {
        this.kbDocumentMapper = kbDocumentMapper;
    }

    /**
     * 在单个 MySQL 事务内完成全部组的 canonicalization。
     *
     * @param groups  治理计划（每组含 winner 与 duplicates）
     * @param batchId 本次批次号（D3，外部固定或运行时生成，长度 &lt;= 32）
     * @return 写入结果
     * @throws IllegalStateException 乐观守卫未命中（affected != 1），触发整体回滚
     */
    @Transactional(rollbackFor = Exception.class)
    public DedupWriteResult canonicalize(List<DedupGroup> groups, String batchId) {
        int duplicateCount = 0;
        List<Long> canonicalIds = new ArrayList<>();
        List<Long> duplicateIds = new ArrayList<>();

        for (DedupGroup group : groups) {
            KbDocument winner = group.winner();
            canonicalIds.add(winner.getId());
            for (KbDocument duplicate : group.duplicates()) {
                LambdaUpdateWrapper<KbDocument> update = buildDuplicateUpdate(duplicate, winner, batchId);
                int affected = kbDocumentMapper.update(null, update);
                if (affected != 1) {
                    // 乐观守卫未命中：该行已被他人治理 / 已软删 / 已带 lineage。
                    // 立即抛异常回滚当前事务，绝不覆盖未知状态。
                    throw new IllegalStateException(
                            "PR-3 乐观守卫失败: 期望 affected=1，实际 affected=" + affected
                                    + ", documentId=" + duplicate.getId());
                }
                duplicateIds.add(duplicate.getId());
                duplicateCount++;
            }
        }

        log.info("PR3 canonicalize committed: groups={}, winners={}, duplicates={}, batchId={}",
                groups.size(), canonicalIds.size(), duplicateCount, batchId);
        return new DedupWriteResult(groups.size(), canonicalIds.size(), duplicateCount,
                canonicalIds, duplicateIds);
    }

    /**
     * 构造 duplicate 行的乐观守卫 UPDATE（D2/D3/D4 语义）。
     *
     * @param duplicate 待软删的 duplicate 行
     * @param winner    canonical 行
     * @param batchId   批次号
     * @return LambdaUpdateWrapper（SET + WHERE 守卫）
     */
    static LambdaUpdateWrapper<KbDocument> buildDuplicateUpdate(KbDocument duplicate, KbDocument winner, String batchId) {
        return new LambdaUpdateWrapper<KbDocument>()
                .set(KbDocument::getIsDeleted, SoftDeleteSemantics.deletedMarker(duplicate.getId()))
                .set(KbDocument::getCanonicalDocumentId, winner.getId())
                .set(KbDocument::getDedupBatch, batchId)
                .eq(KbDocument::getId, duplicate.getId())
                .eq(KbDocument::getIsDeleted, SoftDeleteSemantics.ACTIVE_FLAG)
                .isNull(KbDocument::getCanonicalDocumentId)
                .isNull(KbDocument::getDedupBatch);
    }
}
