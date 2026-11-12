package com.example.voucherflow.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 为集成测试创建隔离的 MySQL 数据库，并统一字符集与 UTC 时区。 */
@TestConfiguration(proxyBeanMethods = false)
public class MySqlTestConfiguration {
    @Bean
    @ServiceConnection
    MySQLContainer mysqlContainer() {
        return new MySQLContainer(DockerImageName.parse(
                "mysql@sha256:a81d043930e51745522bd5c430961cf8286f0a23f0a9a2a6e0108f7ea71b0902"))
                .withDatabaseName("voucherflow_test")
                .withUsername("vf_test")
                .withPassword("isolated-test-password")
                .withCommand("--default-time-zone=+00:00", "--character-set-server=utf8mb4");
    }
}
