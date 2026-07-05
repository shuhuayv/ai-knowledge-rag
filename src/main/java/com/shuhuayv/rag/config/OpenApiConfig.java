package com.shuhuayv.rag.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI aiKnowledgeRagOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("AI Knowledge RAG API")
                        .version("v1.0.0")
                        .description("AI 知识库问答系统接口文档，支持文档上传、管理、检索和 RAG 问答。"));
    }
}