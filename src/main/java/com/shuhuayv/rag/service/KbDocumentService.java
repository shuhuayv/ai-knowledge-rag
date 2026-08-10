package com.shuhuayv.rag.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.shuhuayv.rag.entity.KbDocument;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

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

    List<KbDocument> listDocuments();

    IPage<KbDocument> pageDocuments(long pageNum, long pageSize);

    KbDocument getDocumentById(Long id);

    void deleteDocument(Long id);
}
