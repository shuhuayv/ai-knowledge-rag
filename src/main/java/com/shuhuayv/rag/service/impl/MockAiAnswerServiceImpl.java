package com.shuhuayv.rag.service.impl;

import com.shuhuayv.rag.dto.SearchResultItem;
import com.shuhuayv.rag.service.AiAnswerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class MockAiAnswerServiceImpl implements AiAnswerService {

    @Override
    public String generateAnswer(String question, List<SearchResultItem> references) {
        if (references == null || references.isEmpty()) {
            return "未在知识库中找到足够相关的内容来回答您的问题，请尝试上传更多相关文档或换一种方式提问。";
        }

        StringBuilder answer = new StringBuilder();
        answer.append("根据已检索到的 ").append(references.size()).append(" 个文档片段，");

        String topic = extractTopic(references);
        if (topic != null && !topic.isBlank()) {
            answer.append("该文档主要涉及 ").append(topic).append(" 相关内容。");
        } else {
            answer.append("下面为您总结相关要点：");
        }

        answer.append("\n\n");

        for (int i = 0; i < references.size(); i++) {
            SearchResultItem ref = references.get(i);
            String snippet = ref.getContent();
            if (snippet.length() > 200) {
                snippet = snippet.substring(0, 200) + "...";
            }
            answer.append("【片段 ").append(i + 1).append("】（来源：文档 #").append(ref.getDocumentId())
                    .append("，相似度：").append(String.format("%.2f", ref.getScore())).append("）\n");
            answer.append(snippet).append("\n\n");
        }

        answer.append("---\n");
        answer.append("（注：以上回答基于 Mock AI 服务生成，仅供参考。后续接入真实大模型 API 后可获得更准确的回答。）");

        log.info("Mock AI answer generated, question={}, referenceCount={}", question, references.size());
        return answer.toString();
    }

    private String extractTopic(List<SearchResultItem> references) {
        return references.stream()
                .map(SearchResultItem::getContent)
                .collect(Collectors.joining(" "))
                .substring(0, Math.min(100, references.stream()
                        .mapToInt(r -> r.getContent().length())
                        .sum()));
    }
}