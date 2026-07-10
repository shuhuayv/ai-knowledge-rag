package com.shuhuayv.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shuhuayv.rag.dto.DocumentIndexResponse;
import com.shuhuayv.rag.embedding.service.EmbeddingService;
import com.shuhuayv.rag.entity.KbChunk;
import com.shuhuayv.rag.entity.KbDocument;
import com.shuhuayv.rag.entity.KbVectorRecord;
import com.shuhuayv.rag.mapper.KbDocumentMapper;
import com.shuhuayv.rag.mapper.KbVectorRecordMapper;
import com.shuhuayv.rag.service.ChunkService;
import com.shuhuayv.rag.service.DocumentIndexService;
import com.shuhuayv.rag.vector.service.CollectionNameResolver;
import com.shuhuayv.rag.vector.service.QdrantVectorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文档向量化索引服务。
 *
 * <p>关键增强（真实 Embedding 接入）：
 * <ul>
 *   <li>Collection 来源统一走 {@link CollectionNameResolver}，Mock/Real 物理隔离。</li>
 *   <li>向量维度来自 {@link EmbeddingService#dimensions()}（Mock=384，zhipu=1024），不再依赖 app.qdrant.dimension。</li>
 *   <li>pointId 确定性：基于名称的确定性 UUID（{@link java.util.UUID#nameUUIDFromBytes(byte[])}，UUID v3），
 *       保证幂等（重索引覆盖同一 Point），且符合 Qdrant Point ID 规范。
 *       Qdrant 仅接受 unsigned integer 或 UUID；旧实现 {@code documentId + "_" + chunkId + "_" + indexVersion}
 *       （如 {@code 2_3_v1}）非法，会触发 Qdrant 400 并使 index 接口返回 500，现不再使用。</li>
 *   <li>kb_vector_record / kb_document 写入 Embedding 元数据（provider/model/dimensions/indexVersion 等）。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
public class DocumentIndexServiceImpl implements DocumentIndexService {

    /** 索引版本常量（与 Collection 命名、pointId 关联）。 */
    public static final String INDEX_VERSION = "v1";

    private final KbDocumentMapper kbDocumentMapper;
    private final KbVectorRecordMapper kbVectorRecordMapper;
    private final ChunkService chunkService;
    private final EmbeddingService embeddingService;
    private final QdrantVectorService qdrantVectorService;
    private final CollectionNameResolver collectionNameResolver;

    public DocumentIndexServiceImpl(KbDocumentMapper kbDocumentMapper,
                                    KbVectorRecordMapper kbVectorRecordMapper,
                                    ChunkService chunkService,
                                    EmbeddingService embeddingService,
                                    QdrantVectorService qdrantVectorService,
                                    CollectionNameResolver collectionNameResolver) {
        this.kbDocumentMapper = kbDocumentMapper;
        this.kbVectorRecordMapper = kbVectorRecordMapper;
        this.chunkService = chunkService;
        this.embeddingService = embeddingService;
        this.qdrantVectorService = qdrantVectorService;
        this.collectionNameResolver = collectionNameResolver;
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

        String collectionName = collectionNameResolver.resolveForCurrentMode(embeddingService, INDEX_VERSION);
        int vectorDimension = embeddingService.dimensions();

        try {
            qdrantVectorService.ensureCollection(collectionName, vectorDimension);
        } catch (Exception e) {
            log.error("Document index failed, documentId={}, provider={}, model={}, dimensions={}, collection={}, error={}",
                    documentId, embeddingService.provider(), embeddingService.model(), vectorDimension, collectionName, e.getMessage());
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

                String pointId = buildPointId(documentId, chunk.getId(), INDEX_VERSION);

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
                record.setEmbeddingProvider(embeddingService.provider());
                record.setEmbeddingModel(embeddingService.model());
                record.setEmbeddingDimensions(vectorDimension);
                record.setIndexVersion(INDEX_VERSION);
                record.setStatus("INDEXED");
                kbVectorRecordMapper.insert(record);

                vectorCount++;
            }
        } catch (Exception e) {
            log.error("Document index failed, documentId={}, provider={}, model={}, dimensions={}, collection={}, error={}",
                    documentId, embeddingService.provider(), embeddingService.model(), vectorDimension, collectionName, e.getMessage());
            document.setStatus("FAILED");
            document.setRemark("向量化失败: " + e.getMessage());
            kbDocumentMapper.updateById(document);
            throw new RuntimeException("向量化写入失败: " + e.getMessage(), e);
        }

        document.setStatus("INDEXED");
        document.setRemark(null);
        document.setEmbeddingProvider(embeddingService.provider());
        document.setEmbeddingModel(embeddingService.model());
        document.setEmbeddingDimensions(vectorDimension);
        document.setVectorCollection(collectionName);
        document.setIndexVersion(INDEX_VERSION);
        document.setIndexedAt(LocalDateTime.now());
        kbDocumentMapper.updateById(document);

        log.info("Document indexed successfully, documentId={}, vectorCount={}, collection={}, provider={}, model={}, dimensions={}",
                documentId, vectorCount, collectionName, embeddingService.provider(), embeddingService.model(), vectorDimension);

        return DocumentIndexResponse.builder()
                .documentId(documentId)
                .status("INDEXED")
                .chunkCount(chunks.size())
                .vectorCount(vectorCount)
                .collectionName(collectionName)
                .embeddingProvider(embeddingService.provider())
                .embeddingModel(embeddingService.model())
                .embeddingDimensions(vectorDimension)
                .indexVersion(INDEX_VERSION)
                .message("文档向量化完成，共写入 " + vectorCount + " 条向量到 Qdrant Collection: " + collectionName)
                .build();
    }

    /**
     * 构造确定性 pointId（UUID v3，基于名称），保证幂等且符合 Qdrant Point ID 规范。
     *
     * <p>Qdrant 仅接受 unsigned integer 或 UUID 作为 Point ID。旧实现返回
     * {@code documentId + "_" + chunkId + "_" + indexVersion}（如 {@code 2_3_v1}）非法，
     * 会导致 upsert 触发 Qdrant 400 并使 index 接口返回 500。现改用
     * {@link java.util.UUID#nameUUIDFromBytes(byte[])} 生成确定性 UUID：相同
     * (documentId, chunkId, indexVersion) 始终得到同一 UUID（幂等、重索引覆盖同一 Point），
     * 且输出为合法 UUID 字符串（36 字符），完全符合 Qdrant Point ID 规范。</p>
     */
    String buildPointId(Long documentId, Long chunkId, String indexVersion) {
        String source = documentId + ":" + chunkId + ":" + indexVersion;
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private void deleteVectorRecordsByDocumentId(Long documentId) {
        LambdaQueryWrapper<KbVectorRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KbVectorRecord::getDocumentId, documentId);
        long deleted = kbVectorRecordMapper.delete(wrapper);
        if (deleted > 0) {
            log.info("Old vector records deleted, documentId={}, count={}", documentId, deleted);
        }
    }

    @Override
    public List<KbVectorRecord> getVectorRecordsByDocumentId(Long documentId) {
        LambdaQueryWrapper<KbVectorRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KbVectorRecord::getDocumentId, documentId).orderByAsc(KbVectorRecord::getId);
        return kbVectorRecordMapper.selectList(wrapper);
    }
}
