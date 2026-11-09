package com.dameokja.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.dameokja.backend.image.infrastructure.ImageStorageProperties;
import io.sentry.spring.boot4.SentryAutoConfiguration;
import io.sentry.spring.boot4.SentryProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class DeploymentEnvironmentTest {
    private static final List<String> KAFKA_NAMES = List.of("analysis.kafka.request-topic", "analysis.kafka.completion-topic",
            "analysis.kafka.feedback-topic", "analysis.kafka.completion-dlt-topic", "analysis.kafka.completion-group-id",
            "recommendation.kafka.request-topic", "recommendation.kafka.completion-topic", "recommendation.kafka.completion-group-id",
            "recommendation.kafka.completion-dlt-topic", "cooking.kafka.applied-topic", "notification.expiration-kafka.topic",
            "notification.expiration-kafka.group-id", "membership.kafka.topic", "membership.kafka.group-id", "membership.kafka.transactional-id");
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(SentryAutoConfiguration.class))
            .withUserConfiguration(StorageConfiguration.class)
            .withPropertyValues("spring.config.location=classpath:application.yml", "sentry.dsn=", "S3_UPLOAD_BASE_PREFIX=sample-images",
                    "image.storage.bucket=test-bucket", "image.storage.region=us-east-1", "image.storage.presigned-url-ttl=2m",
                    "image.storage.max-upload-bytes=2048", "image.storage.allowed-content-types=image/png");

    @ParameterizedTest
    @ValueSource(strings = {"local", "dev", "stage", "prod"})
    void derivesEveryEnvironmentSettingFromOneVariable(String environment) {
        environment(Map.of("DEPLOY_ENV", environment, "KAFKA_ENV", "obsolete", "S3_UPLOAD_PREFIX", "obsolete/")).run(application -> {
                    assertThat(application).hasNotFailed();
                    assertThat(application.getBean(SentryProperties.class).getEnvironment()).isEqualTo(environment);
                    assertThat(application.getBean(ImageStorageProperties.class).prefix()).isEqualTo("sample-images/" + environment + "/");
                    for (String key : KAFKA_NAMES) {
                        assertThat(application.getEnvironment().getProperty(key)).as(key).startsWith(environment + ".dameokja.");
                    }
                });
    }

    @Test
    void requiresExplicitDeploymentEnvironmentEvenWhenLegacyVariablesExist() {
        environment(Map.of("KAFKA_ENV", "dev", "SENTRY_ENVIRONMENT", "dev", "S3_UPLOAD_PREFIX", "sample-images/dev/"))
                .run(application -> assertThat(application).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/images", "images/", "../images"})
    void rejectsInvalidUploadBasePrefix(String prefix) {
        environment(Map.of("DEPLOY_ENV", "dev")).withPropertyValues("S3_UPLOAD_BASE_PREFIX=" + prefix)
                .run(application -> assertThat(application).hasFailed());
    }

    private ApplicationContextRunner environment(Map<String, Object> variables) {
        return context.withInitializer(application -> application.getEnvironment().getPropertySources().replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables)));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ImageStorageProperties.class)
    static class StorageConfiguration {}
}
