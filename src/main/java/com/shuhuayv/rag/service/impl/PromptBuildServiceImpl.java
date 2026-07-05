package com.shuhuayv.rag.service.impl;

import com.shuhuayv.rag.dto.SearchResultItem;
import com.shuhuayv.rag.service.PromptBuildService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PromptBuildServiceImpl implements PromptBuildService {

    @Override
    public String buildPrompt(String question, List<SearchResultItem> references) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("你是企业知识库问答助手。\n");
        prompt.append("请基于以下已检索到的文档片段回答用户问题。\n");
        prompt.append("只能基于给定的资料作答，不要编造或引入资料中没有的信息。\n");
        prompt.append("如果资料不足以回答问题，请明确告知用户。\n\n");

        prompt.append("用户问题：\n");
        prompt.append(question).append("\n\n");

        prompt.append("检索到的文档片段（共 ").append(references.size()).append(" 条）：\n");
        prompt.append("---\n");

        for (int i = 0; i < references.size(); i++) {
            SearchResultItem ref = references.get(i);
            prompt.append("【片段 ").append(i + 1).append("】\n");
            prompt.append("来源：文档 #").append(ref.getDocumentId())
                    .append("，Chunk #").append(ref.getChunkId())
                    .append("，相似度：").append(String.format("%.4f", ref.getScore())).append("\n");
            prompt.append("内容：").append(ref.getContent()).append("\n\n");
        }

        prompt.append("---\n");
        prompt.append("请根据以上资料，用中文简洁回答用户问题。");

        return prompt.toString();
    }
}