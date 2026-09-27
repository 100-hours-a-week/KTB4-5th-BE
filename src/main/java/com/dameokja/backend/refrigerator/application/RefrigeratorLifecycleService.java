package com.dameokja.backend.refrigerator.application;

import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.refrigerator.domain.RefrigeratorMember;
import com.dameokja.backend.refrigerator.domain.RefrigeratorMemberRole;
import com.dameokja.backend.refrigerator.infrastructure.RefrigeratorMemberRepository;
import com.dameokja.backend.refrigerator.infrastructure.RefrigeratorRepository;
import com.dameokja.backend.user.domain.User;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class RefrigeratorLifecycleService {
    private final RefrigeratorRepository refrigeratorRepository;
    private final RefrigeratorMemberRepository refrigeratorMemberRepository;

    public Long createPersonal(User user, String expiredCountMonth) {
        Refrigerator refrigerator = refrigeratorRepository.save(
                new Refrigerator(user.getNickname(), expiredCountMonth));
        refrigeratorMemberRepository.save(RefrigeratorMember.owner(user, refrigerator));
        return refrigerator.getId();
    }

    public void deleteOwned(Long userId, LocalDateTime deletedAt) {
        for (RefrigeratorMember ownedMembership
                : refrigeratorMemberRepository.findByUserIdAndRole(
                        userId, RefrigeratorMemberRole.OWNER)) {
            Refrigerator refrigerator = refrigeratorRepository.findByIdForUpdate(
                    ownedMembership.getRefrigerator().getId())
                    .orElseThrow();
            refrigerator.delete(deletedAt);
            refrigeratorMemberRepository.deleteAllByRefrigeratorId(refrigerator.getId());
        }
    }
}
