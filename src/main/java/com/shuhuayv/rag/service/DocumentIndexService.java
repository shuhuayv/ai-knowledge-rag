package com.shuhuayv.rag.service;

import com.shuhuayv.rag.dto.DocumentIndexResponse;
import com.shuhuayv.rag.entity.KbVectorRecord;

import java.util.List;

public interface DocumentIndexService {

    DocumentIndexResponse indexDocument(Long documentId);

    List<KbVectorRecord> getVectorRecordsByDocumentId(Long documentId);
}