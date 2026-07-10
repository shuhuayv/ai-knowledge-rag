package com.shuhuayv.rag.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shuhuayv.rag.dto.RagAskResponse;
import com.shuhuayv.rag.dto.RagReferenceItem;
import com.shuhuayv.rag.dto.SearchResponse;
import com.shuhuayv.rag.dto.SearchResultItem;
import com.shuhuayv.rag.embedding.service.EmbeddingService;
import com.shuhuayv.rag.entity.AiCallLog;
import com.shuhuayv.rag.mapper.AiCallLogMapper;
import com.shuhuayv.rag.service.ChatModelService;
import com.shuhuayv.rag.service.PromptBuildService;
import com.shuhuayv.rag.service.RagService;
import com.shuhuayv.rag.service.SearchService;
import com.shuhuayv.rag.vector.service.CollectionNameResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG 问答服务。
 *
 * <p>关键增强（检索透明性 / 真实 Embedding）：
 * <ul>
 *   <li>检索结果为空（经 min-score 过滤后仍空）时，<b>不调用 Chat</b>，返回固定引导文案，
 *       promptPreview 标记为 {@code [无有效上下文]}，避免大模型凭空编造。</li>
 *   <li>RagAskResponse 填充 Embedding 透明性字段（provider/model/dimensions/mode/collectionName/
 *       retrievalTopK/minScore/candidate/returned/fallbackUsed/qualityNote）。</li>
 *   <li>ai_call_log 写入非敏感 metadata JSON（provider/model/dimensions/latencyMs/success 等）。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
public class RagServiceImpl implements RagService {

    /** 无有效上下文时的固定回答文案（不调用 Chat）。 */
    private static final String NO_CONTEXT_ANSWER =
            "当前知识库中未检索到足够相关的内容，无法基于已上传文档可靠回答该问题。";

    /** 无有效上下文时 promptPreview 标记。 */
    private static final String NO_CONTEXT_PROMPT_MARKER = "[无有效上下文]";

    private final SearchService searchService;
    private final PromptBuildService promptBuildService;
    private final ChatModelService chatModelService;
    private final AiCallLogMapper aiCallLogMapper;
    private final EmbeddingService embeddingService;
    private final CollectionNameResolver collectionNameResolver;
    private final double minScore;
    private final ObjectMapper objectMapper;

    public RagServiceImpl(SearchService searchService,
                          PromptBuildService promptBuildService,
                          ChatModelService chatModelService,
                          AiCallLogMapper aiCallLogMapper,
                          EmbeddingService embeddingService,
                          CollectionNameResolver collectionNameResolver,
                          @Value("${rag.retrieval.min-score:0.0}") double minScore) {
        this.searchService = searchService;
        this.promptBuildService = promptBuildService;
        this.chatModelService = chatModelService;
        this.aiCallLogMapper = aiCallLogMapper;
        this.embeddingService = embeddingService;
        this.collectionNameResolver = collectionNameResolver;
        this.minScore = minScore;
        this.objectMapper = new ObjectMapper();
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

            int candidateCount = searchResponse.getRetrievalCandidateCount();
            int returnedCount = searchResponse.getRetrievalReturnedCount();

            // 无有效上下文：不调用 Chat，直接返回固定引导文案
            if (searchResults.isEmpty()) {
                long costMs = System.currentTimeMillis() - startTime;
                answer = NO_CONTEXT_ANSWER;
                String qualityNote = (candidateCount == 0)
                        ? "无有效上下文"
                        : "检索候选均低于 minScore 阈值，已过滤";

                saveAiCallLog(question, topK, answer, costMs, status, null);

                log.info("RAG ask 无有效上下文，未调用 Chat，provider={}, model={}, question={}",
                        embeddingService.provider(), embeddingService.model(), question);

                return buildResponse(question, answer, topK, List.of(), NO_CONTEXT_PROMPT_MARKER,
                        costMs, candidateCount, returnedCount, qualityNote);
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

            return buildResponse(question, answer, topK, references, promptPreview,
                    costMs, candidateCount, returnedCount, "正常");

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

    private RagAskResponse buildResponse(String question, String answer, int topK,
                                         List<RagReferenceItem> references, String promptPreview,
                                         long costMs, int candidateCount, int returnedCount,
                                         String qualityNote) {
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
                .embeddingProvider(embeddingService.provider())
                .embeddingModel(embeddingService.model())
                .embeddingDimensions(embeddingService.dimensions())
                .embeddingMode(embeddingService.mode().name())
                .collectionName(collectionNameResolver.resolveForCurrentMode(embeddingService,
                        DocumentIndexServiceImpl.INDEX_VERSION))
                .retrievalTopK(topK)
                .retrievalMinScore(minScore)
                .retrievalCandidateCount(candidateCount)
                .retrievalReturnedCount(returnedCount)
                .fallbackUsed(false)
                .retrievalQualityNote(qualityNote)
                .build();
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
            // 非敏感 metadata JSON（不含任何 API Key）
            log.setMetadata(buildMetadata(costMs, status));
            aiCallLogMapper.insert(log);
        } catch (Exception e) {
            log.error("Failed to save ai_call_log", e);
        }
    }

    private String buildMetadata(long costMs, String status) {
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("provider", embeddingService.provider());
            meta.put("model", embeddingService.model());
            meta.put("dimensions", embeddingService.dimensions());
            meta.put("mode", embeddingService.mode().name());
            meta.put("latencyMs", costMs);
            meta.put("success", "SUCCESS".equals(status));
            meta.put("tokenUsage", null);
            meta.put("batchSize", null);
            return objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            return null;
        }
    }
}
