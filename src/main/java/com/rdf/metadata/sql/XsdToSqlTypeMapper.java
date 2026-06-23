package com.rdf.metadata.sql;

import org.eclipse.rdf4j.model.vocabulary.XSD;

import java.util.Map;

/**
 * Reverse-maps W3C XSD datatype IRIs to SQL column type declarations,
 * per target {@link SqlDialect}.
 *
 * <p>This is the inverse direction of {@link com.rdf.metadata.util.XsdTypeMapper}.
 * The SHACL model stores {@code sh:datatype xsd:string}, {@code sh:datatype xsd:long}, etc.;
 * this class converts those back into concrete SQL types like {@code VARCHAR}, {@code BIGINT},
 * or {@code NUMBER(38,0)} depending on the target database.
 *
 * <p>When {@code sh:maxLength} is also present on the property shape, the caller is
 * responsible for appending the length: e.g. {@code VARCHAR} + {@code (255)} = {@code VARCHAR(255)}.
 */
public final class XsdToSqlTypeMapper {

    // ── ANSI / generic mappings ───────────────────────────────────────────────

    private static final Map<String, String> ANSI_MAP = Map.ofEntries(
            Map.entry(XSD.STRING.stringValue(),          "VARCHAR"),
            Map.entry(XSD.INTEGER.stringValue(),         "INTEGER"),
            Map.entry(XSD.LONG.stringValue(),            "BIGINT"),
            Map.entry(XSD.SHORT.stringValue(),           "SMALLINT"),
            Map.entry(XSD.BYTE.stringValue(),            "TINYINT"),
            Map.entry(XSD.DECIMAL.stringValue(),         "DECIMAL"),
            Map.entry(XSD.FLOAT.stringValue(),           "FLOAT"),
            Map.entry(XSD.DOUBLE.stringValue(),          "DOUBLE PRECISION"),
            Map.entry(XSD.BOOLEAN.stringValue(),         "BOOLEAN"),
            Map.entry(XSD.DATE.stringValue(),            "DATE"),
            Map.entry(XSD.TIME.stringValue(),            "TIME"),
            Map.entry(XSD.DATETIME.stringValue(),        "TIMESTAMP"),
            Map.entry(XSD.DATETIMESTAMP.stringValue(),   "TIMESTAMP WITH TIME ZONE"),
            Map.entry(XSD.BASE64BINARY.stringValue(),    "BINARY"),
            Map.entry(XSD.NON_NEGATIVE_INTEGER.stringValue(), "INTEGER")
    );

    // ── Snowflake-specific overrides ──────────────────────────────────────────

    private static final Map<String, String> SNOWFLAKE_MAP = Map.ofEntries(
            Map.entry(XSD.STRING.stringValue(),          "VARCHAR"),
            Map.entry(XSD.INTEGER.stringValue(),         "INTEGER"),
            Map.entry(XSD.LONG.stringValue(),            "BIGINT"),
            Map.entry(XSD.SHORT.stringValue(),           "SMALLINT"),
            Map.entry(XSD.BYTE.stringValue(),            "BYTEINT"),
            Map.entry(XSD.DECIMAL.stringValue(),         "NUMBER"),
            Map.entry(XSD.FLOAT.stringValue(),           "FLOAT"),
            Map.entry(XSD.DOUBLE.stringValue(),          "DOUBLE"),
            Map.entry(XSD.BOOLEAN.stringValue(),         "BOOLEAN"),
            Map.entry(XSD.DATE.stringValue(),            "DATE"),
            Map.entry(XSD.TIME.stringValue(),            "TIME"),
            Map.entry(XSD.DATETIME.stringValue(),        "TIMESTAMP_NTZ"),
            Map.entry(XSD.DATETIMESTAMP.stringValue(),   "TIMESTAMP_TZ"),
            Map.entry(XSD.BASE64BINARY.stringValue(),    "BINARY"),
            Map.entry(XSD.NON_NEGATIVE_INTEGER.stringValue(), "INTEGER")
    );

    // ── PostgreSQL-specific overrides ─────────────────────────────────────────

    private static final Map<String, String> POSTGRESQL_MAP = Map.ofEntries(
            Map.entry(XSD.STRING.stringValue(),          "VARCHAR"),
            Map.entry(XSD.INTEGER.stringValue(),         "INTEGER"),
            Map.entry(XSD.LONG.stringValue(),            "BIGINT"),
            Map.entry(XSD.SHORT.stringValue(),           "SMALLINT"),
            Map.entry(XSD.BYTE.stringValue(),            "SMALLINT"),   // no TINYINT in PG
            Map.entry(XSD.DECIMAL.stringValue(),         "NUMERIC"),
            Map.entry(XSD.FLOAT.stringValue(),           "REAL"),
            Map.entry(XSD.DOUBLE.stringValue(),          "DOUBLE PRECISION"),
            Map.entry(XSD.BOOLEAN.stringValue(),         "BOOLEAN"),
            Map.entry(XSD.DATE.stringValue(),            "DATE"),
            Map.entry(XSD.TIME.stringValue(),            "TIME"),
            Map.entry(XSD.DATETIME.stringValue(),        "TIMESTAMP"),
            Map.entry(XSD.DATETIMESTAMP.stringValue(),   "TIMESTAMPTZ"),
            Map.entry(XSD.BASE64BINARY.stringValue(),    "BYTEA"),
            Map.entry(XSD.NON_NEGATIVE_INTEGER.stringValue(), "INTEGER")
    );

    private XsdToSqlTypeMapper() {}

    /**
     * Map an XSD datatype IRI string to a SQL column type for the given dialect.
     *
     * @param xsdIri   the full XSD IRI string, e.g. {@code "http://www.w3.org/2001/XMLSchema#string"}
     * @param dialect  the target SQL dialect
     * @return SQL type name (without length/precision), defaulting to {@code "VARCHAR"}
     */
    public static String toSqlType(String xsdIri, SqlDialect dialect) {
        if (xsdIri == null) return "VARCHAR";
        Map<String, String> map = switch (dialect) {
            case SNOWFLAKE  -> SNOWFLAKE_MAP;
            case POSTGRESQL -> POSTGRESQL_MAP;
            default         -> ANSI_MAP;
        };
        return map.getOrDefault(xsdIri, "VARCHAR");
    }
}
