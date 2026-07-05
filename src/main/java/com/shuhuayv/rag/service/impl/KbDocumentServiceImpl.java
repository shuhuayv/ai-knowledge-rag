package com.shuhuayv.rag.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shuhuayv.rag.entity.KbDocument;
import com.shuhuayv.rag.mapper.KbDocumentMapper;
import com.shuhuayv.rag.service.KbDocumentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class KbDocumentServiceImpl extends ServiceImpl<KbDocumentMapper, KbDocument> implements KbDocumentService {

    @Value("${app.upload.dir:uploads}")
    private String uploadDir;

    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024;

    @Override
    public KbDocument uploadDocument(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("文件大小不能超过 50MB");
        }

        String originalFilename = file.getOriginalFilename();
        String fileType = getFileType(originalFilename);

        if (!"TXT".equals(fileType) && !"PDF".equals(fileType)) {
            throw new IllegalArgumentException("仅支持 TXT 和 PDF 文件格式");
        }

        String storedFilename = UUID.randomUUID().toString() + "_" + originalFilename;
        Path uploadPath = Paths.get(uploadDir);
        try {
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }
            Path filePath = uploadPath.resolve(storedFilename);
            file.transferTo(filePath.toFile());

            KbDocument document = new KbDocument();
            document.setFileName(originalFilename);
            document.setFileType(fileType);
            document.setFilePath(filePath.toString());
            document.setFileSize(file.getSize());
            document.setStatus("UPLOADED");

            save(document);

            log.info("Document uploaded, id={}, fileName={}", document.getId(), originalFilename);
            return document;
        } catch (IOException e) {
            log.error("Failed to upload file", e);
            throw new RuntimeException("文件上传失败", e);
        }
    }

    @Override
    public List<KbDocument> listDocuments() {
        return lambdaQuery()
                .orderByDesc(KbDocument::getId)
                .list();
    }

    @Override
    public IPage<KbDocument> pageDocuments(long pageNum, long pageSize) {
        return lambdaQuery()
                .orderByDesc(KbDocument::getId)
                .page(new Page<>(pageNum, pageSize));
    }

    @Override
    public KbDocument getDocumentById(Long id) {
        KbDocument document = getById(id);
        if (document == null) {
            throw new IllegalArgumentException("文档不存在");
        }
        return document;
    }

    @Override
    public void deleteDocument(Long id) {
        KbDocument document = getById(id);
        if (document == null) {
            throw new IllegalArgumentException("文档不存在");
        }

        removeById(id);

        if (document.getFilePath() != null) {
            try {
                File file = new File(document.getFilePath());
                if (file.exists()) {
                    file.delete();
                    log.info("Document file deleted, path={}", document.getFilePath());
                }
            } catch (Exception e) {
                log.warn("Failed to delete document file, path={}", document.getFilePath(), e);
            }
        }

        log.info("Document deleted, id={}", id);
    }

    private String getFileType(String filename) {
        if (filename == null) {
            return "UNKNOWN";
        }
        String upper = filename.toUpperCase();
        if (upper.endsWith(".TXT")) {
            return "TXT";
        }
        if (upper.endsWith(".PDF")) {
            return "PDF";
        }
        return "UNKNOWN";
    }
}