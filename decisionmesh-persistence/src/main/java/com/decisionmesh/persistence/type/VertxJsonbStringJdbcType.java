package com.decisionmesh.persistence.type;


import org.hibernate.type.descriptor.ValueBinder;
import org.hibernate.type.descriptor.ValueExtractor;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.JavaType;
import org.hibernate.type.descriptor.jdbc.BasicBinder;
import org.hibernate.type.descriptor.jdbc.BasicExtractor;
import org.hibernate.type.descriptor.jdbc.JdbcType;

import java.sql.*;

/**
 * Custom JdbcType for PostgreSQL JSONB columns mapped to Java String.
 *
 * WHY THIS EXISTS:
 *   Hibernate Reactive + Vert.x PG client decodes JSONB columns at the
 *   wire-protocol level into io.vertx.core.json.JsonObject (for {...}) or
 *   io.vertx.core.json.JsonArray (for [...]) before Hibernate sees the value.
 *
 *   @JdbcTypeCode(SqlTypes.VARCHAR) tells Hibernate to call getString() on the
 *   ResultSet — which internally calls Tuple.getString() — which throws:
 *     ClassCastException: Invalid String value type class io.vertx.core.json.JsonArray
 *   because the Vert.x tuple already holds a JsonArray, not a String.
 *
 * FIX:
 *   Use getObject() to retrieve the raw Vert.x value (JsonObject or JsonArray),
 *   then call toString() which serialises them back to their JSON string form.
 *   JsonObject.toString()  → "{...}"
 *   JsonArray.toString()   → "[...]"
 *   String.toString()      → the string itself (safe for plain-text fallback)
 *
 * USAGE:
 *   Replace @JdbcTypeCode(SqlTypes.VARCHAR) with @JdbcType(VertxJsonbStringJdbcType.class)
 *   on any @Column(columnDefinition = "jsonb") field mapped to String.
 */
public class VertxJsonbStringJdbcType implements JdbcType {

    public static final VertxJsonbStringJdbcType INSTANCE = new VertxJsonbStringJdbcType();

    /** JSONB maps to Types.OTHER in the PostgreSQL JDBC dialect. */
    @Override
    public int getJdbcTypeCode() {
        return Types.OTHER;
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    @Override
    public <X> ValueExtractor<X> getExtractor(JavaType<X> javaType) {
        return new BasicExtractor<>(javaType, this) {

            /**
             * Called by Hibernate when reading a column from a ResultSet row.
             * Uses getObject() — NOT getString() — so Vert.x JsonObject/JsonArray
             * comes back as the raw Object rather than failing a String cast.
             */
            @Override
            protected X doExtract(ResultSet rs, int paramIndex, WrapperOptions options)
                    throws SQLException {
                Object raw = rs.getObject(paramIndex);
                if (raw == null) return null;
                // toString() works for String, JsonObject, JsonArray, and any
                // other representation the PG driver might return for jsonb.
                return javaType.wrap(raw.toString(), options);
            }

            @Override
            protected X doExtract(CallableStatement st, int index, WrapperOptions options)
                    throws SQLException {
                Object raw = st.getObject(index);
                if (raw == null) return null;
                return javaType.wrap(raw.toString(), options);
            }

            @Override
            protected X doExtract(CallableStatement st, String name, WrapperOptions options)
                    throws SQLException {
                Object raw = st.getObject(name);
                if (raw == null) return null;
                return javaType.wrap(raw.toString(), options);
            }
        };
    }

    // ── Write ────────────────────────────────────────────────────────────────

    @Override
    public <X> ValueBinder<X> getBinder(JavaType<X> javaType) {
        return new BasicBinder<>(javaType, this) {

            /**
             * Binds the JSON string as Types.OTHER — PostgreSQL implicitly
             * casts the text value to jsonb on the server side.
             */
            @Override
            protected void doBind(PreparedStatement st, X value, int index, WrapperOptions options)
                    throws SQLException {
                String json = javaType.unwrap(value, String.class, options);
                st.setObject(index, json, Types.OTHER);
            }

            @Override
            protected void doBind(CallableStatement st, X value, String name, WrapperOptions options)
                    throws SQLException {
                String json = javaType.unwrap(value, String.class, options);
                st.setObject(name, json, Types.OTHER);
            }
        };
    }
}