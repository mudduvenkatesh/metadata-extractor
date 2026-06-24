package com.rdf.metadata.rdf;

import com.rdf.metadata.config.RdfProperties;
import com.rdf.metadata.model.*;
import com.rdf.metadata.util.IriUtils;
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
 * Builds the <b>Repository Instance Ontology</b> — an RDF graph that describes
 * one specific database connection and its structural metadata as individuals.
 *
 * <h3>What this graph contains</h3>
 * <ul>
 *   <li>One {@code dr:DataRepository} individual per connection (connection properties
 *       stored as RDF literal statements)</li>
 *   <li>One {@code dr:DatabaseSchema} individual per schema</li>
 *   <li>One {@code dr:DatabaseTable} individual per table/view</li>
 *   <li>One {@code dr:TableColumn} individual per column</li>
 * </ul>
 *
 * <h3>What this graph does NOT contain</h3>
 * <ul>
 *   <li>Vocabulary class/property declarations (those live in the vocab ontology)</li>
 *   <li>OWL domain axioms (those live in the schema ontology)</li>
 *   <li>Cross-graph links to the schema ontology (those live in the linking ontology)</li>
 * </ul>
 *
 * <h3>Ontology IRI pattern</h3>
 * <pre>{@code <base>repo/<databaseName>/<schemaName> }</pre>
 * e.g. {@code <http://example.org/schema#repo/MY_DATABASE/PUBLIC>}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RepoInstanceBuilder {

    private static final ValueFactory      VF     = SimpleValueFactory.getInstance();
    private static final DateTimeFormatter ISO_DT = DateTimeFormatter.ISO_INSTANT;

    private final RdfProperties rdfProperties;

    /**
     * Build the repository instance model and return the ontology IRI.
     *
     * @param schema    the extracted schema metadata carrying connection properties
     * @return a pair of the ontology IRI and the populated model
     */
    public OntologyGraph build(SchemaMetadata schema) {
        Model model = new LinkedHashModel();
        String base = rdfProperties.getBaseNamespace();

        // ── Ontology header ───────────────────────────────────────────────────
        IRI ontIri = repoInstanceOntIri(schema, base);
        add(model, ontIri, RDF.TYPE,        OWL.ONTOLOGY);
        add(model, ontIri, RDFS.LABEL,      lit("Repository Instance: "
                + schema.getDatabaseName() + " / " + schema.getSchemaName()));
        add(model, ontIri, RDFS.COMMENT,    lit("Instance data for " + schema.getDatabaseType()
                + " database '" + schema.getDatabaseName()
                + "', schema '" + schema.getSchemaName() + "'"));
        add(model, ontIri, OWL.VERSIONINFO, lit("1.0"));

        // owl:imports the vocabulary ontology
        add(model, ontIri, OWL.IMPORTS, repoVocabOntIri(base));

        setNamespaces(model, base);

        // ── DataRepository individual ─────────────────────────────────────────
        IRI repoIri = buildRepoIndividual(model, schema, base);

        // ── DatabaseSchema individual ─────────────────────────────────────────
        IRI schemaIri = buildSchemaIndividual(model, schema, repoIri, base);

        // ── DatabaseTable + TableColumn individuals ───────────────────────────
        for (TableMetadata table : schema.getTables()) {
            buildTableIndividual(model, table, schemaIri, base);
        }

        log.info("Repo instance ontology built: {} triples, {} tables",
                model.size(), schema.getTables().size());
        return new OntologyGraph(ontIri, model);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DataRepository individual
    // ─────────────────────────────────────────────────────────────────────────

    private IRI buildRepoIndividual(Model model, SchemaMetadata schema, String base) {
        IRI repoIri = iri(base + "repo/"
                        + IriUtils.sanitise(schema.getDatabaseName())
                        + "_" + IriUtils.sanitise(schema.getSchemaName()));

        add(model, repoIri, RDF.TYPE,   DataRepository);
        add(model, repoIri, RDFS.LABEL, lit(schema.getDatabaseName()
                + " / " + schema.getSchemaName()));

        addIfSet(model, repoIri, databaseType, schema.getDatabaseType());
        addIfSet(model, repoIri, databaseName, schema.getDatabaseName());
        addIfSet(model, repoIri, host,         schema.getHost());
        addIfSet(model, repoIri, jdbcUrl,      schema.getJdbcUrl());
        addIfSet(model, repoIri, warehouse,    schema.getWarehouse());
        addIfSet(model, repoIri, role,         schema.getRole());
        addIfSet(model, repoIri, authMode,     schema.getAuthMode());

        if (schema.getPort() != null) {
            add(model, repoIri, port, VF.createLiteral(schema.getPort()));
        }
        if (schema.getExtractedAt() != null) {
            add(model, repoIri, extractedAt,
                    VF.createLiteral(ISO_DT.format(schema.getExtractedAt()), XSD.DATETIME));
        }
        return repoIri;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DatabaseSchema individual
    // ─────────────────────────────────────────────────────────────────────────

    private IRI buildSchemaIndividual(Model model, SchemaMetadata schema,
                                       IRI repoIri, String base) {
        IRI schemaIri = iri(base + "repo/"
                          + IriUtils.sanitise(schema.getDatabaseName())
                          + "_" + IriUtils.sanitise(schema.getSchemaName()) + "_Schema");

        add(model, schemaIri, RDF.TYPE,   DatabaseSchema);
        add(model, schemaIri, RDFS.LABEL, lit(schema.getSchemaName()));
        add(model, schemaIri, schemaName, lit(schema.getSchemaName()));

        add(model, repoIri, hasSchema, schemaIri);
        return schemaIri;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DatabaseTable + TableColumn individuals
    // ─────────────────────────────────────────────────────────────────────────

    private void buildTableIndividual(Model model, TableMetadata table,
                                       IRI schemaIri, String base) {
        IRI tableIri = tableIndividualIri(base, table.getTableName());

        add(model, tableIri, RDF.TYPE,   DatabaseTable);
        add(model, tableIri, RDFS.LABEL, lit(table.getTableName()));
        add(model, tableIri, tableName,  lit(table.getTableName()));
        addIfSet(model, tableIri, tableType,    table.getTableType());
        addIfSet(model, tableIri, tableRemarks, table.getRemarks());
        add(model, schemaIri, hasTable, tableIri);

        for (ColumnMetadata col : table.getColumns()) {
            buildColumnIndividual(model, col, table, tableIri, base);
        }
    }

    private void buildColumnIndividual(Model model, ColumnMetadata col,
                                        TableMetadata table, IRI tableIri, String base) {
        IRI colIri = columnIndividualIri(base, table.getTableName(), col.getColumnName());
        boolean fkCol = table.getForeignKeys().stream()
                .anyMatch(fk -> fk.getFkColumnName().equalsIgnoreCase(col.getColumnName()));

        add(model, colIri, RDF.TYPE,   TableColumn);
        add(model, colIri, RDFS.LABEL, lit(col.getColumnName()));
        add(model, colIri, columnName, lit(col.getColumnName()));
        addIfSet(model, colIri, sqlDataType, col.getDataType());
        add(model, colIri, ordinalPosition, VF.createLiteral(col.getOrdinalPosition()));
        add(model, colIri, isNullable,      VF.createLiteral(col.isNullable()));
        add(model, colIri, isPrimaryKey,    VF.createLiteral(col.isPrimaryKey()));
        add(model, colIri, isForeignKey,    VF.createLiteral(fkCol));

        if (col.getCharacterMaxLength() != null)
            add(model, colIri, maxLength,        VF.createLiteral(col.getCharacterMaxLength()));
        if (col.getNumericPrecision() != null)
            add(model, colIri, numericPrecision, VF.createLiteral(col.getNumericPrecision()));
        if (col.getNumericScale() != null)
            add(model, colIri, numericScale,     VF.createLiteral(col.getNumericScale()));

        addIfSet(model, colIri, columnRemarks, col.getRemarks());
        addIfSet(model, colIri, defaultValue,  col.getDefaultValue());

        add(model, tableIri, hasColumn, colIri);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // IRI helpers (package-visible so the linker can reference them)
    // ─────────────────────────────────────────────────────────────────────────

    static IRI repoInstanceOntIri(SchemaMetadata schema, String base) {
        return SimpleValueFactory.getInstance().createIRI(
                base + "repo/" + IriUtils.sanitise(schema.getDatabaseName())
                + "/" + IriUtils.sanitise(schema.getSchemaName()));
    }

    static IRI repoVocabOntIri(String base) {
        return SimpleValueFactory.getInstance().createIRI(
                base + "vocabulary/datarepository");
    }

    public static IRI tableIndividualIri(String base, String tableName) {
        return SimpleValueFactory.getInstance().createIRI(
                base + "repo/table/" + IriUtils.toPascalCase(tableName));
    }

    static IRI columnIndividualIri(String base, String tableName, String columnName) {
        return SimpleValueFactory.getInstance().createIRI(
                base + "repo/column/"
                + IriUtils.toPascalCase(tableName)
                + "_" + IriUtils.toPascalCase(columnName));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────────────────────────────────

    private void setNamespaces(Model model, String base) {
        model.setNamespace("dr",     DataRepositoryVocabulary.NS);
        model.setNamespace("repo",   base + "repo/");
        model.setNamespace("owl",    OWL.NAMESPACE);
        model.setNamespace("rdfs",   RDFS.NAMESPACE);
        model.setNamespace("xsd",    XSD.NAMESPACE);
    }

    private static IRI iri(String uri)          { return VF.createIRI(uri); }
    private static Literal lit(String v)        { return VF.createLiteral(v); }
    private static void add(Model m, Resource s, IRI p, Value o) { m.add(s, p, o); }

    private void addIfSet(Model model, IRI subject, IRI predicate, String value) {
        if (value != null && !value.isBlank()) add(model, subject, predicate, lit(value));
    }
}
