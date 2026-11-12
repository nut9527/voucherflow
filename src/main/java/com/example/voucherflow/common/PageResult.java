package com.example.voucherflow.common;

import java.util.List;
import java.util.function.Function;

/** 游标分页结果，通过多查询一条记录判断是否存在下一页。 */
public record PageResult<T>(List<T> items, String nextCursor) {
    /** rows 应包含至多 size + 1 条记录；下一页游标取本页最后一条，而非额外探测行。 */
    public static <T> PageResult<T> of(List<T> rows, int size, Function<T, String> idExtractor) {
        boolean hasMore = rows.size() > size;
        List<T> items = List.copyOf(rows.subList(0, Math.min(size, rows.size())));
        return new PageResult<>(items, hasMore ? idExtractor.apply(items.getLast()) : null);
    }
}
