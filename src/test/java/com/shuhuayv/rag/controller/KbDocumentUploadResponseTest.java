package com.shuhuayv.rag.controller;

import com.shuhuayv.rag.dto.DocumentUploadResponse;
import com.shuhuayv.rag.entity.KbDocument;
import com.shuhuayv.rag.service.ChunkService;
import com.shuhuayv.rag.service.DocumentIndexService;
import com.shuhuayv.rag.service.DocumentParseService;
import com.shuhuayv.rag.service.DocumentUploadResult;
import com.shuhuayv.rag.service.KbDocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * KbDocumentController.uploadDocument 适配层测试（UPLOAD-14）。
 *
 * <p>直接构造 Controller（mock 掉所有 service 依赖），验证其把 {@link DocumentUploadResult} 正确映射为
 * {@link DocumentUploadResponse}（含新增的 {@code duplicate} 字段），且重复上传仍走 200 成功分支
 * （重复上传不是业务错误）。</p>
 */
class KbDocumentUploadResponseTest {

    private KbDocumentService kbDocumentService;
    private KbDocumentController controller;

    @BeforeEach
    void setUp() {
        kbDocumentService = mock(KbDocumentService.class);
        DocumentParseService parseService = mock(DocumentParseService.class);
        ChunkService chunkService = mock(ChunkService.class);
        DocumentIndexService indexService = mock(DocumentIndexService.class);
        controller = new KbDocumentController(
                kbDocumentService, parseService, chunkService, indexService);
    }

    @Test
    void upload14_firstUploadMapsDuplicateFalse() {
        KbDocument doc = new KbDocument();
        doc.setId(11L);
        doc.setFileName("a.pdf");
        doc.setFileType("PDF");
        doc.setFileSize(123L);
        doc.setStatus("UPLOADED");
        doc.setCreatedAt(LocalDateTime.of(2026, 8, 10, 12, 0));

        when(kbDocumentService.uploadDocument(any()))
                .thenReturn(new DocumentUploadResult(doc, false));

        DocumentUploadResponse resp = controller.uploadDocument(sampleFile()).getData();
        assertThat(resp.getId()).isEqualTo(11L);
        assertThat(resp.getFileName()).isEqualTo("a.pdf");
        assertThat(resp.getStatus()).isEqualTo("UPLOADED");
        assertThat(resp.isDuplicate()).isFalse();
    }

    @Test
    void upload14_duplicateUploadMapsDuplicateTrueAndKeepsExistingId() {
        KbDocument existing = new KbDocument();
        existing.setId(2L);
        existing.setFileName("a.pdf");
        existing.setFileType("PDF");
        existing.setFileSize(123L);
        existing.setStatus("INDEXED");

        when(kbDocumentService.uploadDocument(any()))
                .thenReturn(new DocumentUploadResult(existing, true));

        DocumentUploadResponse resp = controller.uploadDocument(sampleFile()).getData();
        assertThat(resp.getId()).isEqualTo(2L);
        assertThat(resp.isDuplicate()).isTrue();
    }

    private static MockMultipartFile sampleFile() {
        return new MockMultipartFile("file", "a.pdf", "application/pdf", "data".getBytes());
    }
}
