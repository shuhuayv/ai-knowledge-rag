package com.shuhuayv.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
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

    private LocalDateTime createdAt;
}