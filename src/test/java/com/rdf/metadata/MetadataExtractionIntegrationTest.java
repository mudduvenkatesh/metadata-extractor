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

    // ─── Metadata Extraction ─────────────────────────────────────────────────

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

    // ─── RDF4J OWL Generation ────────────────────────────────────────────────

    @Test
    @DisplayName("Should generate OWL ontology Turtle containing owl:Class declarations")
    void shouldGenerateOwlOntology() {
        ExtractionResult result = extractionService.extract(buildRequest(true, false));

        assertThat(result.getOntologyRdf()).isNotBlank();
        // RDF4J Rio Turtle output uses prefix abbreviations
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

    // ─── RDF4J SHACL Generation ──────────────────────────────────────────────

    @Test
    @DisplayName("Should generate SHACL shapes Turtle containing sh:NodeShape")
    void shouldGenerateShaclNodeShapes() {
        ExtractionResult result = extractionService.extract(buildRequest(false, true));

        assertThat(result.getShaclRdf()).isNotBlank();
        assertThat(result.getShaclRdf()).containsAnyOf("sh:NodeShape", "shacl#NodeShape");
        assertThat(result.getShaclShapesGenerated()).isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("Should generate SHACL Turtle with sh:PropertyShape entries")
    void shouldGenerateShaclPropertyShapes() {
        ExtractionResult result = extractionService.extract(buildRequest(false, true));

        assertThat(result.getShaclRdf()).containsAnyOf("sh:PropertyShape", "shacl#PropertyShape");
        assertThat(result.getShaclRdf()).containsAnyOf("sh:minCount", "shacl#minCount");
    }

    // ─── Combined ────────────────────────────────────────────────────────────

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
        assertThat(result.getOntologyRdf()).contains("@context");
    }

    // ─── Helper ──────────────────────────────────────────────────────────────

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
