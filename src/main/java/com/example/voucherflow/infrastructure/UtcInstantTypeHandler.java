package com.example.voucherflow.infrastructure;

import java.sql.*;
import java.time.Instant;
import java.util.Calendar;
import java.util.TimeZone;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

/** 按 UTC 读写数据库时间戳，避免 JVM 默认时区影响 Instant 的映射。 */
@MappedTypes(Instant.class)
public class UtcInstantTypeHandler extends BaseTypeHandler<Instant> {
    private static Calendar utc() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Instant value, JdbcType jdbcType)
            throws SQLException {
        ps.setTimestamp(i, Timestamp.from(value), utc());
    }

    @Override
    public Instant getNullableResult(ResultSet rs, String column) throws SQLException {
        return instant(rs.getTimestamp(column, utc()));
    }

    @Override
    public Instant getNullableResult(ResultSet rs, int column) throws SQLException {
        return instant(rs.getTimestamp(column, utc()));
    }

    @Override
    public Instant getNullableResult(CallableStatement cs, int column) throws SQLException {
        return instant(cs.getTimestamp(column, utc()));
    }
}
