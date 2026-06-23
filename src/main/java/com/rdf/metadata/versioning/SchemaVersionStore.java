package com.rdf.metadata.versioning;

import com.rdf.metadata.config.RdfProperties;
import com.rdf.metadata.model.SchemaMetadata;
import com.rdf.metadata.rdf.DataRepositoryVocabulary;
import com.rdf.metadata.util.IriUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.model.*;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static com.rdf.metadata.rdf.DataRepositoryVocabulary.*;

/**
 * Persists schema version snapshots as named graphs in a GraphDB triplestore
 * via RDF4J's {@link Repository} (injected as the GraphDB {@code HTTPRepository} bean).
 *
 * <h3>Named graph layout per (database, schema) pair</h3>
 * <pre>
 * &lt;base&gt;version/&lt;db&gt;/&lt;schema&gt;/registry     — SchemaVersion catalogue
 * &lt;base&gt;version/&lt;db&gt;/&lt;schema&gt;/v1           — tables + columns snapshot
 * &lt;base&gt;version/&lt;db&gt;/&lt;schema&gt;/v1/changes   — SchemaChange individuals
 * &lt;base&gt;version/&lt;db&gt;/&lt;schema&gt;/v2
 * &lt;base&gt;version/&lt;db&gt;/&lt;schema&gt;/v2/changes
 * ...
 * </pre>
 *
 * <h3>Version numbering</h3>
 * The next version number is derived by querying GraphDB for
 * {@code MAX(?versionNumber)} in the registry graph — so the application
 * is fully stateless and safe to restart or scale horizontally.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchemaVersionStore {

    private static final ValueFactory      VF     = SimpleValueFactory.getInstance();
    private static final DateTimeFormatter ISO_DT = DateTimeFormatter.ISO_INSTANT;

    private final Repository    repository;   // injected GraphDB HTTPRepository
    private final RdfProperties rdfProperties;

    // ─────────────────────────────────────────────────────────────────────────
    // Save
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Persist a new schema snapshot to GraphDB.
     *
     * <ol>
     *   <li>Query GraphDB for the current max version number for this (db, schema)</li>
     *   <li>Increment it to get the new version number</li>
     *   <li>Write the registry update in a single transaction</li>
     *   <li>Write the snapshot named graph</li>
     *   <li>Write the changes named graph (if any changes)</li>
     * </ol>
     */
    public StoredSchemaVersion save(SchemaMetadata schema, List<DetectedChange> changes) {
        String base       = rdfProperties.getBaseNamespace();
        int    versionNum = nextVersionNumber(schema, base);

        IRI snapshotGraph = snapshotGraphIri(base, schema, versionNum);
        IRI changesGraph  = changesGraphIri(base, schema, versionNum);
        IRI registryGraph = registryGraphIri(base, schema);
        IRI versionNode   = versionNodeIri(base, schema, versionNum);
        IRI schemaNode    = schemaNodeIri(base, schema);
        IRI repoNode      = repoNodeIri(base, schema);
        IRI prevNode      = versionNum > 1 ? versionNodeIri(base, schema, versionNum - 1) : null;

        int tCount = schema.getTables().size();
        int cCount = schema.getTables().stream().mapToInt(t -> t.getColumns().size()).sum();

        try (RepositoryConnection conn = repository.getConnection()) {
            conn.begin();

            // Mark previous version as no longer current
            if (prevNode != null) {
                conn.remove(prevNode, isCurrent, null, registryGraph);
                conn.add(prevNode, isCurrent, VF.createLiteral(false), registryGraph);
            }

            // Write repo + schema nodes (idempotent)
            writeRepoAndSchemaNodes(conn, repoNode, schemaNode, schema, registryGraph);

            // Write new SchemaVersion node
            writeVersionNode(conn, versionNode, schemaNode, schema,
                    versionNum, prevNode, snapshotGraph, tCount, cCount, registryGraph);

            // Link schema → version
            conn.add(schemaNode, hasVersion, versionNode, registryGraph);

            // Write full snapshot named graph
            writeSnapshotGraph(conn, schema, snapshotGraph, base);

            // Write changes named graph
            if (!changes.isEmpty()) {
                writeChangesGraph(conn, changes, versionNode, schema, changesGraph, base);
            }

            conn.commit();
        }

        log.info("Saved schema v{} for {}/{} to GraphDB: {} tables, {} cols, {} changes",
                versionNum, schema.getDatabaseName(), schema.getSchemaName(),
                tCount, cCount, changes.size());

        return StoredSchemaVersion.builder()
                .databaseName(schema.getDatabaseName())
                .schemaName(schema.getSchemaName())
                .versionNumber(versionNum)
                .versionLabel("v" + versionNum)
                .extractedAt(schema.getExtractedAt())
                .tableCount(tCount)
                .columnCount(cCount)
                .changeCount(changes.size())
                .isCurrent(true)
                .snapshotGraphIri(snapshotGraph.stringValue())
                .changesGraphIri(changes.isEmpty() ? null : changesGraph.stringValue())
                .registryGraphIri(registryGraph.stringValue())
                .versionNodeIri(versionNode.stringValue())
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Query: next version number from GraphDB
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Query GraphDB for {@code MAX(?versionNumber)} in the registry graph.
     * Returns 1 if no versions exist yet.
     * This makes the store stateless — safe to restart or run multiple instances.
     */
    int nextVersionNumber(SchemaMetadata schema, String base) {
        IRI registryGraph = registryGraphIri(base, schema);

        String sparql = """
                PREFIX dr: <%s>
                SELECT (MAX(?v) AS ?maxV) WHERE {
                  GRAPH <%s> {
                    ?versionNode a dr:SchemaVersion ;
                                 dr:versionNumber ?v .
                  }
                }
                """.formatted(DataRepositoryVocabulary.NS, registryGraph.stringValue());

        try (RepositoryConnection conn = repository.getConnection()) {
            TupleQuery query = conn.prepareTupleQuery(sparql);
            try (TupleQueryResult result = query.evaluate()) {
                if (result.hasNext()) {
                    var binding = result.next().getBinding("maxV");
                    if (binding != null && binding.getValue() != null) {
                        return Integer.parseInt(binding.getValue().stringValue()) + 1;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not query max version number ({}), starting at 1", e.getMessage());
        }
        return 1;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Retrieve graphs from GraphDB
    // ─────────────────────────────────────────────────────────────────────────

    /** Retrieve all triples in a specific snapshot named graph. */
    public Model getSnapshotGraph(String db, String schema, int versionNum) {
        String base    = rdfProperties.getBaseNamespace();
        IRI graphIri   = snapshotGraphIri(base,
                SchemaMetadata.builder().databaseName(db).schemaName(schema).build(), versionNum);
        return loadNamedGraph(graphIri);
    }

    /** Retrieve all triples in a specific changes named graph. */
    public Model getChangesGraph(String db, String schema, int versionNum) {
        String base    = rdfProperties.getBaseNamespace();
        IRI graphIri   = changesGraphIri(base,
                SchemaMetadata.builder().databaseName(db).schemaName(schema).build(), versionNum);
        return loadNamedGraph(graphIri);
    }

    /** Retrieve all triples in the registry named graph for a (db, schema). */
    public Model getRegistryGraph(String db, String schema) {
        String base  = rdfProperties.getBaseNamespace();
        IRI graphIri = registryGraphIri(base, db, schema);
        return loadNamedGraph(graphIri);
    }

    private Model loadNamedGraph(IRI graphIri) {
        Model result = new LinkedHashModel();
        try (RepositoryConnection conn = repository.getConnection()) {
            conn.getStatements(null, null, null, graphIri).forEach(result::add);
        }
        log.debug("Loaded {} triples from graph <{}>", result.size(), graphIri);
        return result;
    }

    /** Expose the underlying Repository for direct SPARQL access in the registry. */
    public Repository getRepository() { return repository; }

    // ─────────────────────────────────────────────────────────────────────────
    // Write helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void writeRepoAndSchemaNodes(RepositoryConnection conn,
                                          IRI repoNode, IRI schemaNode,
                                          SchemaMetadata schema, IRI registryGraph) {
        g(conn, registryGraph, repoNode, RDF.TYPE,    DataRepository);
        g(conn, registryGraph, repoNode, databaseType, lit(schema.getDatabaseType()));
        g(conn, registryGraph, repoNode, databaseName, lit(schema.getDatabaseName()));
        if (isSet(schema.getWarehouse()))
            g(conn, registryGraph, repoNode, warehouse, lit(schema.getWarehouse()));
        if (isSet(schema.getJdbcUrl()))
            g(conn, registryGraph, repoNode, jdbcUrl, lit(schema.getJdbcUrl()));
        if (isSet(schema.getAuthMode()))
            g(conn, registryGraph, repoNode, authMode, lit(schema.getAuthMode()));

        g(conn, registryGraph, schemaNode, RDF.TYPE,    DatabaseSchema);
        g(conn, registryGraph, schemaNode, schemaName,  lit(schema.getSchemaName()));
        g(conn, registryGraph, repoNode,   hasSchema,   schemaNode);
    }

    private void writeVersionNode(RepositoryConnection conn,
                                   IRI versionNode, IRI schemaNode,
                                   SchemaMetadata schema, int vNum,
                                   IRI prevNode, IRI snapshotGraph,
                                   int tCount, int cCount, IRI registryGraph) {
        g(conn, registryGraph, versionNode, RDF.TYPE,        SchemaVersion);
        g(conn, registryGraph, versionNode, versionNumber,   VF.createLiteral(vNum));
        g(conn, registryGraph, versionNode, versionLabel,    lit("v" + vNum));
        g(conn, registryGraph, versionNode, snapshotOf,      schemaNode);
        g(conn, registryGraph, versionNode, isCurrent,       VF.createLiteral(true));
        g(conn, registryGraph, versionNode, tableCount,      VF.createLiteral(tCount));
        g(conn, registryGraph, versionNode, columnCount,     VF.createLiteral(cCount));
        g(conn, registryGraph, versionNode, extractedAt,
                VF.createLiteral(ISO_DT.format(schema.getExtractedAt()), XSD.DATETIME));
        // Store the snapshot graph IRI so consumers can navigate to the data
        g(conn, registryGraph, versionNode,
                VF.createIRI(DataRepositoryVocabulary.NS + "snapshotGraph"), snapshotGraph);
        if (prevNode != null)
            g(conn, registryGraph, versionNode, previousVersion, prevNode);
    }

    private void writeSnapshotGraph(RepositoryConnection conn,
                                     SchemaMetadata schema,
                                     IRI graphIri, String base) {
        for (var table : schema.getTables()) {
            IRI tableIri = tableIri(base, schema, table.getTableName());
            g(conn, graphIri, tableIri, RDF.TYPE,   DatabaseTable);
            g(conn, graphIri, tableIri, tableName,  lit(table.getTableName()));
            g(conn, graphIri, tableIri, tableType,  lit(table.getTableType()));
            if (isSet(table.getRemarks()))
                g(conn, graphIri, tableIri, tableRemarks, lit(table.getRemarks()));

            for (var col : table.getColumns()) {
                boolean fkCol = table.getForeignKeys().stream()
                        .anyMatch(fk -> fk.getFkColumnName().equalsIgnoreCase(col.getColumnName()));
                IRI colIri = columnIri(base, schema, table.getTableName(), col.getColumnName());

                g(conn, graphIri, colIri, RDF.TYPE,        TableColumn);
                g(conn, graphIri, colIri, columnName,      lit(col.getColumnName()));
                g(conn, graphIri, colIri, sqlDataType,     lit(col.getDataType()));
                g(conn, graphIri, colIri, ordinalPosition, VF.createLiteral(col.getOrdinalPosition()));
                g(conn, graphIri, colIri, isNullable,      VF.createLiteral(col.isNullable()));
                g(conn, graphIri, colIri, isPrimaryKey,    VF.createLiteral(col.isPrimaryKey()));
                g(conn, graphIri, colIri, isForeignKey,    VF.createLiteral(fkCol));
                if (col.getCharacterMaxLength() != null)
                    g(conn, graphIri, colIri, maxLength, VF.createLiteral(col.getCharacterMaxLength()));

                g(conn, graphIri, tableIri, hasColumn, colIri);
            }
        }
    }

    private void writeChangesGraph(RepositoryConnection conn,
                                    List<DetectedChange> changes,
                                    IRI versionNode, SchemaMetadata schema,
                                    IRI changesGraph, String base) {
        for (int i = 0; i < changes.size(); i++) {
            DetectedChange ch = changes.get(i);
            IRI changeNode = VF.createIRI(changesGraph.stringValue() + "/change_" + i);

            g(conn, changesGraph, changeNode, RDF.TYPE,           SchemaChange);
            g(conn, changesGraph, changeNode, changeType,         lit(ch.changeType().name()));
            g(conn, changesGraph, changeNode, changeDescription,  lit(ch.description()));
            g(conn, changesGraph, versionNode, hasChange,         changeNode);

            IRI tableNode = tableIri(base, schema, ch.tableName());
            g(conn, changesGraph, changeNode, affectsTable, tableNode);

            if (ch.columnName() != null) {
                IRI colNode = columnIri(base, schema, ch.tableName(), ch.columnName());
                g(conn, changesGraph, changeNode, affectsColumn, colNode);
            }
            if (ch.previousValue() != null)
                g(conn, changesGraph, changeNode, previousValue, lit(ch.previousValue()));
            if (ch.newValue() != null)
                g(conn, changesGraph, changeNode, newValue, lit(ch.newValue()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // IRI builders  (static — used by registry and tests)
    // ─────────────────────────────────────────────────────────────────────────

    public static IRI snapshotGraphIri(String base, SchemaMetadata s, int v) {
        return VF.createIRI(base + "version/" + key(s) + "/v" + v);
    }

    public static IRI changesGraphIri(String base, SchemaMetadata s, int v) {
        return VF.createIRI(base + "version/" + key(s) + "/v" + v + "/changes");
    }

    public static IRI registryGraphIri(String base, SchemaMetadata s) {
        return VF.createIRI(base + "version/" + key(s) + "/registry");
    }

    public static IRI registryGraphIri(String base, String db, String schema) {
        return VF.createIRI(base + "version/"
                + IriUtils.sanitise(db) + "/"
                + IriUtils.sanitise(schema) + "/registry");
    }

    public static IRI versionNodeIri(String base, SchemaMetadata s, int v) {
        return VF.createIRI(base + "version/" + key(s) + "/version_" + v);
    }

    private static IRI schemaNodeIri(String base, SchemaMetadata s) {
        return VF.createIRI(base + "repo/" + IriUtils.sanitise(s.getDatabaseName())
                + "_" + IriUtils.sanitise(s.getSchemaName()) + "_Schema");
    }

    private static IRI repoNodeIri(String base, SchemaMetadata s) {
        return VF.createIRI(base + "repo/" + IriUtils.sanitise(s.getDatabaseName())
                + "_" + IriUtils.sanitise(s.getSchemaName()));
    }

    private static IRI tableIri(String base, SchemaMetadata s, String t) {
        return VF.createIRI(base + "version/" + key(s)
                + "/table/" + IriUtils.toPascalCase(t));
    }

    private static IRI columnIri(String base, SchemaMetadata s, String t, String c) {
        return VF.createIRI(base + "version/" + key(s)
                + "/column/" + IriUtils.toPascalCase(t) + "_" + IriUtils.toPascalCase(c));
    }

    private static String key(SchemaMetadata s) {
        return IriUtils.sanitise(s.getDatabaseName()) + "/"
             + IriUtils.sanitise(s.getSchemaName());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────────────────────────────────

    private static void g(RepositoryConnection c, IRI g, Resource s, IRI p, Value o) {
        c.add(s, p, o, g);
    }

    private static Literal lit(String v)   { return VF.createLiteral(v); }
    private static boolean isSet(String v) { return v != null && !v.isBlank(); }
}
