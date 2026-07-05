package com.shuhuayv.rag.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.shuhuayv.rag.common.ApiResponse;
import com.shuhuayv.rag.common.PageResult;
import com.shuhuayv.rag.dto.DocumentUploadResponse;
import com.shuhuayv.rag.entity.KbDocument;
import com.shuhuayv.rag.service.KbDocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Tag(name = "文档管理接口", description = "知识库文档上传、查询、删除接口")
@RestController
@RequestMapping("/api/documents")
public class KbDocumentController {

    private final KbDocumentService kbDocumentService;

    public KbDocumentController(KbDocumentService kbDocumentService) {
        this.kbDocumentService = kbDocumentService;
    }

    @Operation(summary = "上传文档", description = "上传 TXT 或 PDF 文件到知识库")
    @PostMapping("/upload")
    public ApiResponse<DocumentUploadResponse> uploadDocument(
            @Parameter(description = "文件") @RequestParam("file") MultipartFile file) {
        KbDocument document = kbDocumentService.uploadDocument(file);
        DocumentUploadResponse response = DocumentUploadResponse.builder()
                .id(document.getId())
                .fileName(document.getFileName())
                .fileType(document.getFileType())
                .fileSize(document.getFileSize())
                .status(document.getStatus())
                .createdAt(document.getCreatedAt())
                .build();
        return ApiResponse.success(response);
    }

    @Operation(summary = "查询文档列表", description = "获取所有文档列表")
    @GetMapping
    public ApiResponse<List<KbDocument>> listDocuments() {
        return ApiResponse.success(kbDocumentService.listDocuments());
    }

    @Operation(summary = "分页查询文档", description = "分页获取文档列表")
    @GetMapping("/page")
    public ApiResponse<PageResult<KbDocument>> pageDocuments(
            @Parameter(description = "页码", example = "1") @RequestParam(defaultValue = "1") long pageNum,
            @Parameter(description = "每页条数", example = "10") @RequestParam(defaultValue = "10") long pageSize) {
        IPage<KbDocument> page = kbDocumentService.pageDocuments(pageNum, pageSize);
        PageResult<KbDocument> result = PageResult.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
        return ApiResponse.success(result);
    }

    @Operation(summary = "查询文档详情", description = "根据文档 ID 获取文档详细信息")
    @GetMapping("/{id}")
    public ApiResponse<KbDocument> getDocumentById(
            @Parameter(description = "文档 ID", example = "1") @PathVariable Long id) {
        return ApiResponse.success(kbDocumentService.getDocumentById(id));
    }

    @Operation(summary = "删除文档", description = "根据文档 ID 删除文档记录及文件")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteDocument(
            @Parameter(description = "文档 ID", example = "1") @PathVariable Long id) {
        kbDocumentService.deleteDocument(id);
        return ApiResponse.success();
    }
}