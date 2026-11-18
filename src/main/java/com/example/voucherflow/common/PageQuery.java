package com.example.voucherflow.common;

/** 基于主键的游标分页参数：查询编号大于 afterId 的后续记录。 */
public record PageQuery(long afterId, int size) {
    public PageQuery {
        if (afterId < 0 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "分页大小须为 1–100，游标不能为负数");
        }
    }

    public PageQuery() {
        this(0, 20);
    }
}
