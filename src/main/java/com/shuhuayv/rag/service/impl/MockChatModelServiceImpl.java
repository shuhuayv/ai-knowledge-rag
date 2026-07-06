package com.shuhuayv.rag.service.impl;

import com.shuhuayv.rag.service.ChatModelService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Mock Chat Model 实现，用于开发测试阶段。
 * 当 ai.mock-enabled=true 时启用。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "ai.mock-enabled", havingValue = "true", matchIfMissing = true)
public class MockChatModelServiceImpl implements ChatModelService {

    @Override
    public String generateAnswer(String prompt) {
        log.info("Mock Chat Model generating answer, promptLength={}", prompt.length());

        // 从 prompt 中提取关键信息构造 Mock 回答
        StringBuilder answer = new StringBuilder();

        // 尝试提取用户问题
        String question = extractTextBetween(prompt, "用户问题：\n", "\n\n检索到的文档片段");
        if (question == null || question.isBlank()) {
            question = "您的问题";
        }

        // 尝试统计引用片段数
        int refCount = 0;
        int idx = prompt.indexOf("【片段 ");
        while (idx >= 0) {
            refCount++;
            idx = prompt.indexOf("【片段 ", idx + 1);
        }

        if (refCount > 0) {
            answer.append("根据已检索到的 ").append(refCount).append(" 个文档片段，");
            answer.append("以下是对「").append(question).append("」的回答：\n\n");

            // 提取每个片段的内容摘要
            for (int i = 1; i <= refCount; i++) {
                String snippet = extractTextBetween(
                        prompt,
                        "【片段 " + i + "】\n",
                        "\n\n"
                );
                if (snippet != null && !snippet.isBlank()) {
                    // 提取"内容："之后的部分
                    int contentIdx = snippet.indexOf("内容：");
                    if (contentIdx >= 0) {
                        snippet = snippet.substring(contentIdx + 3).trim();
                    }
                    if (snippet.length() > 200) {
                        snippet = snippet.substring(0, 200) + "...";
                    }
                    answer.append("【片段 ").append(i).append("】").append(snippet).append("\n\n");
                }
            }
        } else {
            answer.append("未在知识库中找到足够相关的内容来回答您的问题，请尝试上传更多相关文档或换一种方式提问。");
        }

        answer.append("---\n");
        answer.append("（注：以上回答基于 Mock AI 服务生成，仅供参考。设置 AI_MOCK_ENABLED=false 可接入真实大模型 API。）");

        log.info("Mock Chat Model answer generated, answerLength={}", answer.length());
        return answer.toString();
    }

    @Override
    public String getProvider() {
        return "mock";
    }

    @Override
    public String getModel() {
        return "mock";
    }

    private String extractTextBetween(String text, String startMarker, String endMarker) {
        int startIdx = text.indexOf(startMarker);
        if (startIdx < 0) {
            return null;
        }
        startIdx += startMarker.length();
        int endIdx = text.indexOf(endMarker, startIdx);
        if (endIdx < 0) {
            return text.substring(startIdx).trim();
        }
        return text.substring(startIdx, endIdx).trim();
    }
}