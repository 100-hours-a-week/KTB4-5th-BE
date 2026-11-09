package com.dameokja.backend.notification.infrastructure.kafka;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "notification.expiration-kafka.enabled", havingValue = "true")
public class ExpirationCheckKafkaConfiguration {
    @Bean
    ConcurrentKafkaListenerContainerFactory<Object, Object> expirationCheckListenerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer, ConsumerFactory<Object, Object> consumerFactory) {
        var factory = new ConcurrentKafkaListenerContainerFactory<Object, Object>();
        configurer.configure(factory, consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        // 실패 기록의 offset을 넘기는 운영 정책이 정해질 때까지 동일 기록을 재시도한다.
        var errorHandler = new DefaultErrorHandler(new FixedBackOff(5000L, FixedBackOff.UNLIMITED_ATTEMPTS));
        errorHandler.setClassifications(Map.of(Exception.class, true), true);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }

    @Bean(defaultCandidate = false)
    ThreadPoolTaskScheduler expirationCheckTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("expiration-check-publisher-");
        return scheduler;
    }
}
