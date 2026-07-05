package com.shuhuayv.rag.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.shuhuayv.rag.common.ApiResponse;
import com.shuhuayv.rag.common.PageResult;
import com.shuhuayv.rag.dto.DocumentIndexResponse;
import com.shuhuayv.rag.dto.DocumentParseResponse;
import com.shuhuayv.rag.dto.DocumentUploadResponse;
import com.shuhuayv.rag.entity.KbChunk;
import com.shuhuayv.rag.entity.KbDocument;
import com.shuhuayv.rag.entity.KbVectorRecord;
import com.shuhuayv.rag.service.ChunkService;
import com.shuhuayv.rag.service.DocumentIndexService;
import com.shuhuayv.rag.service.DocumentParseService;
import com.shuhuayv.rag.service.KbDocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Tag(name = "文档管理接口", description = "知识库文档上传、查询、删除、解析接口")
@RestController
@RequestMapping("/api/documents")
public class KbDocumentController {

    private final KbDocumentService kbDocumentService;
    private final DocumentParseService documentParseService;
    private final ChunkService chunkService;
    private final DocumentIndexService documentIndexService;

    public KbDocumentController(KbDocumentService kbDocumentService,
                                DocumentParseService documentParseService,
                                ChunkService chunkService,
                                DocumentIndexService documentIndexService) {
        this.kbDocumentService = kbDocumentService;
        this.documentParseService = documentParseService;
        this.chunkService = chunkService;
        this.documentIndexService = documentIndexService;
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

    @Operation(summary = "解析文档", description = "解析指定文档内容并切分为 Chunk 入库")
    @PostMapping("/{id}/parse")
    public ApiResponse<DocumentParseResponse> parseDocument(
            @Parameter(description = "文档 ID", example = "1") @PathVariable Long id) {
        KbDocument document = kbDocumentService.getDocumentById(id);
        String content = documentParseService.parseDocument(id);
        List<KbChunk> chunks = chunkService.splitAndSave(id, content);

        document = kbDocumentService.getDocumentById(id);

        DocumentParseResponse response = DocumentParseResponse.builder()
                .documentId(document.getId())
                .fileName(document.getFileName())
                .status(document.getStatus())
                .chunkCount(chunks.size())
                .message("文档解析并切分完成，共生成 " + chunks.size() + " 个 Chunk")
                .build();
        return ApiResponse.success(response);
    }

    @Operation(summary = "查询文档 Chunk 列表", description = "获取指定文档的所有 Chunk")
    @GetMapping("/{id}/chunks")
    public ApiResponse<List<KbChunk>> getDocumentChunks(
            @Parameter(description = "文档 ID", example = "1") @PathVariable Long id) {
        return ApiResponse.success(chunkService.getChunksByDocumentId(id));
    }

    @Operation(summary = "分页查询文档 Chunk", description = "分页获取指定文档的 Chunk 列表")
    @GetMapping("/{id}/chunks/page")
    public ApiResponse<PageResult<KbChunk>> pageDocumentChunks(
            @Parameter(description = "文档 ID", example = "1") @PathVariable Long id,
            @Parameter(description = "页码", example = "1") @RequestParam(defaultValue = "1") long pageNum,
            @Parameter(description = "每页条数", example = "10") @RequestParam(defaultValue = "10") long pageSize) {
        List<KbChunk> allChunks = chunkService.getChunksByDocumentId(id);
        int total = allChunks.size();
        int fromIndex = (int) ((pageNum - 1) * pageSize);
        int toIndex = Math.min(fromIndex + (int) pageSize, total);
        List<KbChunk> pageRecords = fromIndex < total ? allChunks.subList(fromIndex, toIndex) : List.of();
        PageResult<KbChunk> result = PageResult.of(pageNum, pageSize, total, pageRecords);
        return ApiResponse.success(result);
    }

    @Operation(summary = "向量化文档", description = "将文档的 Chunk 生成 Mock Embedding 并写入 Qdrant 向量数据库")
    @PostMapping("/{id}/index")
    public ApiResponse<DocumentIndexResponse> indexDocument(
            @Parameter(description = "文档 ID", example = "1") @PathVariable Long id) {
        return ApiResponse.success(documentIndexService.indexDocument(id));
    }

    @Operation(summary = "查询文档向量记录", description = "获取指定文档的向量记录列表")
    @GetMapping("/{id}/vectors")
    public ApiResponse<List<KbVectorRecord>> getDocumentVectors(
            @Parameter(description = "文档 ID", example = "1") @PathVariable Long id) {
        return ApiResponse.success(documentIndexService.getVectorRecordsByDocumentId(id));
    }

    @Operation(summary = "分页查询文档向量记录", description = "分页获取指定文档的向量记录列表")
    @GetMapping("/{id}/vectors/page")
    public ApiResponse<PageResult<KbVectorRecord>> pageDocumentVectors(
            @Parameter(description = "文档 ID", example = "1") @PathVariable Long id,
            @Parameter(description = "页码", example = "1") @RequestParam(defaultValue = "1") long pageNum,
            @Parameter(description = "每页条数", example = "10") @RequestParam(defaultValue = "10") long pageSize) {
        List<KbVectorRecord> allRecords = documentIndexService.getVectorRecordsByDocumentId(id);
        int total = allRecords.size();
        int fromIndex = (int) ((pageNum - 1) * pageSize);
        int toIndex = Math.min(fromIndex + (int) pageSize, total);
        List<KbVectorRecord> pageRecords = fromIndex < total ? allRecords.subList(fromIndex, toIndex) : List.of();
        PageResult<KbVectorRecord> result = PageResult.of(pageNum, pageSize, total, pageRecords);
        return ApiResponse.success(result);
    }
}