package com.dameokja.backend.support;

import com.dameokja.backend.global.config.JpaAuditingConfig;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Repository;

@DataJpaTest(
        properties = {
                "spring.config.import=",
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.sql.init.mode=never"
        },
        includeFilters = @ComponentScan.Filter(
                type = FilterType.ANNOTATION, classes = Repository.class))
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
public abstract class MySqlJpaTest extends MySqlDatabaseTest {
    @Autowired
    protected EntityManager entityManager;

    protected void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
