package com.dameokja.backend.support;

import com.dameokja.backend.global.config.JpaAuditingConfig;
import com.dameokja.backend.global.config.TimeConfig;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

@DataJpaTest(
        properties = {
                "spring.config.import=",
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.sql.init.mode=never",
                // 쿼리 수를 검증하는 테스트가 별도 컨텍스트를 만들지 않도록 공통으로 켠다.
                "spring.jpa.properties.hibernate.generate_statistics=true"
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, TimeConfig.class})
public abstract class MySqlJpaTest extends MySqlDatabaseTest {
    @Autowired
    protected EntityManager entityManager;

    protected void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
