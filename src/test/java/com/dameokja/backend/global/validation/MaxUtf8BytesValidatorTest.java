package com.dameokja.backend.global.validation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MaxUtf8BytesValidatorTest {
    private final MaxUtf8BytesValidator validator = new MaxUtf8BytesValidator();

    @BeforeEach
    void setUp() {
        MaxUtf8Bytes constraint = mock(MaxUtf8Bytes.class);
        when(constraint.value()).thenReturn(6);
        validator.initialize(constraint);
    }

    @ParameterizedTest
    @CsvSource({"abcdef, true", "abcdefg, false", "한글, true", "한글a, false"})
    void countsUtf8BytesNotCharacters(String value, boolean expected) {
        assertThat(validator.isValid(value, null)).isEqualTo(expected);
    }
}
