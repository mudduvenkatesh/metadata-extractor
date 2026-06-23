package com.rdf.metadata.util;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.vocabulary.XSD;

import java.util.Map;

/**
 * Maps SQL / relational column type names to W3C XSD datatype {@link IRI}s
 * using RDF4J's {@link XSD} vocabulary constants.
 *
 * <p>Covers common Snowflake and PostgreSQL type names.
 * Unknown types default to {@code xsd:string}.
 */
public final class XsdTypeMapper {

    private static final Map<String, IRI> TYPE_MAP = Map.ofEntries(

            // ── Strings ──────────────────────────────────────────────────────
            Map.entry("VARCHAR",                     XSD.STRING),
            Map.entry("CHARACTER VARYING",           XSD.STRING),
            Map.entry("CHAR",                        XSD.STRING),
            Map.entry("CHARACTER",                   XSD.STRING),
            Map.entry("TEXT",                        XSD.STRING),
            Map.entry("NVARCHAR",                    XSD.STRING),
            Map.entry("NCHAR",                       XSD.STRING),
            Map.entry("STRING",                      XSD.STRING),   // Snowflake alias
            Map.entry("CLOB",                        XSD.STRING),

            // ── Integers ─────────────────────────────────────────────────────
            Map.entry("INTEGER",                     XSD.INTEGER),
            Map.entry("INT",                         XSD.INTEGER),
            Map.entry("INT4",                        XSD.INTEGER),
            Map.entry("INT2",                        XSD.SHORT),
            Map.entry("SMALLINT",                    XSD.SHORT),
            Map.entry("BIGINT",                      XSD.LONG),
            Map.entry("INT8",                        XSD.LONG),
            Map.entry("TINYINT",                     XSD.BYTE),
            Map.entry("BYTEINT",                     XSD.BYTE),    // Snowflake

            // ── Decimals ─────────────────────────────────────────────────────
            Map.entry("DECIMAL",                     XSD.DECIMAL),
            Map.entry("NUMERIC",                     XSD.DECIMAL),
            Map.entry("NUMBER",                      XSD.DECIMAL),  // Snowflake
            Map.entry("FLOAT",                       XSD.FLOAT),
            Map.entry("FLOAT4",                      XSD.FLOAT),
            Map.entry("FLOAT8",                      XSD.DOUBLE),
            Map.entry("REAL",                        XSD.FLOAT),
            Map.entry("DOUBLE PRECISION",            XSD.DOUBLE),
            Map.entry("DOUBLE",                      XSD.DOUBLE),

            // ── Boolean ──────────────────────────────────────────────────────
            Map.entry("BOOLEAN",                     XSD.BOOLEAN),
            Map.entry("BOOL",                        XSD.BOOLEAN),

            // ── Date / Time ──────────────────────────────────────────────────
            Map.entry("DATE",                        XSD.DATE),
            Map.entry("TIME",                        XSD.TIME),
            Map.entry("TIMETZ",                      XSD.TIME),
            Map.entry("TIME WITH TIME ZONE",         XSD.TIME),
            Map.entry("TIMESTAMP",                   XSD.DATETIME),
            Map.entry("TIMESTAMP_NTZ",               XSD.DATETIME),        // Snowflake
            Map.entry("TIMESTAMP_LTZ",               XSD.DATETIME),        // Snowflake
            Map.entry("TIMESTAMP_TZ",                XSD.DATETIMESTAMP),   // Snowflake
            Map.entry("TIMESTAMPTZ",                 XSD.DATETIMESTAMP),
            Map.entry("TIMESTAMP WITH TIME ZONE",    XSD.DATETIMESTAMP),
            Map.entry("TIMESTAMP WITHOUT TIME ZONE", XSD.DATETIME),

            // ── Binary ───────────────────────────────────────────────────────
            Map.entry("BINARY",                      XSD.BASE64BINARY),
            Map.entry("VARBINARY",                   XSD.BASE64BINARY),
            Map.entry("BYTEA",                       XSD.BASE64BINARY),    // PostgreSQL

            // ── Semi-structured (Snowflake) ───────────────────────────────────
            Map.entry("VARIANT",                     XSD.STRING),
            Map.entry("OBJECT",                      XSD.STRING),
            Map.entry("ARRAY",                       XSD.STRING),

            // ── PostgreSQL-specific ──────────────────────────────────────────
            Map.entry("UUID",                        XSD.STRING),
            Map.entry("JSON",                        XSD.STRING),
            Map.entry("JSONB",                       XSD.STRING),
            Map.entry("XML",                         XSD.STRING),
            Map.entry("INET",                        XSD.STRING),
            Map.entry("CIDR",                        XSD.STRING),
            Map.entry("MACADDR",                     XSD.STRING),
            Map.entry("MONEY",                       XSD.DECIMAL),
            Map.entry("OID",                         XSD.LONG),
            Map.entry("SERIAL",                      XSD.INTEGER),
            Map.entry("BIGSERIAL",                   XSD.LONG),
            Map.entry("SMALLSERIAL",                 XSD.SHORT)
    );

    private XsdTypeMapper() {}

    /**
     * Map a SQL type name to an RDF4J XSD {@link IRI}.
     * Input is normalised (upper-cased, precision/length stripped) before lookup.
     *
     * @return the corresponding XSD IRI, or {@link XSD#STRING} if unknown
     */
    public static IRI toXsd(String sqlTypeName) {
        if (sqlTypeName == null || sqlTypeName.isBlank()) {
            return XSD.STRING;
        }
        // Normalize: uppercase, strip precision/length e.g. "VARCHAR(255)" -> "VARCHAR"
        String normalised = sqlTypeName.toUpperCase().trim()
                                       .replaceAll("\\(.*\\)", "").trim();
        return TYPE_MAP.getOrDefault(normalised, XSD.STRING);
    }

    /**
     * Return the full XSD IRI string for a SQL type name.
     */
    public static String toXsdUri(String sqlTypeName) {
        return toXsd(sqlTypeName).stringValue();
    }
}
