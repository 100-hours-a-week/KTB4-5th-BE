package com.dameokja.backend.push.experiment;

import java.util.Locale;
import org.hibernate.resource.jdbc.spi.StatementInspector;

final class PushExperimentSqlCapture implements StatementInspector {
    private boolean capturing;
    private String capturedSql;

    void begin() {
        capturedSql = null;
        capturing = true;
    }

    @Override
    public String inspect(String sql) {
        if (capturing && capturedSql == null && sql.stripLeading().toLowerCase(Locale.ROOT).startsWith("select ")) {
            capturedSql = sql;
        }
        return sql;
    }

    String finish() {
        capturing = false;
        String sql = capturedSql;
        capturedSql = null;
        return sql;
    }
}
