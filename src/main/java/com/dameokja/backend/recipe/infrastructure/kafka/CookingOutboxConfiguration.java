package com.dameokja.backend.recipe.infrastructure.kafka;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "cooking.kafka.publish-enabled", havingValue = "true")
public class CookingOutboxConfiguration {
    @Bean(defaultCandidate = false)
    ThreadPoolTaskScheduler cookingOutboxTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("cooking-outbox-");
        return scheduler;
    }
}
