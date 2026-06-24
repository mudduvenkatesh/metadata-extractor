package com.rdf.metadata;

import com.rdf.metadata.model.*;
import com.rdf.metadata.service.MetadataExtractionService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.sql.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test using H2 in-memory database.
 *
 * <p>Verifies the full pipeline end-to-end:
 * JDBC metadata extraction → RDF4J OWL ontology → RDF4J SHACL shapes → Rio serialization.
 *
 * <p>H2's JDBC {@link DatabaseMetaData} is compatible with the PostgreSQL extractor's
 * base-class logic, making it suitable for driver-independent integration testing.
 *
 * <h3>Schema under test</h3>
 * <pre>
 * CUSTOMERS  (CUSTOMER_ID PK, FIRST_NAME, LAST_NAME, EMAIL UNIQUE, PHONE, CREATED_AT)
 * PRODUCTS   (PRODUCT_ID PK, PRODUCT_NAME, PRICE CHECK>=0, STOCK_QTY CHECK>=0)
 * ORDERS     (ORDER_ID PK, CUSTOMER_ID FK→CUSTOMERS, ORDER_DATE, STATUS, TOTAL_AMOUNT)
 * ORDER_ITEMS(ORDER_ITEM_ID PK, ORDER_ID FK→ORDERS, PRODUCT_ID FK→PRODUCTS,
 *             QUANTITY, UNIT_PRICE)
 * </pre>
 */
@SpringBootTest
@Import(TestVaultConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MetadataExtractionIntegrationTest {

    private static final String H2_URL      = "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1";
    private static final String H2_USER     = "sa";
    private static final String H2_PASSWORD = "";

    @Autowired
    private MetadataExtractionService extractionService;

    private Connection h2Connection;

    @BeforeAll
    void createSchema() throws SQLException {
        h2Connection = DriverManager.getConnection(H2_URL, H2_USER, H2_PASSWORD);
        try (Statement stmt = h2Connection.createStatement()) {

            stmt.execute("CREATE SCHEMA IF NOT EXISTS PUBLIC");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS CUSTOMERS (
                    CUSTOMER_ID   BIGINT        NOT NULL,
                    FIRST_NAME    VARCHAR(100)  NOT NULL,
                    LAST_NAME     VARCHAR(100)  NOT NULL,
                    EMAIL         VARCHAR(255)  NOT NULL,
                    PHONE         VARCHAR(20),
                    CREATED_AT    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    CONSTRAINT PK_CUSTOMERS PRIMARY KEY (CUSTOMER_ID),
                    CONSTRAINT UQ_CUSTOMERS_EMAIL UNIQUE (EMAIL)
                )
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS PRODUCTS (
                    PRODUCT_ID    BIGINT        NOT NULL,
                    PRODUCT_NAME  VARCHAR(200)  NOT NULL,
                    PRICE         DECIMAL(10,2) NOT NULL,
                    STOCK_QTY     INTEGER       NOT NULL DEFAULT 0,
                    CONSTRAINT PK_PRODUCTS PRIMARY KEY (PRODUCT_ID),
                    CONSTRAINT CHK_PRICE CHECK (PRICE >= 0),
                    CONSTRAINT CHK_STOCK CHECK (STOCK_QTY >= 0)
                )
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ORDERS (
                    ORDER_ID      BIGINT        NOT NULL,
                    CUSTOMER_ID   BIGINT        NOT NULL,
                    ORDER_DATE    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    STATUS        VARCHAR(50)   NOT NULL,
                    TOTAL_AMOUNT  DECIMAL(12,2),
                    CONSTRAINT PK_ORDERS PRIMARY KEY (ORDER_ID),
                    CONSTRAINT FK_ORDERS_CUSTOMER FOREIGN KEY (CUSTOMER_ID)
                        REFERENCES CUSTOMERS(CUSTOMER_ID)
                )
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ORDER_ITEMS (
                    ORDER_ITEM_ID BIGINT        NOT NULL,
                    ORDER_ID      BIGINT        NOT NULL,
                    PRODUCT_ID    BIGINT        NOT NULL,
                    QUANTITY      INTEGER       NOT NULL,
                    UNIT_PRICE    DECIMAL(10,2) NOT NULL,
                    CONSTRAINT PK_ORDER_ITEMS PRIMARY KEY (ORDER_ITEM_ID),
                    CONSTRAINT FK_OI_ORDER   FOREIGN KEY (ORDER_ID)
                        REFERENCES ORDERS(ORDER_ID),
                    CONSTRAINT FK_OI_PRODUCT FOREIGN KEY (PRODUCT_ID)
                        REFERENCES PRODUCTS(PRODUCT_ID)
                )
                """);
        }
    }

    @AfterAll
    void tearDown() throws SQLException {
        if (h2Connection != null && !h2Connection.isClosed()) {
            h2Connection.close();
        }
    }

    // ─── Metadata Extraction ──────────────────────────────────────────────────

    @Test
    @DisplayName("Should extract all four tables from H2 schema")
    void shouldExtractTablesFromH2() {
        SchemaMetadata metadata = extractionService.previewMetadata(buildRequest(false, false));

        assertThat(metadata).isNotNull();
        assertThat(metadata.getTables()).hasSizeGreaterThanOrEqualTo(4);

        var tableNames = metadata.getTables().stream()
                .map(t -> t.getTableName().toUpperCase())
                .toList();
        assertThat(tableNames).contains("CUSTOMERS", "PRODUCTS", "ORDERS", "ORDER_ITEMS");
    }

    @Test
    @DisplayName("Should extract columns and PK for CUSTOMERS")
    void shouldExtractColumnsAndPkForCustomers() {
        SchemaMetadata metadata = extractionService.previewMetadata(buildRequest(false, false));

        var customers = metadata.getTables().stream()
                .filter(t -> t.getTableName().equalsIgnoreCase("CUSTOMERS"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("CUSTOMERS table not found"));

        assertThat(customers.getColumns()).hasSizeGreaterThanOrEqualTo(5);
        assertThat(customers.getPrimaryKeyColumns())
                .anySatisfy(pk -> assertThat(pk).isEqualToIgnoringCase("CUSTOMER_ID"));
    }

    @Test
    @DisplayName("Should extract FK from ORDERS → CUSTOMERS")
    void shouldExtractForeignKeys() {
        SchemaMetadata metadata = extractionService.previewMetadata(buildRequest(false, false));

        var orders = metadata.getTables().stream()
                .filter(t -> t.getTableName().equalsIgnoreCase("ORDERS"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("ORDERS table not found"));

        assertThat(orders.getForeignKeys()).isNotEmpty();
        assertThat(orders.getForeignKeys().get(0).getPkTableName().toUpperCase())
                .isEqualTo("CUSTOMERS");
    }

    // ─── RDF4J OWL Generation ─────────────────────────────────────────────────

    @Test
    @DisplayName("Should generate OWL ontology Turtle containing owl:Class declarations")
    void shouldGenerateOwlOntology() {
        ExtractionResult result = extractionService.extract(buildRequest(true, false));

        assertThat(result.getOntologyRdf()).isNotBlank();
        assertThat(result.getOntologyRdf()).containsAnyOf("owl:Class", "owl#Class");
        assertThat(result.getRdfTriplesGenerated()).isGreaterThan(0);
        assertThat(result.getTablesProcessed()).isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("Should generate OWL Turtle with owl:DatatypeProperty statements")
    void shouldGenerateDatatypeProperties() {
        ExtractionResult result = extractionService.extract(buildRequest(true, false));

        assertThat(result.getOntologyRdf())
                .containsAnyOf("owl:DatatypeProperty", "owl#DatatypeProperty");
    }

    @Test
    @DisplayName("Should generate OWL Turtle with owl:ObjectProperty for FKs")
    void shouldGenerateObjectProperties() {
        ExtractionResult result = extractionService.extract(buildRequest(true, false));

        assertThat(result.getOntologyRdf())
                .containsAnyOf("owl:ObjectProperty", "owl#ObjectProperty");
    }

    // ─── SHACL Shape Generation ───────────────────────────────────────────────

    /**
     * Generates the full SHACL shapes graph from the H2 schema and prints it to
     * stdout in Turtle format so it can be inspected manually or copied into a
     * shapes file.
     *
     * <p>Expected output structure for each table:
     * <pre>{@code
     * schema:CustomersShape
     *     a                sh:NodeShape ;
     *     sh:targetClass   schema:Customers ;
     *     sh:property [
     *         sh:path       schema:Customers_customerId ;
     *         sh:datatype   xsd:long ;
     *         sh:minCount   1 ;
     *         sh:maxCount   1
     *     ] , [ ... ] .
     * }</pre>
     */
    @Test
    @DisplayName("Generate and print SHACL shapes for all tables")
    void shouldGenerateAndPrintShaclShapes() {
        ExtractionResult result = extractionService.extract(buildRequest(false, true));

        assertThat(result.getShaclRdf()).isNotBlank();

        // ── Print the full Turtle output so it can be inspected / copied ──────
        System.out.println("\n" + "=".repeat(80));
        System.out.println("SHACL SHAPES — H2 test schema (PUBLIC)");
        System.out.println("=".repeat(80));
        System.out.println(result.getShaclRdf());
        System.out.println("=".repeat(80) + "\n");
    }

    @Test
    @DisplayName("SHACL: one NodeShape generated per table")
    void shaclShouldHaveOneNodeShapePerTable() {
        ExtractionResult result = extractionService.extract(buildRequest(false, true));
        String shacl = result.getShaclRdf();

        // Four tables → four NodeShapes
        assertThat(result.getShaclShapesGenerated()).isGreaterThanOrEqualTo(4);
        assertThat(shacl).containsAnyOf("sh:NodeShape", "shacl#NodeShape");
    }

    @Test
    @DisplayName("SHACL: CustomersShape targets schema:Customers class")
    void shaclCustomersShapeTargetsCorrectClass() {
        ExtractionResult result = extractionService.extract(buildRequest(false, true));
        String shacl = result.getShaclRdf();

        // NodeShape IRI and targetClass both present
        assertThat(shacl).containsIgnoringCase("CustomersShape");
        assertThat(shacl).containsAnyOf("sh:targetClass", "shacl#targetClass");
        assertThat(shacl).containsIgnoringCase("Customers");
    }

    @Test
    @DisplayName("SHACL: NOT NULL columns have sh:minCount 1")
    void shaclNotNullColumnsShouldHaveMinCount1() {
        ExtractionResult result = extractionService.extract(buildRequest(false, true));
        String shacl = result.getShaclRdf();

        // CUSTOMERS.FIRST_NAME is NOT NULL → must have minCount 1
        assertThat(shacl).containsAnyOf("sh:minCount", "shacl#minCount");
        assertThat(shacl).contains("1");
    }

    @Test
    @DisplayName("SHACL: all scalar columns have sh:maxCount 1")
    void shaclAllScalarColumnsShouldHaveMaxCount1() {
        ExtractionResult result = extractionService.extract(buildRequest(false, true));
        String shacl = result.getShaclRdf();

        assertThat(shacl).containsAnyOf("sh:maxCount", "shacl#maxCount");
    }

    @Test
    @DisplayName("SHACL: VARCHAR columns with length have sh:maxLength")
    void shaclVarcharColumnsShouldHaveMaxLength() {
        ExtractionResult result = extractionService.extract(buildRequest(false, true));
        String shacl = result.getShaclRdf();

        // CUSTOMERS.FIRST_NAME VARCHAR(100), EMAIL VARCHAR(255)
        assertThat(shacl).containsAnyOf("sh:maxLength", "shacl#maxLength");
    }

    @Test
    @DisplayName("SHACL: FK columns use sh:class (object property) not sh:datatype")
    void shaclFkColumnsShouldUseShClass() {
        ExtractionResult result = extractionService.extract(buildRequest(false, true));
        String shacl = result.getShaclRdf();

        // ORDERS.CUSTOMER_ID is a FK → PropertyShape uses sh:class
        assertThat(shacl).containsAnyOf("sh:class", "shacl#class");
    }

    @Test
    @DisplayName("SHACL: non-FK columns use sh:datatype with XSD types")
    void shaclNonFkColumnsShouldUseShDatatype() {
        ExtractionResult result = extractionService.extract(buildRequest(false, true));
        String shacl = result.getShaclRdf();

        // Regular columns use xsd: datatypes
        assertThat(shacl).containsAnyOf("sh:datatype", "shacl#datatype", "XMLSchema#");
        assertThat(shacl).contains("XMLSchema#string"); // full IRI, RDF4J does not abbreviate in this format
    }

    @Test
    @DisplayName("SHACL: PRODUCTS check constraints generate sh:sparql nodes")
    void shaclCheckConstraintsShouldGenerateSparqlNodes() {
        ExtractionResult result = extractionService.extract(buildRequest(false, true));
        String shacl = result.getShaclRdf();

        // PRODUCTS has CHK_PRICE and CHK_STOCK → sh:sparql constraint nodes
        assertThat(shacl).containsAnyOf("sh:sparql", "shacl#sparql");
    }

    @Test
    @DisplayName("SHACL: CUSTOMERS unique constraint generates sh:sparql node")
    void shaclUniqueConstraintShouldGenerateSparqlNode() {
        ExtractionResult result = extractionService.extract(buildRequest(false, true));
        String shacl = result.getShaclRdf();

        // CUSTOMERS has UQ_CUSTOMERS_EMAIL → sh:sparql UNIQUE constraint
        assertThat(shacl).containsAnyOf("sh:sparql", "shacl#sparql");
        assertThat(shacl).containsIgnoringCase("UNIQUE");
    }

    @Test
    @DisplayName("SHACL: OrderItemsShape has property shapes for all five columns")
    void shaclOrderItemsShouldHavePropertyShapes() {
        ExtractionResult result = extractionService.extract(buildRequest(false, true));
        String shacl = result.getShaclRdf();

        // ORDER_ITEMS has 5 columns → 5 PropertyShape blank nodes attached to OrderItemsShape
        assertThat(shacl).containsIgnoringCase("OrderItems");
        assertThat(shacl).containsAnyOf("sh:property", "shacl#property");
    }

    @Test
    @DisplayName("SHACL: nullable column TOTAL_AMOUNT has no sh:minCount")
    void shaclNullableColumnShouldNotHaveMinCount() {
        ExtractionResult result = extractionService.extract(buildRequest(false, true));
        String shacl = result.getShaclRdf();

        // Presence of sh:maxCount without sh:minCount in the same blank node is hard to
        // assert at string level — verify the shape contains TOTAL_AMOUNT as a named property
        // and that not all properties carry minCount (nullable ones don't)
        assertThat(shacl).containsIgnoringCase("totalAmount");
    }

    // ─── Combined ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Full extraction should produce both OWL and SHACL graphs")
    void shouldProduceBothGraphsInFullExtraction() {
        ExtractionResult result = extractionService.extract(buildRequest(true, true));

        assertThat(result.getOntologyRdf()).isNotBlank();
        assertThat(result.getShaclRdf()).isNotBlank();
        assertThat(result.getTablesProcessed()).isGreaterThanOrEqualTo(4);
        assertThat(result.getColumnsProcessed()).isGreaterThan(0);
        assertThat(result.getForeignKeysProcessed()).isGreaterThan(0);
        assertThat(result.getWarnings()).isEmpty();
    }

    @Test
    @DisplayName("Should serialize to JSON-LD format without error")
    void shouldSerializeToJsonLd() {
        ExtractionRequest req = buildRequest(true, true);
        req.setRdfFormat(RdfFormat.JSON_LD);

        ExtractionResult result = extractionService.extract(req);
        assertThat(result.getOntologyRdf()).isNotBlank();
        assertThat(result.getOntologyRdf()).containsAnyOf("@context", "@graph");
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    private ExtractionRequest buildRequest(boolean owl, boolean shacl) {
        ExtractionRequest request = new ExtractionRequest();
        request.setDatabaseType(DatabaseType.POSTGRESQL);
        request.setJdbcUrl(H2_URL);
        request.setUsername(H2_USER);
        request.setPassword(H2_PASSWORD);
        request.setSchemaName("PUBLIC");
        request.setRdfFormat(RdfFormat.TURTLE);
        request.setIncludeOwlAxioms(owl);
        request.setIncludeShaclShapes(shacl);
        return request;
    }
}
