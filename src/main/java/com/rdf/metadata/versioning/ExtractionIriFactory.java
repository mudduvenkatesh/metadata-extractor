package com.rdf.metadata.versioning;

import com.rdf.metadata.model.SchemaMetadata;
import com.rdf.metadata.util.IriUtils;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Generates unique, deterministic IRIs for each schema extraction snapshot.
 *
 * <h3>IRI Patterns</h3>
 *
 * <p><b>Extraction named graph + ExtractionRecord individual:</b>
 * <pre>{@code
 * <base>extraction/<db>/<schema>/<yyyy-MM-dd_HH-mm-ss-SSS>
 *
 * e.g. http://example.org/schema#extraction/MY_DB/PUBLIC/2026-06-23_10-00-00-123
 * }</pre>
 * This IRI is both the named graph that holds all snapshot triples AND the subject
 * IRI of the {@code dr:ExtractionRecord} individual. Using the same IRI for both
 * means the graph is self-describing — every triple in the graph is about the record
 * whose IRI is the graph name.
 *
 * <p><b>Stable DataRepository (no timestamp — same across all extractions):</b>
 * <pre>{@code
 * <base>repo/<db>
 * e.g. http://example.org/schema#repo/MY_DB
 * }</pre>
 *
 * <p><b>Stable DatabaseSchema (no timestamp):</b>
 * <pre>{@code
 * <base>repo/<db>/<schema>
 * e.g. http://example.org/schema#repo/MY_DB/PUBLIC
 * }</pre>
 *
 * <p><b>Per-extraction DatabaseTable (scoped to the extraction):</b>
 * <pre>{@code
 * <base>extraction/<db>/<schema>/<timestamp>/table/<TableName>
 * }</pre>
 *
 * <p><b>Per-extraction TableColumn:</b>
 * <pre>{@code
 * <base>extraction/<db>/<schema>/<timestamp>/column/<TableName>_<ColName>
 * }</pre>
 *
 * <p><b>Catalogue named graph (stable — one per application):</b>
 * <pre>{@code
 * <base>catalogue
 * }</pre>
 */
public final class ExtractionIriFactory {

    /** Timestamp format used in IRIs — URL-safe, sortable, millisecond precision. */
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS")
                             .withZone(ZoneOffset.UTC);

    private static final SimpleValueFactory VF = SimpleValueFactory.getInstance();

    private ExtractionIriFactory() {}

    // ─────────────────────────────────────────────────────────────────────────
    // Extraction-scoped IRIs (unique per snapshot)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The named graph IRI for this extraction snapshot.
     * Also used as the subject IRI of the {@code dr:ExtractionRecord} individual.
     */
    public static IRI extractionIri(String base, SchemaMetadata schema) {
        return VF.createIRI(extractionIriString(base, schema));
    }

    public static String extractionIriString(String base, SchemaMetadata schema) {
        return base + "extraction/"
                + IriUtils.sanitise(schema.getDatabaseName()) + "/"
                + IriUtils.sanitise(schema.getSchemaName()) + "/"
                + TS_FMT.format(schema.getExtractedAt());
    }

    /** Per-extraction {@code dr:DatabaseTable} IRI. */
    public static IRI tableIri(String extractionBase, String tableName) {
        return VF.createIRI(extractionBase + "/table/" + IriUtils.toPascalCase(tableName));
    }

    /** Per-extraction {@code dr:TableColumn} IRI. */
    public static IRI columnIri(String extractionBase, String tableName, String columnName) {
        return VF.createIRI(extractionBase + "/column/"
                + IriUtils.toPascalCase(tableName) + "_"
                + IriUtils.toPascalCase(columnName));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stable IRIs (same across all extractions for the same repo/schema)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Stable {@code dr:DataRepository} IRI — does not encode the timestamp.
     * The same database always maps to the same IRI regardless of when it is extracted.
     */
    public static IRI repositoryIri(String base, SchemaMetadata schema) {
        return VF.createIRI(base + "repo/" + IriUtils.sanitise(schema.getDatabaseName()));
    }

    /**
     * Stable {@code dr:DatabaseSchema} IRI — does not encode the timestamp.
     * Enables cross-extraction queries like "all snapshots of schema PUBLIC".
     */
    public static IRI schemaIri(String base, SchemaMetadata schema) {
        return VF.createIRI(base + "repo/"
                + IriUtils.sanitise(schema.getDatabaseName()) + "/"
                + IriUtils.sanitise(schema.getSchemaName()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Application-wide named graphs
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The catalogue named graph — holds one {@code dr:ExtractionRecord} summary
     * per extraction for fast listing and "latest" queries.
     */
    public static IRI catalogueGraphIri(String base) {
        return VF.createIRI(base + "catalogue");
    }

    /**
     * The vocabulary named graph — holds all {@code dr:} class and property declarations.
     */
    public static IRI vocabularyGraphIri(String base) {
        return VF.createIRI(base + "vocabulary/datarepository");
    }
}
