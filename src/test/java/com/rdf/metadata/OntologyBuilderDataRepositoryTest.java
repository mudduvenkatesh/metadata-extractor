package com.rdf.metadata;

import com.rdf.metadata.config.RdfProperties;
import com.rdf.metadata.model.*;
import com.rdf.metadata.rdf.DataRepositoryVocabulary;
import com.rdf.metadata.rdf.DataRepositoryVocabularyBuilder;
import com.rdf.metadata.rdf.OntologyBuilder;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
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
 * Tests that the {@link OntologyBuilder} correctly emits:
 * <ol>
 *   <li>The {@code dr:} vocabulary as RDF statements</li>
 *   <li>A {@code dr:DataRepository} instance with connection properties from {@link SchemaMetadata}</li>
 *   <li>A {@code dr:DatabaseSchema} linked to the repository</li>
 *   <li>{@code dr:DatabaseTable} individuals linked to the schema</li>
 *   <li>{@code dr:TableColumn} individuals with full property metadata</li>
 *   <li>OWL axioms still present alongside the instance graph</li>
 *   <li>Cross-link from {@code owl:Class} back to its {@code dr:DatabaseTable} individual</li>
 * </ol>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OntologyBuilderDataRepositoryTest {

    private Model model;
    private static final String BASE = "http://example.org/schema#";

    @BeforeAll
    void buildModel() {
        RdfProperties props = new RdfProperties();
        props.setBaseNamespace(BASE);

        OntologyBuilder builder = new OntologyBuilder(props, new DataRepositoryVocabularyBuilder());

        SchemaMetadata schema = SchemaMetadata.builder()
                .databaseType("SNOWFLAKE")
                .databaseName("MY_DATABASE")
                .schemaName("PUBLIC")
                .host(null)
                .jdbcUrl("jdbc:snowflake://xy12345.snowflakecomputing.com/")
                .warehouse("COMPUTE_WH")
                .role("SYSADMIN")
                .authMode("KEY_PAIR")
                .extractedAt(Instant.parse("2026-06-23T10:00:00Z"))
                .tables(List.of(buildCustomersTable(), buildOrdersTable()))
                .build();

        model = builder.build(schema);
    }

    // ─── Vocabulary declarations ──────────────────────────────────────────────

    @Test
    @DisplayName("dr: vocabulary — all four classes are declared as owl:Class")
    void vocabularyClassesPresent() {
        List<IRI> expectedClasses = List.of(
                DataRepository, DatabaseSchema, DatabaseTable, TableColumn);

        for (IRI cls : expectedClasses) {
            assertThat(model.contains(cls, RDF.TYPE, OWL.CLASS))
                    .as("Expected owl:Class declaration for <%s>", cls)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("dr: vocabulary — object properties declared with domain and range")
    void vocabularyObjectPropertiesPresent() {
        assertThat(model.contains(hasSchema, RDF.TYPE, OWL.OBJECTPROPERTY)).isTrue();
        assertThat(model.contains(hasTable,  RDF.TYPE, OWL.OBJECTPROPERTY)).isTrue();
        assertThat(model.contains(hasColumn, RDF.TYPE, OWL.OBJECTPROPERTY)).isTrue();

        // Domain/range links
        assertThat(model.contains(hasSchema, RDFS.DOMAIN, DataRepository)).isTrue();
        assertThat(model.contains(hasSchema, RDFS.RANGE,  DatabaseSchema)).isTrue();
        assertThat(model.contains(hasTable,  RDFS.DOMAIN, DatabaseSchema)).isTrue();
        assertThat(model.contains(hasTable,  RDFS.RANGE,  DatabaseTable)).isTrue();
    }

    @Test
    @DisplayName("dr: vocabulary — datatype properties declared with correct range types")
    void vocabularyDatatypePropertiesPresent() {
        assertThat(model.contains(databaseType,    RDF.TYPE, OWL.DATATYPEPROPERTY)).isTrue();
        assertThat(model.contains(columnName,      RDF.TYPE, OWL.DATATYPEPROPERTY)).isTrue();
        assertThat(model.contains(isNullable,      RDF.TYPE, OWL.DATATYPEPROPERTY)).isTrue();
        assertThat(model.contains(ordinalPosition, RDF.TYPE, OWL.DATATYPEPROPERTY)).isTrue();
    }

    // ─── DataRepository instance ──────────────────────────────────────────────

    @Test
    @DisplayName("DataRepository individual is present")
    void dataRepositoryInstancePresent() {
        Set<org.eclipse.rdf4j.model.Resource> repos = model.filter(null, RDF.TYPE, DataRepository)
                .subjects();
        assertThat(repos).hasSize(1);
    }

    @Test
    @DisplayName("DataRepository has databaseType = SNOWFLAKE")
    void dataRepositoryHasDatabaseType() {
        var repos = model.filter(null, RDF.TYPE, DataRepository).subjects();
        var repo = repos.iterator().next();

        Set<String> values = model.filter(repo, databaseType, null).objects().stream()
                .map(v -> v.stringValue())
                .collect(Collectors.toSet());
        assertThat(values).contains("SNOWFLAKE");
    }

    @Test
    @DisplayName("DataRepository stores warehouse, role and authMode as RDF literals")
    void dataRepositoryConnectionProperties() {
        var repo = model.filter(null, RDF.TYPE, DataRepository).subjects().iterator().next();

        assertThat(model.filter(repo, warehouse, null).objects().stream()
                .map(org.eclipse.rdf4j.model.Value::stringValue).collect(Collectors.toSet()))
                .contains("COMPUTE_WH");

        assertThat(model.filter(repo, role, null).objects().stream()
                .map(org.eclipse.rdf4j.model.Value::stringValue).collect(Collectors.toSet()))
                .contains("SYSADMIN");

        assertThat(model.filter(repo, authMode, null).objects().stream()
                .map(org.eclipse.rdf4j.model.Value::stringValue).collect(Collectors.toSet()))
                .contains("KEY_PAIR");
    }

    @Test
    @DisplayName("DataRepository has extractedAt as xsd:dateTime literal")
    void dataRepositoryExtractedAt() {
        var repo = model.filter(null, RDF.TYPE, DataRepository).subjects().iterator().next();
        Set<String> values = model.filter(repo, extractedAt, null).objects().stream()
                .map(org.eclipse.rdf4j.model.Value::stringValue)
                .collect(Collectors.toSet());
        assertThat(values).anyMatch(v -> v.contains("2026-06-23"));
    }

    // ─── DatabaseSchema instance ──────────────────────────────────────────────

    @Test
    @DisplayName("DatabaseSchema individual is present and linked to DataRepository")
    void databaseSchemaLinked() {
        Set<org.eclipse.rdf4j.model.Resource> schemas = model.filter(null, RDF.TYPE, DatabaseSchema)
                .subjects();
        assertThat(schemas).hasSize(1);

        var schemaNode = schemas.iterator().next();
        // repo --dr:hasSchema--> schema
        var repos = model.filter(null, RDF.TYPE, DataRepository).subjects();
        var repo  = repos.iterator().next();
        assertThat(model.contains(repo, hasSchema, schemaNode)).isTrue();
    }

    @Test
    @DisplayName("DatabaseSchema has schemaName = PUBLIC")
    void databaseSchemaName() {
        var schema = model.filter(null, RDF.TYPE, DatabaseSchema).subjects().iterator().next();
        Set<String> values = model.filter(schema, schemaName, null).objects().stream()
                .map(org.eclipse.rdf4j.model.Value::stringValue).collect(Collectors.toSet());
        assertThat(values).contains("PUBLIC");
    }

    // ─── DatabaseTable instances ──────────────────────────────────────────────

    @Test
    @DisplayName("Two DatabaseTable individuals are present (one per table)")
    void databaseTableIndividuals() {
        assertThat(model.filter(null, RDF.TYPE, DatabaseTable).subjects()).hasSize(2);
    }

    @Test
    @DisplayName("DatabaseTable individuals are linked from DatabaseSchema via dr:hasTable")
    void databaseTablesLinkedToSchema() {
        var schema = model.filter(null, RDF.TYPE, DatabaseSchema).subjects().iterator().next();
        Set<org.eclipse.rdf4j.model.Value> linkedTables = model.filter(schema, hasTable, null).objects();
        assertThat(linkedTables).hasSize(2);
    }

    @Test
    @DisplayName("DatabaseTable has tableName and tableType literals")
    void databaseTableProperties() {
        Set<String> tableNames = model.filter(null, tableName, null).objects().stream()
                .map(org.eclipse.rdf4j.model.Value::stringValue)
                .collect(Collectors.toSet());
        assertThat(tableNames).containsExactlyInAnyOrder("CUSTOMERS", "ORDERS");

        Set<String> tableTypes = model.filter(null, tableType, null).objects().stream()
                .map(org.eclipse.rdf4j.model.Value::stringValue)
                .collect(Collectors.toSet());
        assertThat(tableTypes).contains("TABLE");
    }

    // ─── TableColumn instances ────────────────────────────────────────────────

    @Test
    @DisplayName("TableColumn individuals are present for all columns")
    void tableColumnIndividuals() {
        // 4 customers cols + 4 orders cols = 8
        assertThat(model.filter(null, RDF.TYPE, TableColumn).subjects()).hasSize(8);
    }

    @Test
    @DisplayName("TableColumn has columnName, sqlDataType, isNullable, isPrimaryKey")
    void tableColumnProperties() {
        // Find the ORDER_ID column node
        Set<org.eclipse.rdf4j.model.Resource> orderIdCols = model.filter(null, columnName, null)
                .stream()
                .filter(s -> "ORDER_ID".equals(s.getObject().stringValue()))
                .map(org.eclipse.rdf4j.model.Statement::getSubject)
                .collect(Collectors.toSet());

        assertThat(orderIdCols).isNotEmpty();
        var orderIdNode = orderIdCols.iterator().next();

        // isPrimaryKey = true
        assertThat(model.filter(orderIdNode, isPrimaryKey, null).objects().stream()
                .map(org.eclipse.rdf4j.model.Value::stringValue)
                .collect(Collectors.toSet()))
                .contains("true");

        // isNullable = false
        assertThat(model.filter(orderIdNode, isNullable, null).objects().stream()
                .map(org.eclipse.rdf4j.model.Value::stringValue)
                .collect(Collectors.toSet()))
                .contains("false");
    }

    @Test
    @DisplayName("FK column has isForeignKey = true")
    void fkColumnFlagSet() {
        // CUSTOMER_ID in ORDERS is a FK
        Set<org.eclipse.rdf4j.model.Resource> customerIdCols = model.filter(null, columnName, null)
                .stream()
                .filter(s -> "CUSTOMER_ID".equals(s.getObject().stringValue()))
                .map(org.eclipse.rdf4j.model.Statement::getSubject)
                .collect(Collectors.toSet());

        // Both CUSTOMERS.CUSTOMER_ID and ORDERS.CUSTOMER_ID exist — find the FK one
        boolean anyFk = customerIdCols.stream()
                .flatMap(node -> model.filter(node, isForeignKey, null).objects().stream())
                .anyMatch(v -> "true".equals(v.stringValue()));
        assertThat(anyFk).isTrue();
    }

    // ─── OWL axioms still present ─────────────────────────────────────────────

    @Test
    @DisplayName("OWL classes for domain tables still present alongside instance graph")
    void owlClassesStillPresent() {
        // owl:Class for Customers and Orders
        IRI customersClass = SimpleValueFactory.getInstance()
                .createIRI(BASE + "Customers");
        IRI ordersClass    = SimpleValueFactory.getInstance()
                .createIRI(BASE + "Orders");

        assertThat(model.contains(customersClass, RDF.TYPE, OWL.CLASS)).isTrue();
        assertThat(model.contains(ordersClass,    RDF.TYPE, OWL.CLASS)).isTrue();
    }

    @Test
    @DisplayName("owl:Class is linked to its dr:DatabaseTable node via describedBy")
    void owlClassLinkedToTableNode() {
        IRI ordersClass  = SimpleValueFactory.getInstance().createIRI(BASE + "Orders");
        IRI ordersTable  = SimpleValueFactory.getInstance().createIRI(BASE + "Orders_TableNode");
        IRI describedBy  = SimpleValueFactory.getInstance().createIRI(BASE + "describedBy");

        assertThat(model.contains(ordersClass, describedBy, ordersTable)).isTrue();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static final org.eclipse.rdf4j.model.impl.SimpleValueFactory SimpleValueFactory =
            org.eclipse.rdf4j.model.impl.SimpleValueFactory.getInstance();

    private TableMetadata buildCustomersTable() {
        return TableMetadata.builder()
                .schemaName("PUBLIC").tableName("CUSTOMERS").tableType("TABLE")
                .primaryKeyColumns(List.of("CUSTOMER_ID"))
                .columns(List.of(
                        col("CUSTOMER_ID", "BIGINT", false, true),
                        col("FIRST_NAME",  "VARCHAR", false, false),
                        col("LAST_NAME",   "VARCHAR", false, false),
                        col("EMAIL",       "VARCHAR", false, false)
                ))
                .foreignKeys(List.of())
                .uniqueConstraints(List.of())
                .checkConstraints(List.of())
                .build();
    }

    private TableMetadata buildOrdersTable() {
        return TableMetadata.builder()
                .schemaName("PUBLIC").tableName("ORDERS").tableType("TABLE")
                .primaryKeyColumns(List.of("ORDER_ID"))
                .columns(List.of(
                        col("ORDER_ID",     "BIGINT",   false, true),
                        col("CUSTOMER_ID",  "BIGINT",   false, false),
                        col("STATUS",       "VARCHAR",  false, false),
                        col("TOTAL_AMOUNT", "DECIMAL",  true,  false)
                ))
                .foreignKeys(List.of(
                        ForeignKeyMetadata.builder()
                                .constraintName("FK_ORDERS_CUSTOMER")
                                .fkTableSchema("PUBLIC").fkTableName("ORDERS").fkColumnName("CUSTOMER_ID")
                                .pkTableSchema("PUBLIC").pkTableName("CUSTOMERS").pkColumnName("CUSTOMER_ID")
                                .updateRule("NO_ACTION").deleteRule("NO_ACTION").build()
                ))
                .uniqueConstraints(List.of())
                .checkConstraints(List.of())
                .build();
    }

    private ColumnMetadata col(String name, String type, boolean nullable, boolean pk) {
        return ColumnMetadata.builder()
                .columnName(name).dataType(type).nullable(nullable)
                .primaryKey(pk).ordinalPosition(1).build();
    }
}
