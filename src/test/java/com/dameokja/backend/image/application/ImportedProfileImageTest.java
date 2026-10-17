package com.dameokja.backend.image.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ImportedProfileImageTest {
    private final Runnable cleanup = mock(Runnable.class);

    @AfterEach
    void clearTransaction() { TransactionSynchronizationManager.clear(); }

    @Test
    void committedPhotoSurvivesClose() {
        beginTransaction();
        try (ImportedProfileImage image = ImportedProfileImage.uploaded("photo", cleanup)) {
            image.retainOnCommit();
            complete(TransactionSynchronization.STATUS_COMMITTED);
        }
        verifyNoInteractions(cleanup);
    }

    @Test
    void rollbackDeletesPhotoExactlyOnceEvenAfterClose() {
        beginTransaction();
        try (ImportedProfileImage image = ImportedProfileImage.uploaded("photo", cleanup)) {
            image.retainOnCommit();
            complete(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
        verify(cleanup).run();
    }

    @Test
    void unknownTransactionOutcomeDoesNotDeletePossiblyCommittedPhoto() {
        beginTransaction();
        try (ImportedProfileImage image = ImportedProfileImage.uploaded("photo", cleanup)) {
            image.retainOnCommit();
            complete(TransactionSynchronization.STATUS_UNKNOWN);
        }
        verifyNoInteractions(cleanup);
    }

    @Test
    void failureToEnlistStillCleansPhotoOnClose() {
        try (ImportedProfileImage image = ImportedProfileImage.uploaded("photo", cleanup)) {
            assertThatThrownBy(image::retainOnCommit).isInstanceOf(IllegalStateException.class);
        }
        verify(cleanup).run();
    }

    private void beginTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    private void complete(int status) {
        TransactionSynchronizationManager.getSynchronizations().forEach(callback -> callback.afterCompletion(status));
    }
}
