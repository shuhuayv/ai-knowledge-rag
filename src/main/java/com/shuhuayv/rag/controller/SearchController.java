package com.shuhuayv.rag.controller;

import com.shuhuayv.rag.common.ApiResponse;
import com.shuhuayv.rag.dto.SearchRequest;
import com.shuhuayv.rag.dto.SearchResponse;
import com.shuhuayv.rag.service.SearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "语义检索接口", description = "基于向量相似度的 TopK 语义检索")
@RestController
@RequestMapping("/api")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @Operation(summary = "语义检索", description = "输入查询内容，返回 TopK 最相关的文档 Chunk")
    @PostMapping("/search")
    public ApiResponse<SearchResponse> search(@Valid @RequestBody SearchRequest request) {
        int topK = request.getEffectiveTopK();
        SearchResponse response = searchService.search(request.getQuery(), topK);
        return ApiResponse.success(response);
    }
}