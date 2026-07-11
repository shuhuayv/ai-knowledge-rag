package com.shuhuayv.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_call_log")
public class AiCallLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String apiType;

    private String provider;

    private String model;

    private String requestSummary;

    private String responseSummary;

    private String errorMessage;

    private Long costMs;

    private String status;

    @Schema(description = "非敏感元数据 JSON（provider/model/dimensions/latencyMs/success/tokenUsage 等，不含任何 Key）")
    private String metadata;

    private LocalDateTime createdAt;
}