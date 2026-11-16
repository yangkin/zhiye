package com.tcl.csot.zhiye.util;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;

public final class Db {
    private Db() {}

    public static Instant ts(ResultSet rs, String col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    public static String iso(Instant i) { return i == null ? null : i.toString(); }

    public static OffsetDateTime odt(Instant i) { return i == null ? null : OffsetDateTime.ofInstant(i, java.time.ZoneOffset.UTC); }
}
