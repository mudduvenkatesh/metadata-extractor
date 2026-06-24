package com.rdf.metadata.rdf;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

/**
 * RDF vocabulary constants for the DataRepository ({@code dr:}) namespace.
 *
 * <p>Namespace: {@code http://example.org/datarepository#}
 *
 * <h3>Key design: ExtractionRecord</h3>
 * <p>Every time a schema is extracted, a {@code dr:ExtractionRecord} individual
 * is created with a <b>unique IRI</b> encoding the database, schema, and
 * extraction timestamp. This individual acts as the root of the named graph
 * that holds the complete snapshot — repo info, schema, tables, columns —
 * all scoped to that one extraction event.
 *
 * <pre>
 * IRI pattern:
 *   &lt;base&gt;extraction/&lt;db&gt;/&lt;schema&gt;/&lt;yyyy-MM-dd_HH-mm-ss-SSS&gt;
 *
 * Example:
 *   http://example.org/schema#extraction/MY_DB/PUBLIC/2026-06-23_10-00-00-000
 * </pre>
 */
public final class DataRepositoryVocabulary {

    public static final String NS = "http://example.org/datarepository#";

    private static final SimpleValueFactory VF = SimpleValueFactory.getInstance();

    private DataRepositoryVocabulary() {}

    // ── Core classes ──────────────────────────────────────────────────────────

    /** Represents a connected relational database source. */
    public static final IRI DataRepository  = iri("DataRepository");

    /** A named schema within a {@code DataRepository}. */
    public static final IRI DatabaseSchema  = iri("DatabaseSchema");

    /** A table or view within a {@code DatabaseSchema}. */
    public static final IRI DatabaseTable   = iri("DatabaseTable");

    /** A column within a {@code DatabaseTable}. */
    public static final IRI TableColumn     = iri("TableColumn");

    /**
     * {@code dr:ExtractionRecord} — a timestamped snapshot event.
     *
     * <p>Each extraction produces exactly one {@code ExtractionRecord} individual.
     * Its IRI is unique and encodes the database, schema, and extraction timestamp,
     * making every snapshot independently addressable and queryable.
     *
     * <p>The individual carries:
     * <ul>
     *   <li>All repo connection properties ({@code dr:databaseType}, {@code dr:host}, etc.)</li>
     *   <li>{@code dr:forSchema} — link to the stable {@code dr:DatabaseSchema}</li>
     *   <li>{@code dr:extractedAt} — precise extraction timestamp</li>
     *   <li>{@code dr:tableCount}, {@code dr:columnCount} — summary statistics</li>
     *   <li>Structural links: {@code dr:hasTable} → {@code dr:DatabaseTable}
     *       → {@code dr:hasColumn} → {@code dr:TableColumn}</li>
     * </ul>
     */
    public static final IRI ExtractionRecord = iri("ExtractionRecord");

    // ── Structural object properties ──────────────────────────────────────────

    /** Links a {@code DataRepository} to its {@code DatabaseSchema}. */
    public static final IRI hasSchema       = iri("hasSchema");

    /** Links an {@code ExtractionRecord} or {@code DatabaseSchema} to a {@code DatabaseTable}. */
    public static final IRI hasTable        = iri("hasTable");

    /** Links a {@code DatabaseTable} to a {@code TableColumn}. */
    public static final IRI hasColumn       = iri("hasColumn");

    /**
     * {@code dr:forSchema} — links an {@code ExtractionRecord} to the stable
     * {@code dr:DatabaseSchema} IRI it captured a snapshot of.
     *
     * <p>The {@code DatabaseSchema} IRI is stable across extractions
     * (does not include the timestamp), enabling "give me all extractions
     * for schema X" queries.
     */
    public static final IRI forSchema       = iri("forSchema");

    /**
     * {@code dr:forRepository} — links an {@code ExtractionRecord} to the
     * stable {@code dr:DataRepository} IRI.
     */
    public static final IRI forRepository   = iri("forRepository");

    // ── ExtractionRecord + DataRepository connection properties ──────────────

    /** Database engine type: SNOWFLAKE, POSTGRESQL, … */
    public static final IRI databaseType    = iri("databaseType");

    /** Hostname or IP of the database server. */
    public static final IRI host            = iri("host");

    /** TCP port number. */
    public static final IRI port            = iri("port");

    /** Database / catalog name. */
    public static final IRI databaseName    = iri("databaseName");

    /** Full JDBC connection URL if provided directly. */
    public static final IRI jdbcUrl         = iri("jdbcUrl");

    /** Snowflake virtual warehouse name. */
    public static final IRI warehouse       = iri("warehouse");

    /** Snowflake role. */
    public static final IRI role            = iri("role");

    /** Authentication mode: PASSWORD or KEY_PAIR. */
    public static final IRI authMode        = iri("authMode");

    /** Precise ISO-8601 timestamp of the extraction event. */
    public static final IRI extractedAt     = iri("extractedAt");

    /** Total number of tables captured in this extraction. */
    public static final IRI tableCount      = iri("tableCount");

    /** Total number of columns captured across all tables. */
    public static final IRI columnCount     = iri("columnCount");

    // ── DatabaseSchema properties ─────────────────────────────────────────────

    /** SQL schema name, e.g. PUBLIC. */
    public static final IRI schemaName      = iri("schemaName");

    // ── DatabaseTable properties ──────────────────────────────────────────────

    /** SQL table name. */
    public static final IRI tableName       = iri("tableName");

    /** Object type: TABLE or VIEW. */
    public static final IRI tableType       = iri("tableType");

    /** Optional table comment. */
    public static final IRI tableRemarks    = iri("tableRemarks");

    // ── TableColumn properties ────────────────────────────────────────────────

    /** SQL column name. */
    public static final IRI columnName      = iri("columnName");

    /** SQL type name, e.g. VARCHAR, BIGINT. */
    public static final IRI sqlDataType     = iri("sqlDataType");

    /** 1-based ordinal position within the table. */
    public static final IRI ordinalPosition = iri("ordinalPosition");

    /** True if the column allows NULL. */
    public static final IRI isNullable      = iri("isNullable");

    /** True if the column is part of the primary key. */
    public static final IRI isPrimaryKey    = iri("isPrimaryKey");

    /** True if the column is a foreign key. */
    public static final IRI isForeignKey    = iri("isForeignKey");

    /** Maximum character length for string columns. */
    public static final IRI maxLength       = iri("maxLength");

    /** Total significant digits for numeric columns. */
    public static final IRI numericPrecision = iri("numericPrecision");

    /** Digits right of the decimal point for numeric columns. */
    public static final IRI numericScale    = iri("numericScale");

    /** Optional column comment. */
    public static final IRI columnRemarks   = iri("columnRemarks");

    /** Column default value expression. */
    public static final IRI defaultValue    = iri("defaultValue");

    // ── Catalogue / versioning properties ────────────────────────────────────

    /**
     * {@code dr:namedGraph} — stored in the catalogue graph, pointing from an
     * {@code ExtractionRecord} to the named graph IRI that holds its full snapshot.
     */
    public static final IRI namedGraph      = iri("namedGraph");
    public static final IRI hasShapes       = iri("hasShapes");

    // ── Legacy versioning terms (kept for backward compat) ───────────────────

    public static final IRI SchemaVersion   = iri("SchemaVersion");
    public static final IRI SchemaChange    = iri("SchemaChange");
    public static final IRI hasVersion      = iri("hasVersion");
    public static final IRI previousVersion = iri("previousVersion");
    public static final IRI snapshotOf      = iri("snapshotOf");
    public static final IRI hasChange       = iri("hasChange");
    public static final IRI affectsTable    = iri("affectsTable");
    public static final IRI affectsColumn   = iri("affectsColumn");
    public static final IRI versionNumber   = iri("versionNumber");
    public static final IRI versionLabel    = iri("versionLabel");
    public static final IRI isCurrent       = iri("isCurrent");
    public static final IRI changeType      = iri("changeType");
    public static final IRI changeDescription = iri("changeDescription");
    public static final IRI previousValue   = iri("previousValue");
    public static final IRI newValue        = iri("newValue");

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static IRI iri(String localName) {
        return VF.createIRI(NS + localName);
    }

    public static String iriString(String localName) {
        return NS + localName;
    }
}
