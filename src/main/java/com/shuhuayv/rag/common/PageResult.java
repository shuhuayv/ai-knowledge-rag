package com.shuhuayv.rag.common;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "通用分页返回对象")
public class PageResult<T> {

    @Schema(description = "当前页码", example = "1")
    private long pageNum;

    @Schema(description = "每页条数", example = "10")
    private long pageSize;

    @Schema(description = "总记录数", example = "100")
    private long total;

    @Schema(description = "总页数", example = "10")
    private long pages;

    @Schema(description = "数据列表")
    private List<T> records;

    public static <T> PageResult<T> of(long pageNum, long pageSize, long total, List<T> records) {
        long pages = total == 0 ? 0 : (total + pageSize - 1) / pageSize;
        return new PageResult<>(pageNum, pageSize, total, pages, records);
    }
}