package com.dameokja.backend.global.common;

import java.util.Objects;
import java.util.UUID;

public record EventEnvelope<T>(String messageId, T payload) {
    public EventEnvelope {
        UUID.fromString(messageId);
        Objects.requireNonNull(payload);
    }

    public static <T> EventEnvelope<T> create(T payload) {
        return new EventEnvelope<>(UUID.randomUUID().toString(), payload);
    }
}
