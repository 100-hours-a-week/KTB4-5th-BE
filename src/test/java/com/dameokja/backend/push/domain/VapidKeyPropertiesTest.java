package com.dameokja.backend.push.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VapidKeyPropertiesTest {

    @Test
    void exposesConfiguredValues() {
        VapidKeyProperties properties = new VapidKeyProperties("public-key", "v1");

        assertThat(properties.getPublicKey()).isEqualTo("public-key");
        assertThat(properties.getKeyVersion()).isEqualTo("v1");
    }

    @Test
    void rejectsBlankPublicKey() {
        assertThatThrownBy(() -> new VapidKeyProperties(" ", "v1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankKeyVersion() {
        assertThatThrownBy(() -> new VapidKeyProperties("public-key", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
