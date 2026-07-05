package com.shuhuayv.rag.service;

import com.shuhuayv.rag.dto.SearchResponse;

public interface SearchService {

    SearchResponse search(String query, int topK);
}