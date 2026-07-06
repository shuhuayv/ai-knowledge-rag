package com.shuhuayv.rag.service;

/**
 * AI Chat Model 抽象接口，支持 Mock 和 OpenAI-compatible 两种实现。
 * 通过配置 ai.mock-enabled 切换。
 */
public interface ChatModelService {

    /**
     * 使用 AI Chat Model 生成回答。
     *
     * @param prompt 完整的 prompt 文本（包含系统指令、参考资料、用户问题）
     * @return AI 生成的回答文本
     */
    String generateAnswer(String prompt);

    /**
     * 当前使用的 provider 标识（mock / zhipu / deepseek 等）
     */
    String getProvider();

    /**
     * 当前使用的模型名称
     */
    String getModel();
}