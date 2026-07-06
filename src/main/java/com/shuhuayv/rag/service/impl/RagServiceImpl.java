package com.shuhuayv.rag.service.impl;

import com.shuhuayv.rag.dto.RagAskResponse;
import com.shuhuayv.rag.dto.RagReferenceItem;
import com.shuhuayv.rag.dto.SearchResponse;
import com.shuhuayv.rag.dto.SearchResultItem;
import com.shuhuayv.rag.entity.AiCallLog;
import com.shuhuayv.rag.mapper.AiCallLogMapper;
import com.shuhuayv.rag.service.ChatModelService;
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
    private final ChatModelService chatModelService;
    private final AiCallLogMapper aiCallLogMapper;

    public RagServiceImpl(SearchService searchService,
                          PromptBuildService promptBuildService,
                          ChatModelService chatModelService,
                          AiCallLogMapper aiCallLogMapper) {
        this.searchService = searchService;
        this.promptBuildService = promptBuildService;
        this.chatModelService = chatModelService;
        this.aiCallLogMapper = aiCallLogMapper;
    }

    @Override
    public RagAskResponse ask(String question, int topK) {
        long startTime = System.currentTimeMillis();
        String status = "SUCCESS";
        String errorMessage = null;
        String answer = null;
        String prompt = null;

        try {
            SearchResponse searchResponse = searchService.search(question, topK);
            List<SearchResultItem> searchResults = searchResponse.getResults();
            if (searchResults == null) {
                searchResults = List.of();
            }

            prompt = promptBuildService.buildPrompt(question, searchResults);
            answer = chatModelService.generateAnswer(prompt);

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

            saveAiCallLog(question, topK, answer, costMs, status, null);

            log.info("RAG ask completed, provider={}, model={}, question={}, topK={}, referenceCount={}, costMs={}",
                    chatModelService.getProvider(), chatModelService.getModel(),
                    question, topK, references.size(), costMs);

            return RagAskResponse.builder()
                    .question(question)
                    .answer(answer)
                    .topK(topK)
                    .referenceCount(references.size())
                    .references(references)
                    .promptPreview(promptPreview)
                    .costMs(costMs)
                    .provider(chatModelService.getProvider())
                    .model(chatModelService.getModel())
                    .build();

        } catch (Exception e) {
            long costMs = System.currentTimeMillis() - startTime;
            status = "FAILED";
            errorMessage = e.getMessage();
            String summary = answer != null ? answer : errorMessage;
            saveAiCallLog(question, topK, summary, costMs, status, errorMessage);
            log.error("RAG ask failed, provider={}, model={}, question={}",
                    chatModelService.getProvider(), chatModelService.getModel(), question, e);
            throw new RuntimeException("RAG 问答失败: " + e.getMessage(), e);
        }
    }

    private void saveAiCallLog(String question, int topK, String responseSummary,
                               long costMs, String status, String errorMessage) {
        try {
            AiCallLog log = new AiCallLog();
            log.setApiType("RAG_ASK");
            log.setProvider(chatModelService.getProvider());
            log.setModel(chatModelService.getModel());
            log.setRequestSummary("question=" + question + ", topK=" + topK);
            String summary = responseSummary != null && responseSummary.length() > 500
                    ? responseSummary.substring(0, 500) : responseSummary;
            log.setResponseSummary(summary);
            if (errorMessage != null && errorMessage.length() > 500) {
                errorMessage = errorMessage.substring(0, 500);
            }
            log.setErrorMessage(errorMessage);
            log.setCostMs(costMs);
            log.setStatus(status);
            aiCallLogMapper.insert(log);
        } catch (Exception e) {
            log.error("Failed to save ai_call_log", e);
        }
    }
}