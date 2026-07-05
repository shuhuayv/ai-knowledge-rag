package com.shuhuayv.rag.service;

import com.shuhuayv.rag.dto.RagAskResponse;

public interface RagService {

    RagAskResponse ask(String question, int topK);
}