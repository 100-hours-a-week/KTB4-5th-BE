package com.dameokja.backend.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;

public abstract class MySqlDatabaseTest {
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.8")
            .withReuse(false)
            // 롤백 검증용 실패 유발 트리거를 테스트 DB에서 생성할 수 있도록 허용한다.
            .withCommand("--log-bin-trust-function-creators=1")
            .withInitScript("db/schema.sql");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        MYSQL.start();
        registry.add("jwt.secret", () -> java.util.Base64.getEncoder()
                .encodeToString(new byte[32]));
        registry.add("push.auth-encryption.key", () -> java.util.Base64.getEncoder()
                .encodeToString(new byte[32]));
        registry.add("push.auth-encryption.key-version", () -> "test-v1");
        registry.add("push.vapid.public-key", () -> "test-vapid-public-key");
        registry.add("push.vapid.key-version", () -> "test-v1");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.sql.init.mode", () -> "never");
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

}
