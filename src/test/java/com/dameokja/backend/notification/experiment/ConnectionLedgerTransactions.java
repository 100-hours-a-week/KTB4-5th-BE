package com.dameokja.backend.notification.experiment;

import java.util.IdentityHashMap;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;

final class ConnectionLedgerTransactions implements TransactionExecutionListener {
    private final ConnectionLedgerRecorder recorder;
    private final ThreadLocal<IdentityHashMap<TransactionExecution, ConnectionLedgerRecord.Transaction>> scopes = ThreadLocal.withInitial(IdentityHashMap::new);

    ConnectionLedgerTransactions(ConnectionLedgerRecorder recorder) { this.recorder = recorder; }

    @Override
    public void beforeBegin(TransactionExecution transaction) {
        ConnectionLedgerRecord.Transaction record = recorder.beginTransaction(transaction.getTransactionName(), transaction.isReadOnly());
        if (record != null) {
            scopes.get().put(transaction, record);
        }
    }

    @Override
    public void afterBegin(TransactionExecution transaction, Throwable failure) {
        ConnectionLedgerRecord.Transaction record = scopes.get().get(transaction);
        if (record != null) {
            record.beginAfter = System.nanoTime();
            if (failure != null) {
                complete(transaction, "begin-failed");
            }
        }
    }

    @Override
    public void beforeCommit(TransactionExecution transaction) { beforeCompletion(transaction); }

    @Override
    public void beforeRollback(TransactionExecution transaction) { beforeCompletion(transaction); }

    private void beforeCompletion(TransactionExecution transaction) {
        ConnectionLedgerRecord.Transaction record = scopes.get().get(transaction);
        if (record != null) {
            record.completionBefore = System.nanoTime();
        }
    }

    @Override
    public void afterCommit(TransactionExecution transaction, Throwable failure) { complete(transaction, failure == null ? "committed" : "commit-failed"); }

    @Override
    public void afterRollback(TransactionExecution transaction, Throwable failure) { complete(transaction, failure == null ? "rolled-back" : "rollback-failed"); }

    private void complete(TransactionExecution transaction, String outcome) {
        ConnectionLedgerRecord.Transaction record = scopes.get().remove(transaction);
        if (record != null) {
            record.completionAfter = System.nanoTime();
            record.outcome = outcome;
            recorder.finishTransaction(record);
        }
    }
}
