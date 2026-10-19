package com.dameokja.backend.analysis.infrastructure;

import java.util.Arrays;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;

import static org.assertj.core.api.Assertions.assertThat;

class AiAnalysisConfigTest {
    private static final String[] VALID_PROPERTIES = {"ai.analysis.base-url=http://ai.example.com", "ai.analysis.api-key=test-key",
            "ai.analysis.connect-timeout=1s", "ai.analysis.read-timeout=2s"};
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AiAnalysisConfig.class)
            .withPropertyValues(VALID_PROPERTIES);

    @ParameterizedTest
    @ValueSource(strings = {"ai.analysis.api-key=", "ai.analysis.base-url=ftp://ai.example.com", "ai.analysis.base-url=http:/ai", "ai.analysis.connect-timeout=0ms",
            "ai.analysis.read-timeout=-1s", "ai.analysis.read-timeout=1ns", "ai.analysis.base-url=http://user@ai.example.com",
            "ai.analysis.base-url=http://ai.example.com?key=value", "ai.analysis.base-url=http://ai.example.com#fragment"})
    void rejectsInvalidConfiguration(String property) {
        contextRunner.withPropertyValues(property).run(context -> assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"base-url", "api-key", "connect-timeout", "read-timeout"})
    void rejectsEachMissingProperty(String name) {
        new ApplicationContextRunner().withUserConfiguration(AiAnalysisConfig.class)
                .withPropertyValues(Arrays.stream(VALID_PROPERTIES).filter(property -> !property.startsWith("ai.analysis." + name + "=")).toArray(String[]::new))
                .run(context -> assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://ai.example.com", "https://ai.example.com"})
    void acceptsSupportedAddressWithMinimumTimeouts(String baseUrl) {
        contextRunner.withPropertyValues("ai.analysis.base-url=" + baseUrl,
                "ai.analysis.connect-timeout=1ms", "ai.analysis.read-timeout=1ms")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
