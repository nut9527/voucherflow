package com.example.voucherflow.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** 数据库集成测试基类，统一启用测试配置、随机 HTTP 端口及 MySQL 容器。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(MySqlTestConfiguration.class)
public abstract class MySqlIntegrationTest {
    @LocalServerPort protected int port;
    @Autowired protected JdbcTemplate jdbc;
}
