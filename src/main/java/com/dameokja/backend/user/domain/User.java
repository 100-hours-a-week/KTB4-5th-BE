package com.dameokja.backend.user.domain;

import com.dameokja.backend.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long id;

    @Column(name = "nickname", nullable = false, length = 10)
    private String nickname;
    @Column(name = "profile_image_key", nullable = false, length = 1024)
    private String profileImageKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20, columnDefinition = "varchar(20)")
    private UserStatus status;
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20, columnDefinition = "varchar(20)")
    private UserRole role;
    @Column(name = "cooking_count", nullable = false)
    private Integer cookingCount;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
    @Column(name = "login_id", length = 10)
    private String loginId;
    @Column(name = "password_hash", length = 60)
    private String passwordHash;
    @Column(name = "password_changed_at")
    private LocalDateTime passwordChangedAt;

    public User(String nickname, String profileImageKey, String loginId,
                String passwordHash, LocalDateTime passwordChangedAt) {
        this.nickname = nickname;
        this.status = UserStatus.ACTIVE;
        this.role = UserRole.USER;
        this.cookingCount = 0;
        this.profileImageKey = profileImageKey;
        this.loginId = loginId;
        this.passwordHash = passwordHash;
        this.passwordChangedAt = passwordChangedAt;
    }

    public void updateProfile(String nickname, String profileImageKey) {
        this.nickname = nickname;
        this.profileImageKey = profileImageKey;
    }

}
