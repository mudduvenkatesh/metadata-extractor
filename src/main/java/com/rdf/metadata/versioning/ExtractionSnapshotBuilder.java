package com.rdf.metadata.versioning;

import com.rdf.metadata.model.*;
import com.rdf.metadata.rdf.DataRepositoryVocabulary;
import com.rdf.metadata.rdf.DataRepositoryVocabularyBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.model.*;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.*;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;

import static com.rdf.metadata.rdf.DataRepositoryVocabulary.*;

/**
 * Builds the complete RDF {@link Model} for a single extraction snapshot.
 *
 * <h3>Named graph structure produced</h3>
 *
 * <p>All triples are emitted for a single named graph whose IRI is also the
 * subject IRI of the root {@code dr:ExtractionRecord} individual. The caller
 * is responsible for writing the model into that named graph in GraphDB.
 *
 * <pre>{@code
 * # Root: ExtractionRecord (graph IRI = record IRI)
 * <extraction/MY_DB/PUBLIC/2026-06-23_10-00-00-000>
 *     a                  dr:ExtractionRecord ;
 *     dr:forRepository   <repo/MY_DB> ;
 *     dr:forSchema       <repo/MY_DB/PUBLIC> ;
 *     dr:databaseType    "SNOWFLAKE" ;
 *     dr:databaseName    "MY_DB" ;
 *     dr:warehouse       "COMPUTE_WH" ;
 *     dr:authMode        "KEY_PAIR" ;
 *     dr:extractedAt     "2026-06-23T10:00:00Z"^^xsd:dateTime ;
 *     dr:tableCount      3 ;
 *     dr:columnCount     12 ;
 *     dr:hasTable        <extraction/.../table/Orders> ,
 *                        <extraction/.../table/Customers> .
 *
 * # Stable repository node
 * <repo/MY_DB>
 *     a                  dr:DataRepository ;
 *     dr:databaseType    "SNOWFLAKE" ;
 *     dr:databaseName    "MY_DB" .
 *
 * # Stable schema node
 * <repo/MY_DB/PUBLIC>
 *     a                  dr:DatabaseSchema ;
 *     dr:schemaName      "PUBLIC" .
 *
 * # Per-extraction table nodes
 * <extraction/.../table/Orders>
 *     a                  dr:DatabaseTable ;
 *     dr:tableName       "ORDERS" ;
 *     dr:tableType       "TABLE" ;
 *     dr:hasColumn       <extraction/.../column/Orders_OrderId> .
 *
 * # Per-extraction column nodes
 * <extraction/.../column/Orders_OrderId>
 *     a                  dr:TableColumn ;
 *     dr:columnName      "ORDER_ID" ;
 *     dr:sqlDataType     "BIGINT" ;
 *     dr:ordinalPosition 1 ;
 *     dr:isNullable      false ;
 *     dr:isPrimaryKey    true .
 * }</pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExtractionSnapshotBuilder {

    private static final ValueFactory      VF     = SimpleValueFactory.getInstance();
    private static final DateTimeFormatter ISO_DT = DateTimeFormatter.ISO_INSTANT;

    private final DataRepositoryVocabularyBuilder vocabularyBuilder;

    /**
     * Build the full snapshot model for one extraction.
     *
     * @param schema  the extracted schema (must have {@code extractedAt} set)
     * @param base    the base namespace from {@code RdfProperties}
     * @return a {@link Model} ready to be written to the extraction named graph
     */
    public Model build(SchemaMetadata schema, String base) {
        Model model = new LinkedHashModel();

        String extractionBase = ExtractionIriFactory.extractionIriString(base, schema);
        IRI    recordIri      = VF.createIRI(extractionBase);
        IRI    repoIri        = ExtractionIriFactory.repositoryIri(base, schema);
        IRI    schemaIri      = ExtractionIriFactory.schemaIri(base, schema);

        int tCount = schema.getTables().size();
        int cCount = schema.getTables().stream().mapToInt(t -> t.getColumns().size()).sum();

        // ── ExtractionRecord (root individual) ────────────────────────────────
        add(model, recordIri, RDF.TYPE,        ExtractionRecord);
        add(model, recordIri, RDFS.LABEL,
                lit("Extraction: " + schema.getDatabaseName()
                    + "/" + schema.getSchemaName()
                    + " @ " + ISO_DT.format(schema.getExtractedAt())));

        // Links to stable nodes
        add(model, recordIri, forRepository, repoIri);
        add(model, recordIri, forSchema,     schemaIri);

        // Connection properties stored as RDF literals
        addIfSet(model, recordIri, databaseType, schema.getDatabaseType());
        addIfSet(model, recordIri, databaseName, schema.getDatabaseName());
        addIfSet(model, recordIri, host,         schema.getHost());
        addIfSet(model, recordIri, jdbcUrl,      schema.getJdbcUrl());
        addIfSet(model, recordIri, warehouse,    schema.getWarehouse());
        addIfSet(model, recordIri, role,         schema.getRole());
        addIfSet(model, recordIri, authMode,     schema.getAuthMode());

        if (schema.getPort() != null)
            add(model, recordIri, port, VF.createLiteral(schema.getPort()));

        add(model, recordIri, extractedAt,
                VF.createLiteral(ISO_DT.format(schema.getExtractedAt()), XSD.DATETIME));
        add(model, recordIri, tableCount,  VF.createLiteral(tCount));
        add(model, recordIri, columnCount, VF.createLiteral(cCount));

        // ── Stable DataRepository node ────────────────────────────────────────
        add(model, repoIri, RDF.TYPE,    DataRepositoryVocabulary.DataRepository);
        add(model, repoIri, RDFS.LABEL,  lit(schema.getDatabaseName()));
        addIfSet(model, repoIri, databaseType, schema.getDatabaseType());
        addIfSet(model, repoIri, databaseName, schema.getDatabaseName());
        addIfSet(model, repoIri, warehouse,    schema.getWarehouse());

        // ── Stable DatabaseSchema node ────────────────────────────────────────
        add(model, schemaIri, RDF.TYPE,   DataRepositoryVocabulary.DatabaseSchema);
        add(model, schemaIri, RDFS.LABEL, lit(schema.getSchemaName()));
        add(model, schemaIri, schemaName, lit(schema.getSchemaName()));
        add(model, repoIri, hasSchema, schemaIri);

        // ── Per-extraction tables + columns ───────────────────────────────────
        for (TableMetadata table : schema.getTables()) {
            IRI tableIri = ExtractionIriFactory.tableIri(extractionBase, table.getTableName());
            buildTableNode(model, table, tableIri, recordIri, extractionBase);
        }

        log.debug("Snapshot model built: {} triples for extraction {}",
                model.size(), extractionBase);
        return model;
    }

    // ─────────────────────────────────────────────────────────────────────────

    private void buildTableNode(Model model, TableMetadata table,
                                 IRI tableIri, IRI recordIri, String extractionBase) {
        add(model, tableIri, RDF.TYPE,   DataRepositoryVocabulary.DatabaseTable);
        add(model, tableIri, RDFS.LABEL, lit(table.getTableName()));
        add(model, tableIri, tableName,  lit(table.getTableName()));
        addIfSet(model, tableIri, tableType,    table.getTableType());
        addIfSet(model, tableIri, tableRemarks, table.getRemarks());

        // Link record → table
        add(model, recordIri, hasTable, tableIri);

        for (ColumnMetadata col : table.getColumns()) {
            IRI colIri = ExtractionIriFactory.columnIri(
                    extractionBase, table.getTableName(), col.getColumnName());
            buildColumnNode(model, col, table, colIri, tableIri);
        }
    }

    private void buildColumnNode(Model model, ColumnMetadata col,
                                  TableMetadata table, IRI colIri, IRI tableIri) {
        boolean fkCol = table.getForeignKeys().stream()
                .anyMatch(fk -> fk.getFkColumnName().equalsIgnoreCase(col.getColumnName()));

        add(model, colIri, RDF.TYPE,         DataRepositoryVocabulary.TableColumn);
        add(model, colIri, RDFS.LABEL,       lit(col.getColumnName()));
        add(model, colIri, columnName,       lit(col.getColumnName()));
        addIfSet(model, colIri, sqlDataType, col.getDataType());
        add(model, colIri, ordinalPosition,  VF.createLiteral(col.getOrdinalPosition()));
        add(model, colIri, isNullable,       VF.createLiteral(col.isNullable()));
        add(model, colIri, isPrimaryKey,     VF.createLiteral(col.isPrimaryKey()));
        add(model, colIri, isForeignKey,     VF.createLiteral(fkCol));

        if (col.getCharacterMaxLength() != null)
            add(model, colIri, maxLength,
                    VF.createLiteral(col.getCharacterMaxLength()));
        if (col.getNumericPrecision() != null)
            add(model, colIri, numericPrecision,
                    VF.createLiteral(col.getNumericPrecision()));
        if (col.getNumericScale() != null)
            add(model, colIri, numericScale,
                    VF.createLiteral(col.getNumericScale()));

        addIfSet(model, colIri, columnRemarks, col.getRemarks());
        addIfSet(model, colIri, defaultValue,  col.getDefaultValue());

        // Link table → column
        add(model, tableIri, hasColumn, colIri);
    }

    // ─────────────────────────────────────────────────────────────────────────

    private static void add(Model m, Resource s, IRI p, Value o) { m.add(s, p, o); }
    private static Literal lit(String v)    { return VF.createLiteral(v); }
    private void addIfSet(Model m, IRI s, IRI p, String v) {
        if (v != null && !v.isBlank()) add(m, s, p, lit(v));
    }
}
