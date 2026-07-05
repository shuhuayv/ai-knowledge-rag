-- ============================================
-- ai-knowledge-rag 数据库初始化脚本
-- ============================================

-- 创建数据库
CREATE DATABASE IF NOT EXISTS ai_knowledge_rag
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE ai_knowledge_rag;

-- 文档表
CREATE TABLE IF NOT EXISTS kb_document (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    file_name VARCHAR(255) NOT NULL COMMENT '文件名',
    file_type VARCHAR(32) NOT NULL COMMENT '文件类型（TXT/PDF）',
    file_path VARCHAR(512) DEFAULT NULL COMMENT '文件存储路径',
    file_size BIGINT DEFAULT 0 COMMENT '文件大小（字节）',
    status VARCHAR(32) NOT NULL DEFAULT 'UPLOADED' COMMENT '状态：UPLOADED/PARSING/PARSED/FAILED',
    remark VARCHAR(512) DEFAULT NULL COMMENT '备注',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库文档表';

-- 文档分块表
CREATE TABLE IF NOT EXISTS kb_chunk (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    document_id BIGINT NOT NULL COMMENT '关联文档 ID',
    content TEXT NOT NULL COMMENT '分块内容',
    chunk_index INT NOT NULL COMMENT '分块序号',
    page_no INT DEFAULT NULL COMMENT '页码（PDF）',
    token_count INT DEFAULT 0 COMMENT 'Token 数量',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档分块表';

-- AI 调用日志表
CREATE TABLE IF NOT EXISTS ai_call_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    api_type VARCHAR(64) NOT NULL COMMENT 'API 类型（EMBEDDING/CHAT/RERANK）',
    request_summary TEXT COMMENT '请求摘要',
    response_summary TEXT COMMENT '响应摘要',
    cost_ms BIGINT DEFAULT 0 COMMENT '耗时（毫秒）',
    status VARCHAR(32) NOT NULL COMMENT '状态：SUCCESS/FAILED',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 调用日志表';

-- 向量记录表
CREATE TABLE IF NOT EXISTS kb_vector_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    document_id BIGINT NOT NULL COMMENT '关联文档 ID',
    chunk_id BIGINT NOT NULL COMMENT '关联 Chunk ID',
    qdrant_point_id VARCHAR(128) NOT NULL COMMENT 'Qdrant Point ID',
    collection_name VARCHAR(128) NOT NULL COMMENT 'Qdrant Collection 名称',
    vector_dimension INT NOT NULL COMMENT '向量维度',
    status VARCHAR(32) NOT NULL DEFAULT 'INDEXED' COMMENT '状态：INDEXED/FAILED',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='向量记录表';