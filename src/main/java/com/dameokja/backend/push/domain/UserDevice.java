package com.dameokja.backend.push.domain;

import com.dameokja.backend.global.common.BaseEntity;
import com.dameokja.backend.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "user_devices")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserDevice extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_device_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "endpoint", nullable = false, length = 2048)
    private String endpoint;
    @Column(name = "p256dh_key", nullable = false, length = 128)
    private String p256dhKey;
    @Lob
    @Column(name = "auth_secret_encrypted", nullable = false, length = 65535)
    private byte[] authSecretEncrypted;
    @Column(name = "encryption_key_version", nullable = false, length = 64)
    private String encryptionKeyVersion;
    @Column(name = "vapid_key_version", nullable = false, length = 64)
    private String vapidKeyVersion;
    @Column(name = "subscription_version", nullable = false)
    private Long subscriptionVersion;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private UserDeviceStatus status;

    public UserDevice(User user, String endpoint, String p256dhKey, byte[] authSecretEncrypted,
            String encryptionKeyVersion, String vapidKeyVersion) {
        this.user = user;
        this.endpoint = endpoint;
        this.p256dhKey = p256dhKey;
        this.authSecretEncrypted = authSecretEncrypted;
        this.encryptionKeyVersion = encryptionKeyVersion;
        this.vapidKeyVersion = vapidKeyVersion;
        this.subscriptionVersion = 1L;
        this.status = UserDeviceStatus.ACTIVE;
    }

    // 소유자는 같은 endpoint에 대해서만 호출되므로(다른 계정이면 409로 거절) 여기서 바꾸지 않는다.
    public void renew(String p256dhKey, byte[] authSecretEncrypted, String encryptionKeyVersion,
            String vapidKeyVersion) {
        this.p256dhKey = p256dhKey;
        this.authSecretEncrypted = authSecretEncrypted;
        this.encryptionKeyVersion = encryptionKeyVersion;
        this.vapidKeyVersion = vapidKeyVersion;
        this.subscriptionVersion = this.subscriptionVersion + 1;
        this.status = UserDeviceStatus.ACTIVE;
    }

    public void disable() {
        if (this.status == UserDeviceStatus.DISABLED) {
            return;
        }
        this.status = UserDeviceStatus.DISABLED;
        this.subscriptionVersion = this.subscriptionVersion + 1;
    }
}
