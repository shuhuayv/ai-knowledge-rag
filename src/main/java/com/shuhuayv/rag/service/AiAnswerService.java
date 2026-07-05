package com.shuhuayv.rag.service;

import com.shuhuayv.rag.dto.SearchResultItem;

import java.util.List;

public interface AiAnswerService {

    String generateAnswer(String question, List<SearchResultItem> references);
}