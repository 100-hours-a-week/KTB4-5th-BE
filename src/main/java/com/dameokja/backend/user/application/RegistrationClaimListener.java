package com.dameokja.backend.user.application;

import com.dameokja.backend.user.application.OAuthSignupService.RegistrationClaimed;
import com.dameokja.backend.user.infrastructure.OAuthRegistrationStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class RegistrationClaimListener {
    private final OAuthRegistrationStore registrations;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void consume(RegistrationClaimed event) { registrations.consume(event.claim()); }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_ROLLBACK)
    public void release(RegistrationClaimed event) { registrations.release(event.claim()); }
}
