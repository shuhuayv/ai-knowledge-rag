package com.shuhuayv.rag.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.shuhuayv.rag.entity.KbDocument;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface KbDocumentService extends IService<KbDocument> {

    KbDocument uploadDocument(MultipartFile file);

    List<KbDocument> listDocuments();

    IPage<KbDocument> pageDocuments(long pageNum, long pageSize);

    KbDocument getDocumentById(Long id);

    void deleteDocument(Long id);
}