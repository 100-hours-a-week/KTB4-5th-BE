package com.dameokja.backend.push.domain;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Getter
@Component
public class VapidKeyProperties {
    private final String publicKey;
    private final String keyVersion;

    public VapidKeyProperties(@Value("${push.vapid.public-key}") String publicKey,
            @Value("${push.vapid.key-version}") String keyVersion) {
        validate(publicKey, "공개키");
        validate(keyVersion, "키 버전");
        this.publicKey = publicKey;
        this.keyVersion = keyVersion;
    }

    private void validate(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("VAPID " + label + "는 비어 있을 수 없습니다.");
        }
    }
}
