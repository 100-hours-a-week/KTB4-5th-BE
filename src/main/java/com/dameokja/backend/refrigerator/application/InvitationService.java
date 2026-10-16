package com.dameokja.backend.refrigerator.application;

import static com.dameokja.backend.global.exception.GlobalExceptionCode.INTERNAL_SERVER_ERROR;
import static com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode.ACCESS_DENIED;
import static com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode.INVALID_INVITE;
import static com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode.INVITE_NOT_FOUND;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.refrigerator.domain.InviteCode;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.refrigerator.domain.RefrigeratorMember;
import com.dameokja.backend.refrigerator.infrastructure.InviteCodeRepository;
import com.dameokja.backend.refrigerator.infrastructure.RefrigeratorMemberRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InvitationService {
    private static final int CODE_LENGTH = 6;
    private static final int CODE_RADIX = 36;
    private static final long CODE_SPACE_SIZE = (long) Math.pow(CODE_RADIX, CODE_LENGTH);
    private static final int GENERATION_ATTEMPTS = 10;

    private final RefrigeratorAccessService refrigeratorAccessService;
    private final RefrigeratorMemberRepository refrigeratorMemberRepository;
    private final InviteCodeRepository inviteCodeRepository;
    private final Clock clock;
    private final SecureRandom secureRandom;

    @Transactional
    public InvitationView issueCode(Long userId, Long refrigeratorId) {
        Refrigerator refrigerator = refrigeratorAccessService.lockForWrite(userId, refrigeratorId);
        requireOwner(userId, refrigeratorId);

        String code = generateUniqueCode();
        LocalDateTime issuedAt = BusinessTime.now(clock);
        InviteCode invitationCode = new InviteCode(code, refrigerator, issuedAt);

        InviteCode savedInvitationCode = inviteCodeRepository.save(invitationCode);
        return InvitationView.from(savedInvitationCode);
    }

    public InvitationView getValidCode(Long userId, Long refrigeratorId) {
        refrigeratorAccessService.validateReadAccess(userId, refrigeratorId);
        requireOwner(userId, refrigeratorId);

        Optional<InviteCode> latestInvitationCode = inviteCodeRepository
                .findFirstByRefrigeratorIdOrderByIdDesc(refrigeratorId);
        LocalDateTime checkedAt = BusinessTime.now(clock);

        return latestInvitationCode
                .filter(invitationCode -> invitationCode.isValidAt(checkedAt))
                .map(InvitationView::from)
                .orElse(null);
    }

    // 이 메서드는 가입 TX의 냉장고 잠금 아래 호출해 재발급과 참여 저장의 순서를 맞춘다.
    public void validateCurrent(Long refrigeratorId, String requestedCode) {
        InviteCode latestInvitationCode = inviteCodeRepository
                .findHistoryForUpdate(refrigeratorId, PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .orElseThrow(() -> new CustomException(INVITE_NOT_FOUND));

        LocalDateTime checkedAt = BusinessTime.now(clock);
        latestInvitationCode.validateUsable(requestedCode, checkedAt);
    }

    public Long resolveRefrigerator(String inviteCode) {
        if (inviteCode == null || !inviteCode.matches("[A-Z0-9]{6}")) {
            throw new CustomException(INVALID_INVITE);
        }

        return inviteCodeRepository.findByCode(inviteCode)
                .map(invitationCode -> invitationCode.getRefrigerator().getId())
                .orElseThrow(() -> new CustomException(INVITE_NOT_FOUND));
    }

    private void requireOwner(Long userId, Long refrigeratorId) {
        RefrigeratorMember refrigeratorMember = refrigeratorMemberRepository
                .findLiveMembership(userId, refrigeratorId)
                .orElseThrow(() -> new CustomException(ACCESS_DENIED));

        if (!refrigeratorMember.isOwner()) {
            throw new CustomException(ACCESS_DENIED);
        }
    }

    private String generateUniqueCode() {
        return Stream.generate(this::generateCode)
                .limit(GENERATION_ATTEMPTS)
                .filter(candidate -> !inviteCodeRepository.existsByCode(candidate))
                .findFirst()
                .orElseThrow(() -> new CustomException(INTERNAL_SERVER_ERROR));
    }

    private String generateCode() {
        long randomValue = secureRandom.nextLong(CODE_SPACE_SIZE);
        String codeValue = Long.toString(randomValue, CODE_RADIX);
        String uppercaseCode = codeValue.toUpperCase(Locale.ROOT);
        int missingCharacters = CODE_LENGTH - uppercaseCode.length();

        return "0".repeat(missingCharacters) + uppercaseCode;
    }
}
