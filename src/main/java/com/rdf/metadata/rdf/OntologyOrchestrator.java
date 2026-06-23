package com.rdf.metadata.rdf;

import com.rdf.metadata.config.RdfProperties;
import com.rdf.metadata.model.SchemaMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.model.Model;
import org.springframework.stereotype.Component;

/**
 * Orchestrates the construction of all four RDF ontology graphs from
 * a single {@link SchemaMetadata} instance.
 *
 * <h3>Execution order</h3>
 * <ol>
 *   <li>{@link DataRepositoryVocabularyBuilder} — vocabulary graph
 *       (class/property declarations, no instance data)</li>
 *   <li>{@link RepoInstanceBuilder} — repository instance graph
 *       ({@code dr:DataRepository} and structural individuals)</li>
 *   <li>{@link SchemaOntologyBuilder} — schema/domain ontology graph
 *       ({@code owl:Class}, {@code owl:DatatypeProperty}, restrictions)</li>
 *   <li>{@link LinkingOntologyBuilder} — linking graph
 *       ({@code owl:imports} both + {@code dr:sourceTable} bridges)</li>
 * </ol>
 *
 * <h3>Graph independence</h3>
 * Each graph can be loaded, serialized, stored, and queried independently.
 * The dependency direction is:
 * <pre>
 *   schema-ontology  owl:imports  vocab-ontology
 *   repo-instance    owl:imports  vocab-ontology
 *   linking          owl:imports  repo-instance
 *   linking          owl:imports  schema-ontology
 * </pre>
 * There are no circular imports.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OntologyOrchestrator {

    private final DataRepositoryVocabularyBuilder vocabularyBuilder;
    private final RepoInstanceBuilder              repoInstanceBuilder;
    private final SchemaOntologyBuilder            schemaOntologyBuilder;
    private final LinkingOntologyBuilder           linkingOntologyBuilder;
    private final RdfProperties                    rdfProperties;

    /**
     * Build and return all four ontology graphs for the given schema.
     *
     * @param schema extracted relational schema metadata
     * @return {@link SeparatedOntologyResult} containing all four models and their IRIs
     */
    public SeparatedOntologyResult buildAll(SchemaMetadata schema) {
        String base = rdfProperties.getBaseNamespace();
        log.info("Building separated ontology graphs for {}/{}", 
                schema.getDatabaseName(), schema.getSchemaName());

        // 1. Vocabulary
        Model vocabModel = new org.eclipse.rdf4j.model.impl.LinkedHashModel();
        vocabularyBuilder.emitVocabulary(vocabModel);
        addVocabOntologyHeader(vocabModel, base, schema);
        org.eclipse.rdf4j.model.IRI vocabIri = RepoInstanceBuilder.repoVocabOntIri(base);

        // 2. Repository instance
        OntologyGraph repoGraph = repoInstanceBuilder.build(schema);

        // 3. Schema ontology
        OntologyGraph schemaGraph = schemaOntologyBuilder.build(schema);

        // 4. Linking ontology
        OntologyGraph linkGraph = linkingOntologyBuilder.build(
                schema, repoGraph.ontologyIri(), schemaGraph.ontologyIri());

        log.info("Separated ontology complete: vocab={}, repo={}, schema={}, link={} triples",
                vocabModel.size(), repoGraph.model().size(),
                schemaGraph.model().size(), linkGraph.model().size());

        return SeparatedOntologyResult.builder()
                .repoVocabularyIri(vocabIri)
                .repoInstanceIri(repoGraph.ontologyIri())
                .schemaOntologyIri(schemaGraph.ontologyIri())
                .linkingOntologyIri(linkGraph.ontologyIri())
                .repoVocabularyModel(vocabModel)
                .repoInstanceModel(repoGraph.model())
                .schemaOntologyModel(schemaGraph.model())
                .linkingOntologyModel(linkGraph.model())
                .build();
    }

    /**
     * Add an {@code owl:Ontology} header to the vocabulary model.
     * The vocabulary builder only emits property/class triples; the header
     * is added here so the graph is a valid named ontology.
     */
    private void addVocabOntologyHeader(Model model, String base, SchemaMetadata schema) {
        org.eclipse.rdf4j.model.IRI vocabIri = RepoInstanceBuilder.repoVocabOntIri(base);
        org.eclipse.rdf4j.model.impl.SimpleValueFactory vf =
                org.eclipse.rdf4j.model.impl.SimpleValueFactory.getInstance();

        model.add(vocabIri, org.eclipse.rdf4j.model.vocabulary.RDF.TYPE,
                org.eclipse.rdf4j.model.vocabulary.OWL.ONTOLOGY);
        model.add(vocabIri, org.eclipse.rdf4j.model.vocabulary.RDFS.LABEL,
                vf.createLiteral("DataRepository Vocabulary"));
        model.add(vocabIri, org.eclipse.rdf4j.model.vocabulary.RDFS.COMMENT,
                vf.createLiteral("Vocabulary defining dr:DataRepository, dr:DatabaseSchema, "
                    + "dr:DatabaseTable, dr:TableColumn and their properties."));
        model.add(vocabIri, org.eclipse.rdf4j.model.vocabulary.OWL.VERSIONINFO,
                vf.createLiteral("1.0"));

        model.setNamespace("dr",   DataRepositoryVocabulary.NS);
        model.setNamespace("owl",  org.eclipse.rdf4j.model.vocabulary.OWL.NAMESPACE);
        model.setNamespace("rdfs", org.eclipse.rdf4j.model.vocabulary.RDFS.NAMESPACE);
        model.setNamespace("xsd",  org.eclipse.rdf4j.model.vocabulary.XSD.NAMESPACE);
    }
}
