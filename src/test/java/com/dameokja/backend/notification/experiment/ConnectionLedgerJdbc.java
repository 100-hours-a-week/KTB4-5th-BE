package com.dameokja.backend.notification.experiment;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

final class ConnectionLedgerJdbc {
    private ConnectionLedgerJdbc() { }

    static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }

    static Statement statement(Statement delegate, String preparedSql, ConnectionLedgerRecord.Borrow borrow, ConnectionLedgerRecorder recorder) {
        ConnectionLedgerRecord.Sql[] latest = new ConnectionLedgerRecord.Sql[1];
        Class<?> type = delegate instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
        return (Statement) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getName().startsWith("execute")) {
                String sql = args != null && args.length > 0 && args[0] instanceof String text ? text : preparedSql;
                return execute(delegate, method, args, sql, borrow, recorder, latest);
            }
            Object result = invoke(delegate, method, args);
            return result instanceof ResultSet rows && latest[0] != null ? resultSet(rows, latest[0], borrow) : result;
        });
    }

    private static Object execute(Statement delegate, Method method, Object[] args, String sql,
                                  ConnectionLedgerRecord.Borrow borrow, ConnectionLedgerRecorder recorder, ConnectionLedgerRecord.Sql[] latest) throws Throwable {
        int id = recorder.sqlId(sql);
        ConnectionLedgerRecord.Sql record = new ConnectionLedgerRecord.Sql(id, System.nanoTime(), ConnectionLedgerRecorder.cpu());
        latest[0] = record;
        borrow.sqlCount++;
        if (borrow.request != null) {
            borrow.sql.add(record);
        }
        borrow.phase = "execute:" + id;
        borrow.phaseStart = record.start;
        Object result;
        try {
            result = invoke(delegate, method, args);
        } catch (Throwable failure) {
            record.failed = true;
            throw failure;
        } finally {
            record.executeEnd = System.nanoTime();
            record.executeCpuEnd = ConnectionLedgerRecorder.cpu();
            borrow.lastDbEnd = record.executeEnd;
            borrow.phase = "application";
            borrow.phaseStart = record.executeEnd;
        }
        return result instanceof ResultSet rows ? resultSet(rows, record, borrow) : result;
    }

    private static ResultSet resultSet(ResultSet delegate, ConnectionLedgerRecord.Sql sql, ConnectionLedgerRecord.Borrow borrow) {
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class}, (proxy, method, args) -> {
            if (method.getName().equals("next")) {
                return next(delegate, method, args, sql, borrow);
            }
            Object result = invoke(delegate, method, args);
            if (method.getName().equals("close")) {
                sql.resultClose = System.nanoTime();
                borrow.lastDbEnd = sql.resultClose;
            }
            return result;
        });
    }

    private static Object next(ResultSet delegate, Method method, Object[] args, ConnectionLedgerRecord.Sql sql,
                               ConnectionLedgerRecord.Borrow borrow) throws Throwable {
        long start = System.nanoTime();
        borrow.phase = "result-next:" + sql.sqlId;
        borrow.phaseStart = start;
        try {
            Object value = invoke(delegate, method, args);
            if (Boolean.TRUE.equals(value)) {
                sql.rows++;
            }
            return value;
        } finally {
            sql.lastNextEnd = System.nanoTime();
            sql.nextNanos += sql.lastNextEnd - start;
            borrow.lastDbEnd = sql.lastNextEnd;
            borrow.phase = "application";
            borrow.phaseStart = sql.lastNextEnd;
        }
    }
}
