package com.shuhuayv.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shuhuayv.rag.dto.DocumentIndexResponse;
import com.shuhuayv.rag.embedding.service.EmbeddingService;
import com.shuhuayv.rag.entity.KbChunk;
import com.shuhuayv.rag.entity.KbDocument;
import com.shuhuayv.rag.entity.KbVectorRecord;
import com.shuhuayv.rag.mapper.KbDocumentMapper;
import com.shuhuayv.rag.mapper.KbVectorRecordMapper;
import com.shuhuayv.rag.service.ChunkService;
import com.shuhuayv.rag.service.DocumentIndexService;
import com.shuhuayv.rag.vector.service.QdrantVectorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class DocumentIndexServiceImpl extends ServiceImpl<KbVectorRecordMapper, KbVectorRecord> implements DocumentIndexService {

    private final KbDocumentMapper kbDocumentMapper;
    private final ChunkService chunkService;
    private final EmbeddingService embeddingService;
    private final QdrantVectorService qdrantVectorService;

    @Value("${app.qdrant.collection:kb_chunks}")
    private String collectionName;

    @Value("${app.qdrant.dimension:384}")
    private int vectorDimension;

    public DocumentIndexServiceImpl(KbDocumentMapper kbDocumentMapper,
                                    ChunkService chunkService,
                                    EmbeddingService embeddingService,
                                    QdrantVectorService qdrantVectorService) {
        this.kbDocumentMapper = kbDocumentMapper;
        this.chunkService = chunkService;
        this.embeddingService = embeddingService;
        this.qdrantVectorService = qdrantVectorService;
    }

    @Override
    public DocumentIndexResponse indexDocument(Long documentId) {
        KbDocument document = kbDocumentMapper.selectById(documentId);
        if (document == null) {
            throw new IllegalArgumentException("文档不存在");
        }

        List<KbChunk> chunks = chunkService.getChunksByDocumentId(documentId);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("文档没有 Chunk，请先解析文档（POST /api/documents/" + documentId + "/parse）");
        }

        try {
            qdrantVectorService.ensureCollection(collectionName, vectorDimension);
        } catch (Exception e) {
            document.setStatus("FAILED");
            document.setRemark("向量化失败: Qdrant 不可用");
            kbDocumentMapper.updateById(document);
            throw new RuntimeException("Qdrant 不可用，请确保 Qdrant 已启动: " + e.getMessage(), e);
        }

        deleteVectorRecordsByDocumentId(documentId);

        int vectorCount = 0;
        try {
            for (KbChunk chunk : chunks) {
                List<Float> vector = embeddingService.embed(chunk.getContent());

                String pointId = UUID.randomUUID().toString();

                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("documentId", documentId);
                payload.put("chunkId", chunk.getId());
                payload.put("chunkIndex", chunk.getChunkIndex());
                payload.put("content", chunk.getContent());

                qdrantVectorService.upsertPoint(collectionName, pointId, vector, payload);

                KbVectorRecord record = new KbVectorRecord();
                record.setDocumentId(documentId);
                record.setChunkId(chunk.getId());
                record.setQdrantPointId(pointId);
                record.setCollectionName(collectionName);
                record.setVectorDimension(vectorDimension);
                record.setStatus("INDEXED");
                save(record);

                vectorCount++;
            }
        } catch (Exception e) {
            document.setStatus("FAILED");
            document.setRemark("向量化失败: " + e.getMessage());
            kbDocumentMapper.updateById(document);
            throw new RuntimeException("向量化写入失败: " + e.getMessage(), e);
        }

        document.setStatus("INDEXED");
        document.setRemark(null);
        kbDocumentMapper.updateById(document);

        log.info("Document indexed successfully, documentId={}, vectorCount={}, collection={}", documentId, vectorCount, collectionName);

        return DocumentIndexResponse.builder()
                .documentId(documentId)
                .status("INDEXED")
                .chunkCount(chunks.size())
                .vectorCount(vectorCount)
                .collectionName(collectionName)
                .message("文档向量化完成，共写入 " + vectorCount + " 条向量到 Qdrant Collection: " + collectionName)
                .build();
    }

    private void deleteVectorRecordsByDocumentId(Long documentId) {
        LambdaQueryWrapper<KbVectorRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KbVectorRecord::getDocumentId, documentId);
        long deleted = baseMapper.delete(wrapper);
        if (deleted > 0) {
            log.info("Old vector records deleted, documentId={}, count={}", documentId, deleted);
        }
    }

    @Override
    public List<KbVectorRecord> getVectorRecordsByDocumentId(Long documentId) {
        return lambdaQuery()
                .eq(KbVectorRecord::getDocumentId, documentId)
                .orderByAsc(KbVectorRecord::getId)
                .list();
    }
}