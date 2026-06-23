package com.rdf.metadata;

import com.rdf.metadata.config.RdfProperties;
import com.rdf.metadata.model.*;
import com.rdf.metadata.rdf.*;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.vocabulary.OWL;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.rdf.metadata.rdf.DataRepositoryVocabulary.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the separated four-graph ontology system built by {@link OntologyOrchestrator}.
 *
 * <p>Verifies:
 * <ol>
 *   <li>Each graph contains only what it should (correct separation)</li>
 *   <li>Each graph has the correct {@code owl:Ontology} header</li>
 *   <li>{@code owl:imports} links form the correct dependency chain</li>
 *   <li>The linking ontology bridges {@code owl:Class} → {@code dr:DatabaseTable}</li>
 *   <li>The merged union model contains all triples from all four graphs</li>
 * </ol>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SeparatedOntologyTest {

    private static final String BASE = "http://example.org/schema#";

    private SeparatedOntologyResult result;
    private OntologyOrchestrator    orchestrator;

    @BeforeAll
    void buildGraphs() {
        RdfProperties props = new RdfProperties();
        props.setBaseNamespace(BASE);

        orchestrator = new OntologyOrchestrator(
                new DataRepositoryVocabularyBuilder(),
                new RepoInstanceBuilder(props),
                new SchemaOntologyBuilder(props),
                new LinkingOntologyBuilder(props),
                props
        );

        SchemaMetadata schema = SchemaMetadata.builder()
                .databaseType("SNOWFLAKE")
                .databaseName("MY_DB")
                .schemaName("PUBLIC")
                .warehouse("COMPUTE_WH")
                .role("SYSADMIN")
                .authMode("KEY_PAIR")
                .extractedAt(Instant.parse("2026-06-23T10:00:00Z"))
                .tables(List.of(buildOrdersTable(), buildCustomersTable()))
                .build();

        result = orchestrator.buildAll(schema);
    }

    // ─── Graph 1: Vocabulary ──────────────────────────────────────────────────

    @Test
    @DisplayName("Vocabulary graph: contains owl:Class declarations for all dr: classes")
    void vocabHasClassDeclarations() {
        var model = result.getRepoVocabularyModel();
        assertThat(model.contains(DataRepository,  RDF.TYPE, OWL.CLASS)).isTrue();
        assertThat(model.contains(DatabaseSchema,  RDF.TYPE, OWL.CLASS)).isTrue();
        assertThat(model.contains(DatabaseTable,   RDF.TYPE, OWL.CLASS)).isTrue();
        assertThat(model.contains(TableColumn,     RDF.TYPE, OWL.CLASS)).isTrue();
    }

    @Test
    @DisplayName("Vocabulary graph: contains owl:DatatypeProperty declarations")
    void vocabHasPropertyDeclarations() {
        var model = result.getRepoVocabularyModel();
        assertThat(model.contains(databaseType,    RDF.TYPE, OWL.DATATYPEPROPERTY)).isTrue();
        assertThat(model.contains(columnName,      RDF.TYPE, OWL.DATATYPEPROPERTY)).isTrue();
        assertThat(model.contains(isNullable,      RDF.TYPE, OWL.DATATYPEPROPERTY)).isTrue();
    }

    @Test
    @DisplayName("Vocabulary graph: contains owl:ObjectProperty declarations")
    void vocabHasObjectPropertyDeclarations() {
        var model = result.getRepoVocabularyModel();
        assertThat(model.contains(hasSchema, RDF.TYPE, OWL.OBJECTPROPERTY)).isTrue();
        assertThat(model.contains(hasTable,  RDF.TYPE, OWL.OBJECTPROPERTY)).isTrue();
        assertThat(model.contains(hasColumn, RDF.TYPE, OWL.OBJECTPROPERTY)).isTrue();
    }

    @Test
    @DisplayName("Vocabulary graph: does NOT contain any dr:DataRepository individual")
    void vocabContainsNoInstanceData() {
        // No subject should be typed as DataRepository (instances are in the repo graph)
        assertThat(result.getRepoVocabularyModel().filter(null, RDF.TYPE, DataRepository)).isEmpty();
    }

    @Test
    @DisplayName("Vocabulary graph: has owl:Ontology header")
    void vocabHasOntologyHeader() {
        IRI vocabIri = result.getRepoVocabularyIri();
        assertThat(result.getRepoVocabularyModel().contains(vocabIri, RDF.TYPE, OWL.ONTOLOGY)).isTrue();
    }

    // ─── Graph 2: Repository Instance ────────────────────────────────────────

    @Test
    @DisplayName("Repo instance graph: has owl:Ontology header")
    void repoHasOntologyHeader() {
        IRI repoOntIri = result.getRepoInstanceIri();
        assertThat(result.getRepoInstanceModel().contains(repoOntIri, RDF.TYPE, OWL.ONTOLOGY)).isTrue();
    }

    @Test
    @DisplayName("Repo instance graph: imports vocabulary")
    void repoImportsVocab() {
        IRI repoOntIri  = result.getRepoInstanceIri();
        IRI vocabOntIri = result.getRepoVocabularyIri();
        assertThat(result.getRepoInstanceModel()
                .contains(repoOntIri, OWL.IMPORTS, vocabOntIri)).isTrue();
    }

    @Test
    @DisplayName("Repo instance graph: contains exactly one DataRepository individual")
    void repoHasOneDataRepositoryIndividual() {
        assertThat(result.getRepoInstanceModel()
                .filter(null, RDF.TYPE, DataRepository).subjects()).hasSize(1);
    }

    @Test
    @DisplayName("Repo instance graph: DataRepository has warehouse and authMode as RDF literals")
    void repoDataRepositoryProperties() {
        var subject = result.getRepoInstanceModel()
                .filter(null, RDF.TYPE, DataRepository).subjects().iterator().next();
        Set<String> warehouses = result.getRepoInstanceModel()
                .filter(subject, warehouse, null).objects().stream()
                .map(v -> v.stringValue()).collect(Collectors.toSet());
        assertThat(warehouses).contains("COMPUTE_WH");

        Set<String> authModes = result.getRepoInstanceModel()
                .filter(subject, authMode, null).objects().stream()
                .map(v -> v.stringValue()).collect(Collectors.toSet());
        assertThat(authModes).contains("KEY_PAIR");
    }

    @Test
    @DisplayName("Repo instance graph: two DatabaseTable individuals (one per table)")
    void repoHasTwoDatabaseTableIndividuals() {
        assertThat(result.getRepoInstanceModel()
                .filter(null, RDF.TYPE, DatabaseTable).subjects()).hasSize(2);
    }

    @Test
    @DisplayName("Repo instance graph: TableColumn individuals present with columnName literals")
    void repoHasTableColumnIndividuals() {
        var colNodes = result.getRepoInstanceModel()
                .filter(null, RDF.TYPE, TableColumn).subjects();
        assertThat(colNodes).isNotEmpty();

        Set<String> colNames = result.getRepoInstanceModel()
                .filter(null, columnName, null).objects().stream()
                .map(v -> v.stringValue()).collect(Collectors.toSet());
        assertThat(colNames).contains("ORDER_ID", "CUSTOMER_ID");
    }

    @Test
    @DisplayName("Repo instance graph: does NOT contain any owl:Class (those are in schema ontology)")
    void repoContainsNoOwlClasses() {
        // Filter out the OWL.CLASS triples that come from vocabulary declarations
        // (which are NOT present in the repo instance graph)
        assertThat(result.getRepoInstanceModel().filter(null, RDF.TYPE, OWL.CLASS)).isEmpty();
    }

    // ─── Graph 3: Schema Ontology ─────────────────────────────────────────────

    @Test
    @DisplayName("Schema ontology: has owl:Ontology header")
    void schemaOntHasHeader() {
        IRI schemaOntIri = result.getSchemaOntologyIri();
        assertThat(result.getSchemaOntologyModel()
                .contains(schemaOntIri, RDF.TYPE, OWL.ONTOLOGY)).isTrue();
    }

    @Test
    @DisplayName("Schema ontology: imports vocabulary")
    void schemaOntImportsVocab() {
        IRI schemaOntIri = result.getSchemaOntologyIri();
        IRI vocabOntIri  = result.getRepoVocabularyIri();
        assertThat(result.getSchemaOntologyModel()
                .contains(schemaOntIri, OWL.IMPORTS, vocabOntIri)).isTrue();
    }

    @Test
    @DisplayName("Schema ontology: two owl:Class declarations (one per table)")
    void schemaOntHasTwoOwlClasses() {
        assertThat(result.getSchemaOntologyModel()
                .filter(null, RDF.TYPE, OWL.CLASS).subjects()).hasSize(2);
    }

    @Test
    @DisplayName("Schema ontology: owl:DatatypeProperty declarations present")
    void schemaOntHasDatatypeProperties() {
        assertThat(result.getSchemaOntologyModel()
                .filter(null, RDF.TYPE, OWL.DATATYPEPROPERTY).subjects()).isNotEmpty();
    }

    @Test
    @DisplayName("Schema ontology: owl:ObjectProperty present for FK")
    void schemaOntHasObjectProperty() {
        assertThat(result.getSchemaOntologyModel()
                .filter(null, RDF.TYPE, OWL.OBJECTPROPERTY).subjects()).isNotEmpty();
    }

    @Test
    @DisplayName("Schema ontology: does NOT contain any DataRepository individual")
    void schemaOntContainsNoRepoInstances() {
        assertThat(result.getSchemaOntologyModel()
                .filter(null, RDF.TYPE, DataRepository)).isEmpty();
    }

    // ─── Graph 4: Linking Ontology ────────────────────────────────────────────

    @Test
    @DisplayName("Linking ontology: has owl:Ontology header")
    void linkingHasOntologyHeader() {
        IRI linkIri = result.getLinkingOntologyIri();
        assertThat(result.getLinkingOntologyModel()
                .contains(linkIri, RDF.TYPE, OWL.ONTOLOGY)).isTrue();
    }

    @Test
    @DisplayName("Linking ontology: imports both repo instance and schema ontology")
    void linkingImportsBothGraphs() {
        IRI linkIri   = result.getLinkingOntologyIri();
        var model     = result.getLinkingOntologyModel();
        assertThat(model.contains(linkIri, OWL.IMPORTS, result.getRepoInstanceIri())).isTrue();
        assertThat(model.contains(linkIri, OWL.IMPORTS, result.getSchemaOntologyIri())).isTrue();
    }

    @Test
    @DisplayName("Linking ontology: dr:sourceTable declared as owl:AnnotationProperty")
    void linkingSourceTableDeclared() {
        assertThat(result.getLinkingOntologyModel()
                .contains(LinkingOntologyBuilder.SOURCE_TABLE, RDF.TYPE, OWL.ANNOTATIONPROPERTY))
                .isTrue();
    }

    @Test
    @DisplayName("Linking ontology: two dr:sourceTable bridge triples (one per table)")
    void linkingHasBridgeTriples() {
        long bridgeCount = result.getLinkingOntologyModel()
                .filter(null, LinkingOntologyBuilder.SOURCE_TABLE, null)
                .stream().count();
        assertThat(bridgeCount).isEqualTo(2L);
    }

    @Test
    @DisplayName("Linking ontology: Orders owl:Class linked to Orders DatabaseTable individual")
    void linkingOrdersClassToTableIndividual() {
        IRI ordersClass  = svf().createIRI(BASE + "Orders");
        IRI ordersTable  = RepoInstanceBuilder.tableIndividualIri(BASE, "ORDERS");
        assertThat(result.getLinkingOntologyModel()
                .contains(ordersClass, LinkingOntologyBuilder.SOURCE_TABLE, ordersTable)).isTrue();
    }

    @Test
    @DisplayName("Linking ontology: rdfs:seeAlso bridges also present as fallback")
    void linkingHasSeeAlsoBridges() {
        long seeAlsoCount = result.getLinkingOntologyModel()
                .filter(null, RDFS.SEEALSO, null).stream().count();
        assertThat(seeAlsoCount).isEqualTo(2L);
    }

    // ─── Graph independence ───────────────────────────────────────────────────

    @Test
    @DisplayName("Each graph has a distinct owl:Ontology IRI")
    void allGraphIrisAreDistinct() {
        Set<IRI> iris = Set.of(
                result.getRepoVocabularyIri(),
                result.getRepoInstanceIri(),
                result.getSchemaOntologyIri(),
                result.getLinkingOntologyIri()
        );
        assertThat(iris).hasSize(4);
    }

    @Test
    @DisplayName("Total triple count is the sum of all four graph sizes")
    void totalTriplesConsistent() {
        int summed = result.getRepoVocabularyModel().size()
                   + result.getRepoInstanceModel().size()
                   + result.getSchemaOntologyModel().size()
                   + result.getLinkingOntologyModel().size();
        assertThat(result.totalTriples()).isEqualTo(summed);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static org.eclipse.rdf4j.model.impl.SimpleValueFactory svf() {
        return org.eclipse.rdf4j.model.impl.SimpleValueFactory.getInstance();
    }

    private TableMetadata buildOrdersTable() {
        return TableMetadata.builder()
                .schemaName("PUBLIC").tableName("ORDERS").tableType("TABLE")
                .primaryKeyColumns(List.of("ORDER_ID"))
                .columns(List.of(
                        col("ORDER_ID",    "BIGINT",  false, true),
                        col("CUSTOMER_ID", "BIGINT",  false, false),
                        col("STATUS",      "VARCHAR", false, false)
                ))
                .foreignKeys(List.of(
                        ForeignKeyMetadata.builder()
                                .constraintName("FK_ORDERS_CUST")
                                .fkTableSchema("PUBLIC").fkTableName("ORDERS").fkColumnName("CUSTOMER_ID")
                                .pkTableSchema("PUBLIC").pkTableName("CUSTOMERS").pkColumnName("CUSTOMER_ID")
                                .updateRule("NO_ACTION").deleteRule("NO_ACTION").build()
                ))
                .uniqueConstraints(List.of()).checkConstraints(List.of())
                .build();
    }

    private TableMetadata buildCustomersTable() {
        return TableMetadata.builder()
                .schemaName("PUBLIC").tableName("CUSTOMERS").tableType("TABLE")
                .primaryKeyColumns(List.of("CUSTOMER_ID"))
                .columns(List.of(
                        col("CUSTOMER_ID", "BIGINT",  false, true),
                        col("EMAIL",       "VARCHAR", false, false)
                ))
                .foreignKeys(List.of()).uniqueConstraints(List.of()).checkConstraints(List.of())
                .build();
    }

    private ColumnMetadata col(String name, String type, boolean nullable, boolean pk) {
        return ColumnMetadata.builder()
                .columnName(name).dataType(type).nullable(nullable)
                .primaryKey(pk).ordinalPosition(1).build();
    }
}
