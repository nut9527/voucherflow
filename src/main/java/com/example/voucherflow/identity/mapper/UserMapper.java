package com.example.voucherflow.identity.mapper;

import com.example.voucherflow.identity.model.UserRow;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 按账号查询或新增用户，具体 SQL 定义在同名 XML 映射中。 */
@Mapper
public interface UserMapper {
    Optional<UserRow> findByUsername(@Param("username") String username);

    void insert(UserRow user);
}
