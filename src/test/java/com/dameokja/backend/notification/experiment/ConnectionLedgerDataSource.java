package com.dameokja.backend.notification.experiment;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

final class ConnectionLedgerDataSource extends DelegatingDataSource {
    private static final Map<String, Integer> CONTROLS = Map.of("commit", 1, "rollback", 2, "setAutoCommit", 3,
            "setReadOnly", 4, "setTransactionIsolation", 5);
    private final ConnectionLedgerRecorder recorder;
    private final Class<?> physicalType;
    private final Method physicalId;

    ConnectionLedgerDataSource(DataSource delegate, ConnectionLedgerRecorder recorder) throws ReflectiveOperationException {
        super(delegate);
        this.recorder = recorder;
        physicalType = Class.forName("com.mysql.cj.jdbc.JdbcConnection");
        physicalId = physicalType.getMethod("getId");
    }

    @Override
    public Connection getConnection() throws SQLException {
        long requested = System.nanoTime();
        long id = recorder.awaiting(requested);
        try {
            Connection connection = super.getConnection();
            long connectionId = ((Number) physicalId.invoke(connection.unwrap(physicalType))).longValue();
            ConnectionLedgerRecord.Borrow borrow = recorder.acquired(id, connectionId, requested);
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> invokeConnection(connection, method, args, borrow));
        } catch (Exception failure) {
            recorder.failedAcquisition(id);
            if (failure instanceof SQLException sql) {
                throw sql;
            }
            throw new SQLException("Cannot map measured MySQL connection", failure);
        }
    }

    private Object invokeConnection(Connection delegate, Method method, Object[] args, ConnectionLedgerRecord.Borrow borrow) throws Throwable {
        if (method.getName().equals("close")) {
            borrow.closeStart = System.nanoTime();
            try {
                return ConnectionLedgerJdbc.invoke(delegate, method, args);
            } finally {
                recorder.returned(borrow);
            }
        }
        if (CONTROLS.containsKey(method.getName())) {
            return control(delegate, method, args, borrow);
        }
        Object value = ConnectionLedgerJdbc.invoke(delegate, method, args);
        if (value instanceof Statement statement) {
            String text = args != null && args.length > 0 && args[0] instanceof String sql ? sql : "statement";
            return ConnectionLedgerJdbc.statement(statement, text, borrow, recorder);
        }
        return value;
    }

    private Object control(Connection delegate, Method method, Object[] args, ConnectionLedgerRecord.Borrow borrow) throws Throwable {
        long start = System.nanoTime();
        borrow.phase = method.getName() + (args != null && args.length > 0 ? ":" + args[0] : "");
        borrow.phaseStart = start;
        try {
            return ConnectionLedgerJdbc.invoke(delegate, method, args);
        } finally {
            long end = System.nanoTime();
            if (borrow.request != null) {
                borrow.controls.add(new long[]{CONTROLS.get(method.getName()), start, end,
                        args != null && args.length > 0 && args[0] instanceof Boolean value ? (value ? 1 : 0) : -1});
            }
            borrow.lastDbEnd = end;
            borrow.phase = "application";
            borrow.phaseStart = end;
        }
    }
}
