package com.shuhuayv.rag.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.shuhuayv.rag.entity.KbDocument;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface KbDocumentService extends IService<KbDocument> {

    /**
     * 上传文档，并在内容级别保持幂等。
     *
     * <p>流程：校验 → 生成随机存储文件名 → 落盘 → 对<b>落盘文件的 raw bytes</b> 计算 SHA-256
     * → 用该 hash 查 MySQL {@code kb_document} 中的 active 重复文档：</p>
     * <ul>
     *   <li>命中：删除本次新落盘的文件，返回<b>已有</b>文档，{@code duplicate = true}；</li>
     *   <li>未命中：新建 {@code KbDocument}（{@code contentSha256} 非空、{@code isDeleted = 0}）并入库，
     *       返回新文档，{@code duplicate = false}。</li>
     * </ul>
     *
     * <p>去重<b>只</b>以 MySQL 为事实源，上传路径不会访问 Qdrant。</p>
     *
     * @param file 上传的文件，仅支持 TXT / PDF，且不超过 50MB
     * @return 上传结果，包含最终文档与是否重复标记
     * @throws IllegalArgumentException 文件为空、超过 50MB、或类型不是 TXT/PDF
     */
    DocumentUploadResult uploadDocument(MultipartFile file);

    /**
     * 按内容哈希查找「首选的 active 重复文档」。
     *
     * <p>查询条件固定为 {@code content_sha256 = ? AND is_deleted = 0}，并在 Java 侧做确定性优选。
     * 允许结果集出现多行（历史过渡期的既有重复数据），<b>不会</b>抛 TooManyResults。</p>
     *
     * @param contentSha256 raw-byte SHA-256（lowercase hex）；为 null 或空白时直接返回 {@code null}
     * @return 首选的 active 重复文档；无任何命中时返回 {@code null}
     */
    KbDocument findPreferredActiveDuplicate(String contentSha256);

    /**
     * 批量查询 active 文档 id（D10，Search/Ask 防御层）。
     *
     * <p><b>禁止 N+1</b>：一次 SQL {@code WHERE id IN (...) AND is_deleted = 0} 返回所有匹配 id；
     * Search 层只对 unique document IDs 做一次批量校验。空集合直接返回空 Set，不发起查询。</p>
     *
     * @param ids 待校验的 document id 集合（可为空）
     * @return active 文档 id 集合（仅 is_deleted = 0 的 id）
     */
    Set<Long> findActiveDocumentIds(Collection<Long> ids);

    /**
     * 列出全部 active 文档（D5：active-only，soft-deleted 行不返回）。
     */
    List<KbDocument> listDocuments();

    /**
     * 分页查询 active 文档（D5：active-only，soft-deleted 行不返回）。
     */
    IPage<KbDocument> pageDocuments(long pageNum, long pageSize);

    /**
     * 查询单个 active 文档（D5：soft-deleted 行视为 not found）。
     *
     * @param id 文档 id
     * @return active 文档
     * @throws IllegalArgumentException 文档不存在或已软删
     */
    KbDocument getDocumentById(Long id);

    /**
     * 删除文档：<b>物理删除 → soft delete</b>（D7）。
     *
     * <p>语义：仅对 {@code is_deleted = 0} 的行生效 → 设 {@code is_deleted = 自身 document id}
     * → 不改 {@code content_sha256} → canonical_document_id 普通删除不强制写（保持 NULL，
     * 除非已有 lineage）→ 保留 row 作 tombstone → Qdrant points 安全补偿式 cleanup
     * → <b>不得物理删除 DB row</b> → 默认 PHYSICAL_FILE_DELETE_ON_SOFT_DELETE=NO
     * （保留原始文件保 rollback）。</p>
     *
     * @param id 文档 id
     * @throws IllegalArgumentException 文档不存在或已软删（重复删除按 not found 契约）
     */
    void deleteDocument(Long id);
}
