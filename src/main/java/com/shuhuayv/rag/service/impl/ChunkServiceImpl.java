package com.shuhuayv.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shuhuayv.rag.entity.KbChunk;
import com.shuhuayv.rag.mapper.KbChunkMapper;
import com.shuhuayv.rag.service.ChunkService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class ChunkServiceImpl extends ServiceImpl<KbChunkMapper, KbChunk> implements ChunkService {

    @Value("${app.chunk.size:500}")
    private int chunkMaxSize;

    @Value("${app.chunk.overlap:80}")
    private int chunkOverlap;

    @Override
    public List<KbChunk> splitAndSave(Long documentId, String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("文档内容为空，无法切分");
        }

        deleteChunksByDocumentId(documentId);

        List<KbChunk> chunks = splitText(documentId, content);
        saveBatch(chunks);

        log.info("Chunk split completed, documentId={}, chunkCount={}", documentId, chunks.size());
        return chunks;
    }

    @Override
    public List<KbChunk> getChunksByDocumentId(Long documentId) {
        return lambdaQuery()
                .eq(KbChunk::getDocumentId, documentId)
                .orderByAsc(KbChunk::getChunkIndex)
                .list();
    }

    @Override
    public void deleteChunksByDocumentId(Long documentId) {
        LambdaQueryWrapper<KbChunk> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KbChunk::getDocumentId, documentId);
        long deleted = baseMapper.delete(wrapper);
        if (deleted > 0) {
            log.info("Old chunks deleted, documentId={}, count={}", documentId, deleted);
        }
    }

    private List<KbChunk> splitText(Long documentId, String content) {
        // 参数校验
        if (chunkMaxSize <= 0) {
            throw new IllegalArgumentException("chunkSize 必须大于 0，当前值: " + chunkMaxSize);
        }

        int overlap = chunkOverlap;
        if (overlap < 0) {
            overlap = 0;
        }
        if (overlap >= chunkMaxSize) {
            overlap = chunkMaxSize / 5;
        }

        List<KbChunk> chunks = new ArrayList<>();
        int length = content.length();
        int start = 0;
        int chunkIndex = 0;
        final int MAX_CHUNK_COUNT = 10000;

        while (start < length) {
            if (chunks.size() >= MAX_CHUNK_COUNT) {
                throw new IllegalStateException(
                        "Chunk 数量超过上限 " + MAX_CHUNK_COUNT + "，当前文档可能过大，文档 ID: " + documentId);
            }

            int end = Math.min(start + chunkMaxSize, length);
            String chunkText = content.substring(start, end).trim();

            if (!chunkText.isEmpty()) {
                KbChunk chunk = new KbChunk();
                chunk.setDocumentId(documentId);
                chunk.setContent(chunkText);
                chunk.setChunkIndex(chunkIndex);
                chunk.setTokenCount(chunkText.length());
                chunks.add(chunk);
                chunkIndex++;
            }

            if (end >= length) {
                break;
            }

            int nextStart = end - overlap;
            if (nextStart <= start) {
                nextStart = end; // 确保 start 每轮向前推进
            }
            start = nextStart;
        }

        return chunks;
    }
}