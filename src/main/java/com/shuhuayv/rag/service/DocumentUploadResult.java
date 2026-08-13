package com.shuhuayv.rag.service;

import com.shuhuayv.rag.entity.KbDocument;

/**
 * 文档上传的<b>内部 service 返回值</b>（PR-2A 幂等上传）。
 *
 * <p>用于把「最终对外呈现的文档」与「本次上传是否命中内容重复」两件事一次性交给调用方，
 * 避免 Controller 再去二次查询判断。</p>
 *
 * <p><b>定位约束</b>：这是 service 层内部契约，<b>不是</b> HTTP DTO。
 * HTTP 层继续使用既有的 {@code DocumentUploadResponse}（仅追加 {@code duplicate} 字段）。
 * 禁止把本 record 演化成第二个 HTTP 响应体，也禁止新建
 * {@code DocumentUploadResponseV2} / {@code DuplicateDocumentResponse} 之类的并行 DTO。</p>
 *
 * @param document  本次上传最终对应的文档。
 *                  首次上传时为新建文档；命中内容重复时为<b>已存在</b>的首选文档
 *                  （由 {@code KbDocumentService#findPreferredActiveDuplicate} 确定性选出）
 * @param duplicate 是否为内容级重复上传。首次上传 {@code false}；命中已有相同 content_sha256 的
 *                  active 文档时 {@code true}。重复上传<b>不是业务错误</b>，HTTP 仍返回 200
 */
public record DocumentUploadResult(KbDocument document, boolean duplicate) {
}
