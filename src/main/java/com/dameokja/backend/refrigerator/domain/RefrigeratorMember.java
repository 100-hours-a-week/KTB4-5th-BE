package com.dameokja.backend.refrigerator.domain;

import static com.dameokja.backend.global.exception.GlobalExceptionCode.INTERNAL_SERVER_ERROR;
import static com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode.ACCESS_DENIED;
import static com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode.ALREADY_SHARING;
import static com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode.MEMBER_NOT_FOUND;
import static com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode.OWNER_CANNOT_LEAVE;
import static com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode.OWNER_ONLY;
import static com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode.OWN_REFRIGERATOR;
import static com.dameokja.backend.user.domain.UserStatus.ACTIVE;

import com.dameokja.backend.global.exception.CustomException;
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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "refrigerator_members")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefrigeratorMember extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
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
    @Column(name = "ended_at")
    private LocalDateTime endedAt;
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private RefrigeratorMemberRole role;

    public RefrigeratorMember(User user, Refrigerator refrigerator) {
        this(user, refrigerator, RefrigeratorMemberRole.MEMBER, false);
    }

    private RefrigeratorMember(User user, Refrigerator refrigerator,
                              RefrigeratorMemberRole role, boolean active) {
        if (user == null || refrigerator == null) {
            throw new CustomException(INTERNAL_SERVER_ERROR);
        }

        this.user = user;
        this.refrigerator = refrigerator;
        this.role = role;
        this.isActive = active;
    }

    public static RefrigeratorMember owner(User user, Refrigerator refrigerator) {
        return new RefrigeratorMember(user, refrigerator, RefrigeratorMemberRole.OWNER, true);
    }

    public boolean isOwner() {
        return role == RefrigeratorMemberRole.OWNER;
    }

    public boolean isLive() {
        return endedAt == null;
    }

    public boolean isSelected() {
        return isLive() && Boolean.TRUE.equals(isActive);
    }

    public boolean isUserActive() {
        return user.getStatus() == ACTIVE;
    }

    public boolean belongsToRefrigerator(Long refrigeratorId) {
        return Objects.equals(refrigerator.getId(), refrigeratorId);
    }

    /**
     * 이 참여 관계가 종료되지 않았고 요청한 냉장고를 현재 선택했는지 검사한다.
     *
     * @param refrigeratorId 접근하려는 냉장고 ID
     */
    public void validateSelectedAccessTo(Long refrigeratorId) {
        if (!isSelected() || !belongsToRefrigerator(refrigeratorId)) {
            throw new CustomException(ACCESS_DENIED);
        }
    }

    public boolean belongsToUser(User user) {
        return this.user == user
                || (this.user.getId() != null && user != null && Objects.equals(this.user.getId(), user.getId()));
    }

    public void validateJoin(Long refrigeratorId, boolean hasOtherMembers) {
        if (belongsToRefrigerator(refrigeratorId)) {
            throw new CustomException(OWN_REFRIGERATOR);
        }

        if (!isOwner() || hasOtherMembers) {
            throw new CustomException(ALREADY_SHARING);
        }
    }

    public void validateLeave() {
        if (isOwner()) {
            throw new CustomException(OWNER_CANNOT_LEAVE);
        }
    }

    public void validateOwnerForRemoval() {
        if (!isOwner()) {
            throw new CustomException(OWNER_ONLY);
        }
    }

    public void validateRemovalTarget(RefrigeratorMember targetRefrigeratorMembership) {
        if (!targetRefrigeratorMembership.belongsToRefrigerator(refrigerator.getId())) {
            throw new CustomException(MEMBER_NOT_FOUND);
        }

        if (belongsToUser(targetRefrigeratorMembership.getUser()) || targetRefrigeratorMembership.isOwner()) {
            throw new CustomException(OWNER_ONLY);
        }
    }

    public void activate() {
        changeActiveStatus(true);
    }

    public void deactivate() {
        changeActiveStatus(false);
    }

    public void joinSharedRefrigerator() {
        endedAt = null;
        role = RefrigeratorMemberRole.MEMBER;
        isActive = true;
    }

    public void leaveSharedRefrigerator(LocalDateTime endedAt) {
        validateLeave();
        end(endedAt);
    }

    public void end(LocalDateTime time) {
        if (time == null) {
            throw new CustomException(INTERNAL_SERVER_ERROR);
        }

        isActive = false;
        endedAt = time;
    }

    public void updateOwner() {
        role = RefrigeratorMemberRole.OWNER;
    }

    private void changeActiveStatus(boolean active) {
        if (active && endedAt != null) {
            throw new CustomException(INTERNAL_SERVER_ERROR);
        }
        this.isActive = active;
    }
}
