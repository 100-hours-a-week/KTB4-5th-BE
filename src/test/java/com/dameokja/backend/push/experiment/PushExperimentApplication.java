package com.dameokja.backend.push.experiment;

import com.dameokja.backend.BackendApplication;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

final class PushExperimentApplication {
    private final PushExperimentConfig config;
    private final Map<String, Object> databaseProperties;

    PushExperimentApplication(PushExperimentConfig config, Map<String, Object> databaseProperties) {
        this.config = config;
        this.databaseProperties = databaseProperties;
    }

    ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(BackendApplication.class, ExperimentBeans.class)
                .initializers(context -> {
                    context.getBeanFactory().registerSingleton("pushExperimentConfig", config);
                    context.getEnvironment().getPropertySources()
                            .addFirst(new MapPropertySource("pushExperiment", applicationProperties()));
                }).run();
    }

    private Map<String, Object> applicationProperties() {
        Map<String, Object> properties = new HashMap<>(databaseProperties);
        properties.put("server.port", "0");
        properties.put("server.address", "127.0.0.1");
        properties.put("sentry.dsn", "");
        properties.put("sentry.logs.enabled", false);
        properties.put("spring.jpa.show-sql", false);
        properties.put("spring.config.import", "");
        return properties;
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import(PushExperimentData.class)
    static class ExperimentBeans {
        @Bean
        @Primary
        Clock experimentClock(PushExperimentConfig config) { return config.clock(); }

        // 데이터 준비 중 자동 생성·정리가 끼어들지 않도록 자동 스케줄을 차단한다.
        @Bean
        static BeanFactoryPostProcessor disableAutomaticScheduledTasks() {
            return factory -> {
                BeanDefinitionRegistry registry = (BeanDefinitionRegistry) factory;
                String processor = TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME;
                if (registry.containsBeanDefinition(processor)) {
                    registry.removeBeanDefinition(processor);
                }
            };
        }
    }
}
