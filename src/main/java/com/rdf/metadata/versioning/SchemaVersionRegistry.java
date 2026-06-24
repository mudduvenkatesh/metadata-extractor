package com.rdf.metadata.versioning;

import com.rdf.metadata.config.RdfProperties;
import com.rdf.metadata.model.RdfFormat;
import com.rdf.metadata.model.SchemaMetadata;
import com.rdf.metadata.rdf.DataRepositoryVocabulary;
import com.rdf.metadata.rdf.RdfSerializer;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.model.*;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.*;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static com.rdf.metadata.rdf.DataRepositoryVocabulary.*;

/**
 * In-memory RDF4J registry that stores every extraction snapshot alongside
 * its SHACL shapes, and supports querying for the latest shapes.
 *
 * <h3>Internal storage</h3>
 * <p>Extraction metadata (ExtractionRecord triples) live in the <em>default graph</em>.
 * SHACL shapes for each extraction are stored in a <em>named graph</em> keyed by
 * the shapes IRI — this keeps them isolated and trivially retrievable.
 *
 * <h3>"Latest shapes" query</h3>
 * <pre>{@code
 * SELECT ?shapesIri WHERE {
 *     ?schema dr:schemaName "PUBLIC" ;
 *             dr:hasVersion  ?record .
 *     ?record dr:extractedAt ?extractedAt ;
 *             dr:hasShapes   ?shapesIri .
 * }
 * ORDER BY DESC(?extractedAt) LIMIT 1
 * }</pre>
 * The returned {@code ?shapesIri} is the named graph IRI — load it directly to get all shapes.
 */
@Slf4j
@Component
public class SchemaVersionRegistry {

    private static final ValueFactory      VF     = SimpleValueFactory.getInstance();
    

    private static final DateTimeFormatter ISO_DT = DateTimeFormatter.ISO_INSTANT;

    private final Repository    repository;
    private final RdfProperties rdfProperties;
    private final RdfSerializer rdfSerializer;

    public SchemaVersionRegistry(RdfProperties rdfProperties, RdfSerializer rdfSerializer) {
        this.rdfProperties = rdfProperties;
        this.rdfSerializer = rdfSerializer;
        this.repository    = new SailRepository(new MemoryStore());
        this.repository.init();
        log.info("SchemaVersionRegistry initialised (in-memory RDF4J)");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Register an extraction + its shapes
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Persist an extraction snapshot and its SHACL shapes model.
     *
     * <p>Extraction metadata is written to the <em>default graph</em>.
     * Shapes are written to a <em>named graph</em> keyed by the shapes IRI.
     *
     * @return the shapes graph IRI
     */
    public IRI register(SchemaMetadata schema, Model shapesModel) {
        String base          = rdfProperties.getBaseNamespace();
        IRI    extractionIri = ExtractionIriFactory.extractionIri(base, schema);
        IRI    shapesIri     = newShapesIri();   // http://example.org/harvestor/<22-char token>
        IRI    schemaIri     = ExtractionIriFactory.schemaIri(base, schema);
        IRI    repoIri       = ExtractionIriFactory.repositoryIri(base, schema);

        try (RepositoryConnection conn = repository.getConnection()) {
            conn.begin();

            // ── Default graph: stable repo/schema nodes ───────────────────────
            conn.add(schemaIri, RDF.TYPE,    DatabaseSchema);
            conn.add(schemaIri, schemaName,  lit(schema.getSchemaName()));
            conn.add(repoIri,   RDF.TYPE,    DataRepositoryVocabulary.DataRepository);
            conn.add(repoIri,   databaseName, lit(schema.getDatabaseName()));
            conn.add(repoIri,   databaseType, lit(schema.getDatabaseType()));
            conn.add(repoIri,   hasSchema,   schemaIri);

            // ── Default graph: ExtractionRecord ───────────────────────────────
            conn.add(extractionIri, RDF.TYPE,     ExtractionRecord);
            conn.add(extractionIri, RDFS.LABEL,
                    lit("Extraction: " + schema.getDatabaseName()
                        + "/" + schema.getSchemaName()
                        + " @ " + ISO_DT.format(schema.getExtractedAt())));
            conn.add(extractionIri, forSchema,    schemaIri);
            conn.add(extractionIri, forRepository, repoIri);
            conn.add(extractionIri, extractedAt,
                    VF.createLiteral(ISO_DT.format(schema.getExtractedAt()), XSD.DATETIME));
            conn.add(extractionIri, tableCount,
                    VF.createLiteral(schema.getTables().size()));
            conn.add(extractionIri, columnCount,
                    VF.createLiteral(schema.getTables().stream()
                            .mapToInt(t -> t.getColumns().size()).sum()));

            // Link record → shapes graph IRI
            conn.add(extractionIri, hasShapes, shapesIri);

            // Link schema → record (enables ORDER BY DESC(?extractedAt) LIMIT 1)
            conn.add(schemaIri, hasVersion, extractionIri);

            // ── Named graph: SHACL shapes ─────────────────────────────────────
            // Storing shapes in their own named graph keeps them isolated and
            // allows retrieval in one getStatements(null, null, null, shapesIri) call.
            for (Statement st : shapesModel) {
                conn.add(st.getSubject(), st.getPredicate(), st.getObject(), shapesIri);
            }

            conn.commit();
        }

        log.info("Registered extraction {} → {} shape triples in named graph <{}>",
                extractionIri.getLocalName(), shapesModel.size(), shapesIri.getLocalName());
        return shapesIri;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Query: latest shapes
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Return the SHACL shapes model for the most recent extraction of the
     * given database/schema pair. The shapes are loaded directly from the
     * named graph identified by the shapes IRI.
     */
    public Model latestShapes(String databaseName, String schemaName) {
        IRI shapesIri = findLatestShapesIri(schemaName);
        if (shapesIri == null) {
            log.warn("No shapes registered for {}/{}", databaseName, schemaName);
            return new LinkedHashModel();
        }
        return loadNamedGraph(shapesIri);
    }

    /**
     * List all extraction records for a schema, newest first.
     */
    public List<Map<String, String>> listExtractions(String databaseName, String schemaName) {
        String sparql = """
                PREFIX dr:  <%s>
                SELECT ?record ?extractedAt ?tableCount ?columnCount ?shapesIri WHERE {
                    ?schema dr:schemaName "%s" ;
                            dr:hasVersion  ?record .
                    ?record dr:extractedAt  ?extractedAt ;
                            dr:tableCount   ?tableCount ;
                            dr:columnCount  ?columnCount ;
                            dr:hasShapes    ?shapesIri .
                }
                ORDER BY DESC(?extractedAt)
                """.formatted(DataRepositoryVocabulary.NS, schemaName);

        List<Map<String, String>> rows = new ArrayList<>();
        try (RepositoryConnection conn = repository.getConnection();
             TupleQueryResult rs = conn.prepareTupleQuery(sparql).evaluate()) {
            while (rs.hasNext()) {
                var bs = rs.next();
                Map<String, String> row = new LinkedHashMap<>();
                row.put("record",      bs.getValue("record").stringValue());
                row.put("extractedAt", bs.getValue("extractedAt").stringValue());
                row.put("tableCount",  bs.getValue("tableCount").stringValue());
                row.put("columnCount", bs.getValue("columnCount").stringValue());
                row.put("shapesIri",   bs.getValue("shapesIri").stringValue());
                rows.add(row);
            }
        }
        return rows;
    }

    /** Expose the raw repository for direct SPARQL access. */
    public Repository getRepository() { return repository; }

    // ─────────────────────────────────────────────────────────────────────────
    // Export
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Export the entire registry — default graph (ExtractionRecord catalogue) plus
     * every harvestor named graph (SHACL shapes) — as a TriG string.
     *
     * <h3>TriG structure produced</h3>
     * <pre>{@code
     * # Default graph: ExtractionRecord catalogue
     * {
     *     <repo/MY_DB/PUBLIC> dr:hasVersion <extraction/MY_DB/PUBLIC/2026-01-01_...> .
     *     <extraction/...>    dr:hasShapes  <http://example.org/harvestor/abc123> .
     *     ...
     * }
     *
     * # Named graph: SHACL shapes for V1
     * <http://example.org/harvestor/abc123> {
     *     <schema#CustomersShape> a sh:NodeShape ; ...
     *     <schema#CustomersShape/emailShape> a sh:PropertyShape ; ...
     * }
     *
     * # Named graph: SHACL shapes for V2
     * <http://example.org/harvestor/def456> { ... }
     * }</pre>
     *
     * @return serialized TriG as a UTF-8 string
     */
    public String exportAsTriG() {
        org.eclipse.rdf4j.model.impl.LinkedHashModel dataset =
                new org.eclipse.rdf4j.model.impl.LinkedHashModel();

        try (RepositoryConnection conn = repository.getConnection()) {
            // Collect all statements (default graph + all named graphs)
            conn.getStatements(null, null, null, true)
                .forEach(st -> dataset.add(
                        st.getSubject(),
                        st.getPredicate(),
                        st.getObject(),
                        st.getContext()   // null = default graph, IRI = named graph
                ));
        }

        return rdfSerializer.serialize(dataset, com.rdf.metadata.model.RdfFormat.TRIG);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────────

    private IRI findLatestShapesIri(String schemaName) {
        String sparql = """
                PREFIX dr:  <%s>
                SELECT ?shapesIri WHERE {
                    ?schema dr:schemaName "%s" ;
                            dr:hasVersion  ?record .
                    ?record dr:extractedAt ?extractedAt ;
                            dr:hasShapes   ?shapesIri .
                }
                ORDER BY DESC(?extractedAt)
                LIMIT 1
                """.formatted(DataRepositoryVocabulary.NS, schemaName);

        try (RepositoryConnection conn = repository.getConnection();
             TupleQueryResult rs = conn.prepareTupleQuery(sparql).evaluate()) {
            if (rs.hasNext()) {
                return (IRI) rs.next().getValue("shapesIri");
            }
        }
        return null;
    }

    private Model loadNamedGraph(IRI graphIri) {
        Model model = new LinkedHashModel();
        try (RepositoryConnection conn = repository.getConnection()) {
            conn.getStatements(null, null, null, graphIri)
                .forEach(st -> model.add(st.getSubject(), st.getPredicate(), st.getObject()));
        }
        log.debug("Loaded {} triples from named graph <{}>", model.size(), graphIri.getLocalName());
        return model;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // IRI factory
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Generate the named graph IRI for a SHACL shapes snapshot.
     *
     * <p>Delegates to {@link ExtractionIriFactory#harvestorGraphIri()} which
     * produces a fresh {@code http://example.org/harvestor/<22-char base-62 token>}
     * IRI on every call. The generated IRI is stored in the registry on the
     * {@code ExtractionRecord} via {@code dr:hasShapes} so it can always be
     * retrieved from the catalogue without regenerating it.
     */
    private static IRI newShapesIri() {
        return ExtractionIriFactory.harvestorGraphIri();
    }

    private static Literal lit(String v) { return VF.createLiteral(v != null ? v : ""); }

    @PreDestroy
    public void shutdown() {
        repository.shutDown();
        log.info("SchemaVersionRegistry shut down");
    }
}
