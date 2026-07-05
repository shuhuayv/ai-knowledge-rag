package com.shuhuayv.rag.service.impl;

import com.shuhuayv.rag.dto.RagAskResponse;
import com.shuhuayv.rag.dto.RagReferenceItem;
import com.shuhuayv.rag.dto.SearchResponse;
import com.shuhuayv.rag.dto.SearchResultItem;
import com.shuhuayv.rag.entity.AiCallLog;
import com.shuhuayv.rag.mapper.AiCallLogMapper;
import com.shuhuayv.rag.service.AiAnswerService;
import com.shuhuayv.rag.service.PromptBuildService;
import com.shuhuayv.rag.service.RagService;
import com.shuhuayv.rag.service.SearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RagServiceImpl implements RagService {

    private final SearchService searchService;
    private final PromptBuildService promptBuildService;
    private final AiAnswerService aiAnswerService;
    private final AiCallLogMapper aiCallLogMapper;

    public RagServiceImpl(SearchService searchService,
                          PromptBuildService promptBuildService,
                          AiAnswerService aiAnswerService,
                          AiCallLogMapper aiCallLogMapper) {
        this.searchService = searchService;
        this.promptBuildService = promptBuildService;
        this.aiAnswerService = aiAnswerService;
        this.aiCallLogMapper = aiCallLogMapper;
    }

    @Override
    public RagAskResponse ask(String question, int topK) {
        long startTime = System.currentTimeMillis();
        String status = "SUCCESS";
        String answer = null;
        String prompt = null;

        try {
            SearchResponse searchResponse = searchService.search(question, topK);
            List<SearchResultItem> searchResults = searchResponse.getResults();
            if (searchResults == null) {
                searchResults = List.of();
            }

            prompt = promptBuildService.buildPrompt(question, searchResults);
            answer = aiAnswerService.generateAnswer(question, searchResults);

            List<RagReferenceItem> references = searchResults.stream()
                    .map(r -> RagReferenceItem.builder()
                            .documentId(r.getDocumentId())
                            .chunkId(r.getChunkId())
                            .chunkIndex(r.getChunkIndex())
                            .content(r.getContent())
                            .score(r.getScore())
                            .build())
                    .collect(Collectors.toList());

            String promptPreview = prompt.length() > 500 ? prompt.substring(0, 500) : prompt;
            long costMs = System.currentTimeMillis() - startTime;

            saveAiCallLog(question, topK, answer, costMs, status);

            log.info("RAG ask completed, question={}, topK={}, referenceCount={}, costMs={}",
                    question, topK, references.size(), costMs);

            return RagAskResponse.builder()
                    .question(question)
                    .answer(answer)
                    .topK(topK)
                    .referenceCount(references.size())
                    .references(references)
                    .promptPreview(promptPreview)
                    .costMs(costMs)
                    .build();

        } catch (Exception e) {
            long costMs = System.currentTimeMillis() - startTime;
            status = "FAILED";
            saveAiCallLog(question, topK, e.getMessage(), costMs, status);
            log.error("RAG ask failed, question={}", question, e);
            throw new RuntimeException("RAG 问答失败: " + e.getMessage(), e);
        }
    }

    private void saveAiCallLog(String question, int topK, String responseSummary, long costMs, String status) {
        try {
            AiCallLog log = new AiCallLog();
            log.setApiType("RAG_ASK");
            log.setRequestSummary("question=" + question + ", topK=" + topK);
            String summary = responseSummary != null && responseSummary.length() > 500
                    ? responseSummary.substring(0, 500) : responseSummary;
            log.setResponseSummary(summary);
            log.setCostMs(costMs);
            log.setStatus(status);
            aiCallLogMapper.insert(log);
        } catch (Exception e) {
            log.error("Failed to save ai_call_log", e);
        }
    }
}