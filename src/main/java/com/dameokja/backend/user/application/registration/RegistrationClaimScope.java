package com.dameokja.backend.user.application.registration;

import com.dameokja.backend.user.domain.RegistrationClaim;
import com.dameokja.backend.user.infrastructure.OAuthRegistrationStore;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

final class RegistrationClaimScope implements AutoCloseable {
    private final OAuthRegistrationStore registrations;
    private final RegistrationClaim claim;
    private boolean transactionOwnsClaim;
    private boolean closed;

    RegistrationClaimScope(OAuthRegistrationStore registrations, RegistrationClaim claim) {
        this.registrations = registrations;
        this.claim = claim;
    }

    void completeWithTransaction() {
        if (closed || transactionOwnsClaim || !TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("가입 토큰을 연결할 트랜잭션이 필요합니다.");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) {
                    registrations.consume(claim);
                } else if (status == STATUS_ROLLED_BACK) {
                    registrations.release(claim);
                }
                // 결과가 불명확하면 이미 가입된 계정으로 다시 시도하지 않도록 선점을 유지한다.
            }
        });
        transactionOwnsClaim = true;
    }

    @Override
    public void close() {
        if (!closed && !transactionOwnsClaim) {
            registrations.release(claim);
        }
        closed = true;
    }
}
