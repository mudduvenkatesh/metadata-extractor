package com.rdf.metadata.rdf;

import com.rdf.metadata.config.RdfProperties;
import com.rdf.metadata.model.SchemaMetadata;
import com.rdf.metadata.model.TableMetadata;
import com.rdf.metadata.util.IriUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.model.*;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.*;
import org.springframework.stereotype.Component;

import static com.rdf.metadata.rdf.DataRepositoryVocabulary.NS;

/**
 * Builds the <b>Linking Ontology</b> — a thin bridge graph that:
 *
 * <ol>
 *   <li>Imports both the repo instance ontology and the schema ontology via
 *       {@code owl:imports}</li>
 *   <li>Declares {@code dr:sourceTable} as an {@code owl:AnnotationProperty}
 *       so a single vocabulary term serves as the cross-graph connector</li>
 *   <li>For every table, asserts:
 *       <pre>{@code
 *       schema:Orders  dr:sourceTable  repo:table/Orders .
 *       }</pre>
 *       linking each {@code owl:Class} in the schema ontology to its
 *       corresponding {@code dr:DatabaseTable} individual in the repo instance
 *       ontology</li>
 *   <li>Adds {@code rdfs:seeAlso} as a weaker, widely-understood alternative
 *       bridge for tooling that doesn't understand {@code dr:sourceTable}</li>
 * </ol>
 *
 * <h3>Why a separate linking ontology?</h3>
 * <p>Neither the schema ontology nor the repo instance ontology should import
 * each other — that would create a circular dependency and violate their
 * separation of concerns. A dedicated linking graph is the standard OWL
 * pattern for cross-ontology alignment (cf. SKOS mappings, OWL alignments).
 * It can be loaded or omitted independently.
 *
 * <h3>Ontology IRI pattern</h3>
 * <pre>{@code <base>link/<databaseName>/<schemaName> }</pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LinkingOntologyBuilder {

    /** {@code dr:sourceTable} — annotation property declared in this graph. */
    public static final IRI SOURCE_TABLE = SimpleValueFactory.getInstance()
            .createIRI(NS + "sourceTable");

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    private final RdfProperties rdfProperties;

    /**
     * Build the linking ontology given the schema metadata and the IRIs of
     * the two ontologies it must bridge.
     *
     * @param schema          source relational metadata
     * @param repoInstanceIri IRI of the {@link RepoInstanceBuilder} ontology
     * @param schemaOntIri    IRI of the {@link SchemaOntologyBuilder} ontology
     * @return the linking {@link OntologyGraph}
     */
    public OntologyGraph build(SchemaMetadata schema,
                                IRI repoInstanceIri,
                                IRI schemaOntIri) {
        Model model = new LinkedHashModel();
        String base = rdfProperties.getBaseNamespace();

        // ── Ontology header ───────────────────────────────────────────────────
        IRI ontIri = linkingOntIri(schema, base);
        add(model, ontIri, RDF.TYPE,        OWL.ONTOLOGY);
        add(model, ontIri, RDFS.LABEL,      lit("Linking Ontology: "
                + schema.getDatabaseName() + " / " + schema.getSchemaName()));
        add(model, ontIri, RDFS.COMMENT,    lit("""
                Bridges the schema ontology (OWL classes/properties) and the repository \
                instance ontology (dr:DataRepository individuals) via dr:sourceTable assertions.\
                """));
        add(model, ontIri, OWL.VERSIONINFO, lit("1.0"));

        // owl:imports both sides
        add(model, ontIri, OWL.IMPORTS, repoInstanceIri);
        add(model, ontIri, OWL.IMPORTS, schemaOntIri);

        setNamespaces(model, base);

        // ── Declare dr:sourceTable as an annotation property ──────────────────
        add(model, SOURCE_TABLE, RDF.TYPE,      OWL.ANNOTATIONPROPERTY);
        add(model, SOURCE_TABLE, RDFS.LABEL,    lit("sourceTable"));
        add(model, SOURCE_TABLE, RDFS.COMMENT,  lit("""
                Links an owl:Class in the schema ontology to the dr:DatabaseTable \
                individual in the repository instance ontology that it was derived from.\
                """));
        add(model, SOURCE_TABLE, RDFS.DOMAIN,   OWL.CLASS);
        add(model, SOURCE_TABLE, RDFS.RANGE,    DataRepositoryVocabulary.DatabaseTable);

        // ── Bridge triples: owl:Class ──dr:sourceTable──► dr:DatabaseTable ────
        int bridgeCount = 0;
        for (TableMetadata table : schema.getTables()) {
            IRI owlClass   = iri(IriUtils.classIri(base, table.getTableName()));
            IRI tableIndiv = RepoInstanceBuilder.tableIndividualIri(base, table.getTableName());

            // Primary bridge
            add(model, owlClass, SOURCE_TABLE,   tableIndiv);
            // Weaker fallback for tooling that understands rdfs:seeAlso
            add(model, owlClass, RDFS.SEEALSO,   tableIndiv);

            bridgeCount++;
        }

        log.info("Linking ontology built: {} bridge triples for {} tables",
                bridgeCount * 2, bridgeCount);
        return new OntologyGraph(ontIri, model);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // IRI helpers
    // ─────────────────────────────────────────────────────────────────────────

    static IRI linkingOntIri(SchemaMetadata schema, String base) {
        return VF.createIRI(base + "link/"
                + IriUtils.sanitise(schema.getDatabaseName())
                + "/" + IriUtils.sanitise(schema.getSchemaName()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────────────────────────────────

    private void setNamespaces(Model model, String base) {
        model.setNamespace("schema", base);
        model.setNamespace("dr",     NS);
        model.setNamespace("repo",   base + "repo/");
        model.setNamespace("link",   base + "link/");
        model.setNamespace("owl",    OWL.NAMESPACE);
        model.setNamespace("rdfs",   RDFS.NAMESPACE);
    }

    private static IRI iri(String uri)           { return VF.createIRI(uri); }
    private static Literal lit(String v)         { return VF.createLiteral(v); }
    private static void add(Model m, Resource s, IRI p, Value o) { m.add(s, p, o); }
}
