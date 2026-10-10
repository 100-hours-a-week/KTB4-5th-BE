package com.dameokja.backend.auth.application;

import com.dameokja.backend.auth.application.AuthService.RefreshSessionCreationRequested;
import com.dameokja.backend.auth.infrastructure.RefreshSessionStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class RefreshSessionRollbackListener {
    private final RefreshSessionStore sessions;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_ROLLBACK)
    public void revoke(RefreshSessionCreationRequested event) { sessions.revoke(event.payload()); }
}
