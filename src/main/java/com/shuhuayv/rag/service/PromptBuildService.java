package com.shuhuayv.rag.service;

import com.shuhuayv.rag.dto.SearchResultItem;

import java.util.List;

public interface PromptBuildService {

    String buildPrompt(String question, List<SearchResultItem> references);
}