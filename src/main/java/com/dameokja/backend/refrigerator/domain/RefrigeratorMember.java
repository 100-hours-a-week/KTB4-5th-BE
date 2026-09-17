package com.dameokja.backend.refrigerator.domain;

import com.dameokja.backend.global.common.BaseEntity;
import com.dameokja.backend.user.domain.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

@Getter
@Entity
@Table(name = "refrigerator_members")
@DynamicInsert
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

    @Generated(event = EventType.INSERT, writable = true)
    @Column(name = "is_active", nullable = false, columnDefinition = "tinyint(1)")
    private Boolean isActive;
    @Generated(event = EventType.INSERT, writable = true)
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20, columnDefinition = "varchar(20)")
    private RefrigeratorMemberRole role;

    // Unspecified selection and role use the database defaults.
    public RefrigeratorMember(User user, Refrigerator refrigerator) {
        this.user = user;
        this.refrigerator = refrigerator;
    }

    public static RefrigeratorMember owner(User user, Refrigerator refrigerator) {
        RefrigeratorMember member = new RefrigeratorMember(user, refrigerator);
        member.role = RefrigeratorMemberRole.OWNER;
        member.isActive = true;
        return member;
    }

    public void changeActiveStatus(boolean active) { this.isActive = active; }
}
