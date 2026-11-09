package com.tian.erp.common.response;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 常规列表查询的分页结果。
 * @param items 当前页记录，空结果为列表
 * @param total 符合筛选与权限条件的总记录数
 * @param page 从 1 开始的页码
 * @param size 请求的每页记录数
 * @param <T> 单条记录类型
 */
@Schema(description = "常规业务列表的分页结果")
public record PageResult<T>(
        @Schema(description = "当前页记录") List<T> items,
        @Schema(description = "符合筛选条件的总数", example = "35") long total,
        @Schema(description = "页码，从 1 开始", example = "1") int page,
        @Schema(description = "每页数量，1–200", example = "20") int size) {}
