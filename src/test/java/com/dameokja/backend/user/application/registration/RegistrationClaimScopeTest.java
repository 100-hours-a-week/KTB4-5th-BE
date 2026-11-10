package com.dameokja.backend.user.application.registration;

import com.dameokja.backend.auth.domain.OAuthIdentity;
import com.dameokja.backend.auth.domain.OAuthProvider;
import com.dameokja.backend.user.domain.RegistrationClaim;
import com.dameokja.backend.user.infrastructure.OAuthRegistrationStore;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class RegistrationClaimScopeTest {
    private final OAuthRegistrationStore registrations = mock(OAuthRegistrationStore.class);
    private final RegistrationClaim claim = new RegistrationClaim(UUID.randomUUID(), UUID.randomUUID(),
            new OAuthIdentity(OAuthProvider.KAKAO, "123", "member@example.com"));

    @AfterEach
    void clearTransaction() { TransactionSynchronizationManager.clear(); }

    @ParameterizedTest
    @ValueSource(ints = {TransactionSynchronization.STATUS_COMMITTED, TransactionSynchronization.STATUS_ROLLED_BACK,
            TransactionSynchronization.STATUS_UNKNOWN})
    void transactionOutcomeOwnsClaimEvenAfterScopeCloses(int status) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try (RegistrationClaimScope scope = new RegistrationClaimScope(registrations, claim)) {
            scope.completeWithTransaction();
        }
        verifyNoInteractions(registrations);
        TransactionSynchronizationManager.getSynchronizations().forEach(callback -> callback.afterCompletion(status));
        if (status == TransactionSynchronization.STATUS_COMMITTED) {
            verify(registrations).consume(claim);
        } else if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
            verify(registrations).release(claim);
        }
        verifyNoMoreInteractions(registrations);
    }

    @Test
    void bindingFailureReleasesClaimExactlyOnce() {
        try (RegistrationClaimScope scope = new RegistrationClaimScope(registrations, claim)) {
            assertThatThrownBy(scope::completeWithTransaction).isInstanceOf(IllegalStateException.class);
        }
        verify(registrations).release(claim);
        verifyNoMoreInteractions(registrations);
    }
}
