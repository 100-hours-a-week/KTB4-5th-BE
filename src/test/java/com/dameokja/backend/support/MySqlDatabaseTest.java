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
    private static final String TEST_VAPID_PUBLIC_KEY =
            "BBsm6R4Q8Y7zDW6IP3LwAqDKguIYBLa83eH8r18Jd9ts8Dyt3xmcoSyL91wjkGIPymgWPJZPeol1iwrLafmJczY";
    private static final String TEST_VAPID_PRIVATE_KEY = "CaYwQ9blK0k4N0J-5tPLIQzYBpSJj24S6sWadUP7wCg";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        MYSQL.start();
        registry.add("jwt.secret", () -> java.util.Base64.getEncoder()
                .encodeToString(new byte[32]));
        registry.add("push.auth-encryption.key", () -> java.util.Base64.getEncoder()
                .encodeToString(new byte[32]));
        registry.add("push.auth-encryption.key-version", () -> "test-v1");
        // 컨텍스트에서 PushService가 실제 키를 파싱하므로 테스트 전용 P-256 키 쌍을 사용한다.
        registry.add("push.vapid.public-key", () -> TEST_VAPID_PUBLIC_KEY);
        registry.add("push.vapid.private-key", () -> TEST_VAPID_PRIVATE_KEY);
        registry.add("push.vapid.key-version", () -> "test-v1");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.sql.init.mode", () -> "never");
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

}
