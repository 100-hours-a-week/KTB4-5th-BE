package com.dameokja.backend.refrigerator.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class InvitationPropertiesTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(InvitationCodeConfig.class);

    @Test
    void missingSettingDisablesFixedInvitation() {
        context.run(application -> {
            assertThat(application).hasNotFailed();
            InvitationProperties properties = application.getBean(InvitationProperties.class);
            assertThat(properties.usesFixedCode(1L)).isFalse();
            assertThat(properties.usesFixedCode(0L)).isFalse();
            assertThat(properties.usesFixedCode(null)).isFalse();
        });
    }

    @Test
    void positiveSettingAppliesOnlyToTheSelectedRefrigerator() {
        context.withPropertyValues("refrigerator.invitation.fixed-refrigerator-id=123").run(application -> {
            assertThat(application).hasNotFailed();
            InvitationProperties properties = application.getBean(InvitationProperties.class);
            assertThat(properties.usesFixedCode(123L)).isTrue();
            assertThat(properties.usesFixedCode(124L)).isFalse();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "invalid"})
    void invalidSettingFailsStartup(String value) {
        context.withPropertyValues("refrigerator.invitation.fixed-refrigerator-id=" + value)
                .run(application -> assertThat(application).hasFailed());
    }
}
