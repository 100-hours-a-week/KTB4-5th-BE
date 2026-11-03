package com.dameokja.backend.global.config.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;

class KafkaConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.config.location=classpath:application.yml");

    @Test
    void loadsCommonConfigurationWithoutStartingKafka() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(KafkaTemplate.class);
            KafkaProperties properties = context.getBean(KafkaProperties.class);
            assertThat(properties.getListener().isAutoStartup()).isFalse();
            assertThat(properties.getAdmin().isAutoCreate()).isFalse();
            assertThat(properties.getConsumer().getEnableAutoCommit()).isFalse();
            assertThat(properties.getConsumer().getGroupId()).isNull();
            assertThat(properties.getConsumer().getProperties()).doesNotContainKey("spring.json.value.default.type");
        });
    }

    @Test
    void keepsRegisteredListenerStoppedWithoutBroker() {
        contextRunner.withPropertyValues("spring.kafka.bootstrap-servers=127.0.0.1:1").withUserConfiguration(ListenerConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();
            MessageListenerContainer container = context.getBean(KafkaListenerEndpointRegistry.class).getListenerContainer("foundation-test-listener");
            assertThat(container).isNotNull();
            assertThat(container.isAutoStartup()).isFalse();
            assertThat(container.isRunning()).isFalse();
        });
    }

    @Test
    void bindsDeploymentEnvironmentWithoutStartingConsumer() {
        Map<String, Object> environment = Map.of("KAFKA_BOOTSTRAP_SERVERS", "127.0.0.1:1,127.0.0.1:2", "KAFKA_ENV", "dev", "KAFKA_LISTENER_ENABLED", "true");
        contextRunner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(new SystemEnvironmentPropertySource("kafkaTestEnvironment", environment)))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    KafkaProperties properties = context.getBean(KafkaProperties.class);
                    assertThat(properties.getBootstrapServers()).containsExactly("127.0.0.1:1", "127.0.0.1:2");
                    assertThat(properties.getListener().isAutoStartup()).isTrue();
                    assertThat(context.getEnvironment().getProperty("analysis.kafka.request-topic")).isEqualTo("dev.dameokja.analysis.requested.v1");
                });
    }

    @ParameterizedTest
    @CsvSource({
        "analysis.kafka.request-topic, analysis.requested.v1",
        "analysis.kafka.completion-topic, analysis.completed.v1",
        "analysis.kafka.feedback-topic, analysis.feedback.v1",
        "analysis.kafka.completion-dlt-topic, analysis.completed.v1.dlt",
        "recommendation.kafka.request-topic, recommendation.requested.v1",
        "recommendation.kafka.completion-topic, recommendation.completed.v1",
        "cooking.kafka.applied-topic, cooking.applied.v1"
    })
    void resolvesTopicNamesFromSharedEnvironment(String property, String suffix) {
        contextRunner.withPropertyValues("KAFKA_ENV=local").run(context ->
                assertThat(context.getEnvironment().getProperty(property)).isEqualTo("local.dameokja." + suffix));
    }

    @Configuration(proxyBeanMethods = false)
    static class ListenerConfiguration {
        @Bean
        TestListener testListener() { return new TestListener(); }
    }

    static class TestListener {
        @KafkaListener(id = "foundation-test-listener", topics = "foundation-test", groupId = "foundation-test")
        void receive(String message) {}
    }
}
