package com.dameokja.backend.refrigerator.infrastructure;

import java.security.SecureRandom;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class InvitationCodeConfig {

    @Bean
    SecureRandom secureRandom() {
        return new SecureRandom();
    }
}
