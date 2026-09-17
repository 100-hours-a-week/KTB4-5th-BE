package com.dameokja.backend.refrigerator.domain;

import com.dameokja.backend.global.common.BaseEntity;
import com.dameokja.backend.user.domain.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "refrigerator_members")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefrigeratorMember extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "refrigerator_member_id")
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "refrigerator_id", nullable = false)
    private Refrigerator refrigerator;

    @Column(name = "is_active", nullable = false, columnDefinition = "tinyint(1)")
    private Boolean isActive;
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20, columnDefinition = "varchar(20)")
    private RefrigeratorMemberRole role;

    public RefrigeratorMember(User user, Refrigerator refrigerator) {
        this(user, refrigerator, RefrigeratorMemberRole.MEMBER, false);
    }

    private RefrigeratorMember(User user, Refrigerator refrigerator,
                              RefrigeratorMemberRole role, boolean active) {
        this.user = user;
        this.refrigerator = refrigerator;
        this.role = role;
        this.isActive = active;
    }

    public static RefrigeratorMember owner(User user, Refrigerator refrigerator) {
        return new RefrigeratorMember(user, refrigerator, RefrigeratorMemberRole.OWNER, true);
    }

    public void changeActiveStatus(boolean active) { this.isActive = active; }
}
