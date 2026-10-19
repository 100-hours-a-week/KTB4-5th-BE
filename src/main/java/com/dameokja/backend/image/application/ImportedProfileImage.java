package com.dameokja.backend.image.application;

import java.util.Objects;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class ImportedProfileImage implements AutoCloseable {
    private final String key;
    private final Runnable cleanup;
    private boolean transactionOwnsImage;
    private boolean closed;

    private ImportedProfileImage(String key, Runnable cleanup) {
        this.key = key;
        this.cleanup = cleanup;
    }

    public static ImportedProfileImage absent() { return new ImportedProfileImage(null, () -> {}); }

    public static ImportedProfileImage uploaded(String key, Runnable cleanup) {
        return new ImportedProfileImage(Objects.requireNonNull(key), Objects.requireNonNull(cleanup));
    }

    public String key() { return key; }

    /**
     * 사진을 현재 가입 트랜잭션에 연결해 커밋하면 유지하고 롤백하면 삭제합니다.
     * 트랜잭션 결과가 불명확하면 커밋된 회원의 사진을 지우지 않도록 유지합니다.
     */
    public void retainOnCommit() {
        if (key == null) {
            return;
        }
        if (closed || transactionOwnsImage || !TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("사진을 연결할 가입 트랜잭션이 필요합니다.");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    cleanup.run();
                }
            }
        });
        transactionOwnsImage = true;
    }

    @Override
    public void close() {
        if (!closed && !transactionOwnsImage) {
            cleanup.run();
        }
        closed = true;
    }
}
