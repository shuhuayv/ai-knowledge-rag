package com.shuhuayv.rag.controller;

import com.shuhuayv.rag.common.ApiResponse;
import com.shuhuayv.rag.dto.RagAskRequest;
import com.shuhuayv.rag.dto.RagAskResponse;
import com.shuhuayv.rag.service.RagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "RAG 问答接口", description = "基于检索增强生成的问答接口")
@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    @Operation(summary = "RAG 问答", description = "输入问题，检索相关知识片段，生成回答并返回引用来源")
    @PostMapping("/ask")
    public ApiResponse<RagAskResponse> ask(@Valid @RequestBody RagAskRequest request) {
        int topK = request.getEffectiveTopK();
        RagAskResponse response = ragService.ask(request.getQuestion(), topK);
        return ApiResponse.success(response);
    }
}