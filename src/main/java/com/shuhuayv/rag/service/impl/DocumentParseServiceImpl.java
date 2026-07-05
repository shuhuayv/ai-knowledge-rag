package com.shuhuayv.rag.service.impl;

import com.shuhuayv.rag.entity.KbDocument;
import com.shuhuayv.rag.mapper.KbDocumentMapper;
import com.shuhuayv.rag.service.DocumentParseService;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Service
public class DocumentParseServiceImpl implements DocumentParseService {

    private final KbDocumentMapper kbDocumentMapper;

    public DocumentParseServiceImpl(KbDocumentMapper kbDocumentMapper) {
        this.kbDocumentMapper = kbDocumentMapper;
    }

    @Override
    public String parseDocument(Long documentId) {
        KbDocument document = kbDocumentMapper.selectById(documentId);
        if (document == null) {
            throw new IllegalArgumentException("文档不存在");
        }

        String filePath = document.getFilePath();
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("文档文件路径为空");
        }

        File file = new File(filePath);
        if (!file.exists()) {
            throw new IllegalArgumentException("文档文件不存在: " + filePath);
        }

        String fileType = document.getFileType();
        String content;

        try {
            if ("TXT".equalsIgnoreCase(fileType)) {
                content = parseTxt(file.toPath());
            } else if ("PDF".equalsIgnoreCase(fileType)) {
                content = parsePdf(file);
            } else {
                throw new IllegalArgumentException("不支持的文件类型: " + fileType);
            }
        } catch (IOException e) {
            log.error("Failed to parse document, id={}, filePath={}", documentId, filePath, e);
            document.setStatus("FAILED");
            document.setRemark("解析失败: " + e.getMessage());
            kbDocumentMapper.updateById(document);
            throw new RuntimeException("文档解析失败: " + e.getMessage(), e);
        }

        if (content == null || content.isBlank()) {
            document.setStatus("FAILED");
            document.setRemark("解析结果为空");
            kbDocumentMapper.updateById(document);
            throw new IllegalArgumentException("文档解析结果为空");
        }

        document.setStatus("PARSED");
        document.setRemark(null);
        kbDocumentMapper.updateById(document);

        log.info("Document parsed successfully, id={}, fileName={}", documentId, document.getFileName());
        return content;
    }

    private String parseTxt(Path filePath) throws IOException {
        return Files.readString(filePath, StandardCharsets.UTF_8);
    }

    private String parsePdf(File file) throws IOException {
        try (PDDocument pdDocument = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(pdDocument);
        }
    }
}