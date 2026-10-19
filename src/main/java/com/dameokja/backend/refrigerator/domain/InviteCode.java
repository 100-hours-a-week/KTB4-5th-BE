package com.dameokja.backend.refrigerator.domain;

import static com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode.EXPIRED_INVITE;

import com.dameokja.backend.global.exception.CustomException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "invite_codes")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InviteCode {
    private static final Duration VALIDITY = Duration.ofMinutes(5);
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "invite_code_id")
    private Long id;
    @Column(nullable = false, length = 6, columnDefinition = "char(6)")
    private String code;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "refrigerator_id", nullable = false)
    private Refrigerator refrigerator;

    public InviteCode(String code, Refrigerator refrigerator, LocalDateTime issuedAt) {
        this.code = code;
        this.refrigerator = refrigerator;
        this.createdAt = issuedAt;
    }

    public LocalDateTime expiresAt() {
        return createdAt.plus(VALIDITY);
    }

    public boolean isValidAt(LocalDateTime now) {
        return now.isBefore(expiresAt());
    }

    /**
     * 요청한 코드가 이 초대 코드와 같고 발급 후 5분인 만료 시각 전인지 검사한다.
     * 최신 코드 조회와 재발급의 직렬화는 호출 서비스가 냉장고 잠금 아래 보장한다.
     * 검사로 코드를 소진하거나 만료 시각을 연장하지 않는다.
     *
     * @param requestedCode 참여 요청에서 받은 초대 코드
     * @param checkedAt 유효성을 검사하는 시각
     */
    public void validateUsable(String requestedCode, LocalDateTime checkedAt) {
        validateMatches(requestedCode);
        if (!isValidAt(checkedAt)) {
            throw new CustomException(EXPIRED_INVITE);
        }
    }

    public void validateMatches(String requestedCode) {
        if (!code.equals(requestedCode)) {
            throw new CustomException(EXPIRED_INVITE);
        }
    }
}
