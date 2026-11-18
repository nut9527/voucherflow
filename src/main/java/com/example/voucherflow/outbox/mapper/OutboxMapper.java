package com.example.voucherflow.outbox.mapper;

import com.example.voucherflow.outbox.DomainEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 写入待发布事件记录，具体 SQL 定义在同名 XML 映射中。 */
@Mapper
public interface OutboxMapper {
    void insert(@Param("event") DomainEvent event, @Param("payload") String payload);
}
