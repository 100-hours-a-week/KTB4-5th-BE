package com.dameokja.backend.notification.experiment;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

final class ConnectionTraceDataSource extends DelegatingDataSource {
    private final ConnectionTraceRecorder recorder;

    ConnectionTraceDataSource(DataSource source, ConnectionTraceRecorder recorder) {
        super(source);
        this.recorder = recorder;
    }

    @Override
    public Connection getConnection() throws SQLException {
        long started = System.nanoTime();
        long id = recorder.awaiting();
        try {
            Connection connection = super.getConnection();
            ConnectionTraceRecorder.Borrow borrow = recorder.acquired(id, started, connectionId(connection));
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> invokeConnection(connection, borrow, method, args));
        } catch (SQLException exception) {
            recorder.acquisitionFailed(id, exception);
            throw exception;
        }
    }

    private long connectionId(Connection connection) throws SQLException {
        try {
            Class<?> type = Class.forName("com.mysql.cj.jdbc.JdbcConnection");
            Object physical = connection.unwrap(type);
            return ((Number) type.getMethod("getId").invoke(physical)).longValue();
        } catch (ReflectiveOperationException exception) {
            throw new SQLException("Cannot map JDBC connection to MySQL process ID", exception);
        }
    }

    private Object invokeConnection(Connection connection, ConnectionTraceRecorder.Borrow borrow, Method method, Object[] args) throws Throwable {
        if (method.getName().equals("close")) {
            try {
                return invoke(connection, method, args);
            } finally {
                recorder.returned(borrow);
            }
        }
        if (method.getName().equals("commit") || method.getName().equals("rollback")) {
            return execute(connection, borrow, method, args, method.getName());
        }
        Object result = invoke(connection, method, args);
        if (result instanceof Statement statement) {
            String sql = args != null && args.length > 0 && args[0] instanceof String text ? text : "statement";
            Class<?> type = statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
            return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, action, values) ->
                    action.getName().startsWith("execute") ? execute(statement, borrow, action, values, sql) : invoke(statement, action, values));
        }
        return result;
    }

    private Object execute(Object target, ConnectionTraceRecorder.Borrow borrow, Method method, Object[] args, String sql) throws Throwable {
        long start = System.nanoTime();
        long cpu = recorder.cpuNanos();
        borrow.operation = sql;
        borrow.operationStart = start;
        borrow.operationActive = true;
        try {
            return invoke(target, method, args);
        } finally {
            recorder.completedOperation(borrow, sql, start, cpu);
        }
    }

    private Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }
}
