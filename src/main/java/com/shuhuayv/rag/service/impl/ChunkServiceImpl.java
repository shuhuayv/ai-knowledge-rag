package com.shuhuayv.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shuhuayv.rag.entity.KbChunk;
import com.shuhuayv.rag.mapper.KbChunkMapper;
import com.shuhuayv.rag.service.ChunkService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class ChunkServiceImpl extends ServiceImpl<KbChunkMapper, KbChunk> implements ChunkService {

    private static final int CHUNK_MAX_SIZE = 500;
    private static final int CHUNK_OVERLAP = 80;

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
        List<KbChunk> chunks = new ArrayList<>();
        int totalLength = content.length();
        int start = 0;
        int chunkIndex = 0;

        while (start < totalLength) {
            int end = Math.min(start + CHUNK_MAX_SIZE, totalLength);
            String chunkText = content.substring(start, end);

            KbChunk chunk = new KbChunk();
            chunk.setDocumentId(documentId);
            chunk.setContent(chunkText);
            chunk.setChunkIndex(chunkIndex);
            chunk.setTokenCount(chunkText.length());

            chunks.add(chunk);

            chunkIndex++;
            start = end - CHUNK_OVERLAP;

            if (start >= totalLength) {
                break;
            }
            if (start < 0) {
                start = 0;
            }
        }

        return chunks;
    }
}