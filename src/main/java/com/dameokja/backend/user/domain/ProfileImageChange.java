package com.dameokja.backend.user.domain;

import java.util.Objects;

public sealed interface ProfileImageChange {
    enum Keep implements ProfileImageChange { INSTANCE }
    enum Reset implements ProfileImageChange { INSTANCE }
    record Replace(String key) implements ProfileImageChange {
        public Replace {
            Objects.requireNonNull(key);
        }
    }

    default String resolve(String currentKey, String defaultKey) {
        return switch (this) {
            case Keep ignored -> currentKey;
            case Reset ignored -> defaultKey;
            case Replace replacement -> replacement.key();
        };
    }
}
