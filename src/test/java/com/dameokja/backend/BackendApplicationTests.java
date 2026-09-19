package com.dameokja.backend;

import com.dameokja.backend.support.MySqlDatabaseTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.config.import=",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never"
})
class BackendApplicationTests extends MySqlDatabaseTest {

    @Test
    void contextLoads() {
    }

}
