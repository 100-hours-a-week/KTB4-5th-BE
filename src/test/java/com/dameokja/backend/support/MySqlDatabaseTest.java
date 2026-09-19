package com.dameokja.backend.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;

public abstract class MySqlDatabaseTest {
    // One disposable container per test JVM; Ryuk removes it when the JVM exits. No reuse.
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.8")
            .withReuse(false)
            // Allow failure-injection triggers in the disposable test database only.
            .withCommand("--log-bin-trust-function-creators=1")
            .withInitScript("db/schema.sql");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        MYSQL.start();
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.sql.init.mode", () -> "never");
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

}
