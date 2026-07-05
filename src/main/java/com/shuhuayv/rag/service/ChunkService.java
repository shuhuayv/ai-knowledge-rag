package com.shuhuayv.rag.service;

import com.shuhuayv.rag.entity.KbChunk;

import java.util.List;

public interface ChunkService {

    List<KbChunk> splitAndSave(Long documentId, String content);

    List<KbChunk> getChunksByDocumentId(Long documentId);

    void deleteChunksByDocumentId(Long documentId);
}