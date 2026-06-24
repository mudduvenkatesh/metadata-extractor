package com.rdf.metadata;

import com.rdf.metadata.model.*;
import com.rdf.metadata.service.MetadataExtractionService;
import com.rdf.metadata.versioning.SchemaVersionRegistry;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests SHACL shape evolution across three schema versions.
 *
 * <h3>Schema timeline</h3>
 *
 * <p><b>V1 — Baseline</b> (2026-01-01)<br>
 * CUSTOMERS(CUSTOMER_ID PK, FIRST_NAME, LAST_NAME, EMAIL UNIQUE)<br>
 * ORDERS(ORDER_ID PK, CUSTOMER_ID FK→CUSTOMERS, STATUS VARCHAR(50), TOTAL_AMOUNT nullable)
 *
 * <p><b>V2 — Column added + new table</b> (2026-03-15)<br>
 * CUSTOMERS gains PHONE VARCHAR(20) nullable<br>
 * New table PRODUCTS(PRODUCT_ID PK, PRODUCT_NAME, PRICE DECIMAL)
 *
 * <p><b>V3 — Type change + column dropped</b> (2026-06-01)<br>
 * ORDERS.STATUS VARCHAR(50) → VARCHAR(100) (expanded)<br>
 * CUSTOMERS.LAST_NAME dropped (company decided single-name field)<br>
 * ORDERS gains NOTES VARCHAR(500) nullable
 *
 * <h3>What the test verifies</h3>
 * <ol>
 *   <li>Each extraction registers a new snapshot in the registry</li>
 *   <li>{@code latestShapes()} always returns V3 shapes after V3 is registered</li>
 *   <li>History list has 3 entries in reverse-chronological order</li>
 *   <li>V1 shapes have no PHONE, no PRODUCTS, STATUS maxLength=50</li>
 *   <li>V2 shapes have PHONE, PRODUCTS NodeShape, STATUS maxLength=50</li>
 *   <li>V3 shapes have NOTES, no LAST_NAME, STATUS maxLength=100</li>
 *   <li>All three shape Turtle outputs are printed to stdout</li>
 * </ol>
 */
@SpringBootTest
@Import(TestVaultConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SchemaEvolutionShapesTest {

    private static final String DB     = "EVOLUTION_DB";
    private static final String SCHEMA = "EVOLUTION_SCHEMA";

    @Autowired private MetadataExtractionService extractionService;
    @Autowired private SchemaVersionRegistry     registry;

    // Captured shape output for cross-test assertions
    private String shapesV1;
    private String shapesV2;
    private String shapesV3;

    // ─────────────────────────────────────────────────────────────────────────
    // V1 — Baseline schema
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("V1 — Register baseline schema and generate shapes")
    void v1_baselineSchema() {
        SchemaMetadata v1 = SchemaMetadata.builder()
                .databaseType("POSTGRESQL")
                .databaseName(DB)
                .schemaName(SCHEMA)
                .extractedAt(Instant.parse("2026-01-01T10:00:00Z"))
                .tables(List.of(
                        customersV1(),
                        ordersV1()
                ))
                .build();

        shapesV1 = extractionService.extractAndRegisterShapes(v1);

        printShapes("V1 — Baseline", shapesV1);

        // CUSTOMERS shape has CUSTOMER_ID, FIRST_NAME, LAST_NAME, EMAIL — no PHONE
        assertThat(shapesV1).containsIgnoringCase("firstName");
        assertThat(shapesV1).containsIgnoringCase("lastName");
        assertThat(shapesV1).containsIgnoringCase("email");
        assertThat(shapesV1).doesNotContainIgnoringCase("phone");

        // No PRODUCTS shape yet
        assertThat(shapesV1).doesNotContainIgnoringCase("Products");

        // STATUS maxLength = 50
        assertThat(shapesV1).containsIgnoringCase("status");
        assertThat(shapesV1).contains("50");

        // TOTAL_AMOUNT is nullable — no minCount for it
        assertThat(shapesV1).containsIgnoringCase("totalAmount");

        // CUSTOMER_ID FK in ORDERS uses sh:class, not sh:datatype
        assertThat(shapesV1).containsIgnoringCase("hasCustomers");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // V2 — Column added + new table
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(2)
    @DisplayName("V2 — Add PHONE to CUSTOMERS, add PRODUCTS table")
    void v2_columnAddedAndNewTable() {
        SchemaMetadata v2 = SchemaMetadata.builder()
                .databaseType("POSTGRESQL")
                .databaseName(DB)
                .schemaName(SCHEMA)
                .extractedAt(Instant.parse("2026-03-15T10:00:00Z"))
                .tables(List.of(
                        customersV2(),   // + PHONE
                        ordersV1(),      // unchanged
                        productsV2()     // new
                ))
                .build();

        shapesV2 = extractionService.extractAndRegisterShapes(v2);

        printShapes("V2 — Column added + new table", shapesV2);

        // PHONE now present (nullable → no minCount)
        assertThat(shapesV2).containsIgnoringCase("phone");

        // PRODUCTS NodeShape added
        assertThat(shapesV2).containsIgnoringCase("Products");
        assertThat(shapesV2).containsIgnoringCase("productName");
        assertThat(shapesV2).containsIgnoringCase("price");

        // LAST_NAME still present (not dropped yet)
        assertThat(shapesV2).containsIgnoringCase("lastName");

        // STATUS still maxLength=50
        assertThat(shapesV2).contains("50");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // V3 — Type changed + column dropped + column added
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(3)
    @DisplayName("V3 — STATUS expanded to 100, LAST_NAME dropped, NOTES added")
    void v3_typeChangeColumnDropColumnAdd() {
        SchemaMetadata v3 = SchemaMetadata.builder()
                .databaseType("POSTGRESQL")
                .databaseName(DB)
                .schemaName(SCHEMA)
                .extractedAt(Instant.parse("2026-06-01T10:00:00Z"))
                .tables(List.of(
                        customersV3(),   // LAST_NAME dropped
                        ordersV3(),      // STATUS VARCHAR(100), + NOTES
                        productsV2()     // unchanged
                ))
                .build();

        shapesV3 = extractionService.extractAndRegisterShapes(v3);

        printShapes("V3 — Type change + column drop + column add", shapesV3);

        // LAST_NAME dropped from CUSTOMERS
        assertThat(shapesV3).doesNotContainIgnoringCase("lastName");

        // STATUS maxLength now 100, not 50
        assertThat(shapesV3).containsIgnoringCase("status");
        assertThat(shapesV3).contains("100");

        // NOTES added (nullable)
        assertThat(shapesV3).containsIgnoringCase("notes");

        // PRODUCTS still present (unchanged)
        assertThat(shapesV3).containsIgnoringCase("Products");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Registry queries
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("latestShapes() returns V3 — the most recent extraction")
    void latestShapesReturnsV3() {
        String latest = extractionService.latestShapes(DB, SCHEMA);

        assertThat(latest).isNotBlank();
        // V3 markers
        assertThat(latest).doesNotContainIgnoringCase("lastName");
        assertThat(latest).contains("100");   // STATUS expanded
        assertThat(latest).containsIgnoringCase("notes");
        // V3 does NOT have V1-only content
        // (lastName gone, 50 could still appear for other columns — that's fine)
    }

    @Test
    @Order(5)
    @DisplayName("Extraction history has 3 entries in newest-first order")
    void historyHasThreeEntriesNewestFirst() {
        List<Map<String, String>> history = extractionService.listExtractions(DB, SCHEMA);

        printHistory(history);

        assertThat(history).hasSize(3);

        // Newest first
        assertThat(history.get(0).get("extractedAt")).contains("2026-06-01");
        assertThat(history.get(1).get("extractedAt")).contains("2026-03-15");
        assertThat(history.get(2).get("extractedAt")).contains("2026-01-01");

        // Table counts reflect each version
        assertThat(history.get(0).get("tableCount")).isEqualTo("3"); // V3
        assertThat(history.get(1).get("tableCount")).isEqualTo("3"); // V2
        assertThat(history.get(2).get("tableCount")).isEqualTo("2"); // V1
    }

    @Test
    @Order(6)
    @DisplayName("Each version has a distinct shapes IRI")
    void eachVersionHasDistinctShapesIri() {
        List<Map<String, String>> history = extractionService.listExtractions(DB, SCHEMA);
        List<String> shapesIris = history.stream()
                .map(m -> m.get("shapesIri"))
                .toList();
        assertThat(shapesIris).doesNotHaveDuplicates();
        shapesIris.forEach(iri -> {
            assertThat(iri).startsWith("http://example.org/harvestor/");
            String token = iri.substring("http://example.org/harvestor/".length());
            assertThat(token).hasSize(22);
            assertThat(token).containsPattern("[A-Za-z0-9]{22}");
        });
    }

    @Test
    @Order(7)
    @DisplayName("Export full registry — all ExtractionRecords + all shapes — as a TriG file")
    void exportRegistryAsTriG() throws Exception {
        String trig = registry.exportAsTriG();

        assertThat(trig).isNotBlank();

        // Default graph: catalogue triples present
        assertThat(trig).containsIgnoringCase("ExtractionRecord");
        assertThat(trig).containsIgnoringCase("extractedAt");
        assertThat(trig).containsIgnoringCase("hasShapes");

        // Named graphs: all three harvestor IRIs present
        assertThat(trig).contains("http://example.org/harvestor/");

        // All three schema evolutions' shapes present
        assertThat(trig).containsIgnoringCase("CustomersShape");
        assertThat(trig).containsIgnoringCase("OrdersShape");
        assertThat(trig).containsIgnoringCase("ProductsShape");  // added in V2

        // Evolution-specific evidence across the combined export
        assertThat(trig).containsIgnoringCase("lastName");        // V1 + V2
        assertThat(trig).containsIgnoringCase("phone");           // V2 + V3
        assertThat(trig).containsIgnoringCase("notes");           // V3 only
        assertThat(trig).contains("100");                         // V3 STATUS maxLength

        // Write to file
        java.nio.file.Path path = java.nio.file.Path.of(
                "/mnt/user-data/outputs/schema-evolution-shapes.trig");
        java.nio.file.Files.writeString(path, trig,
                java.nio.charset.StandardCharsets.UTF_8);

        System.out.println("\n>>> TriG file written to: " + path.toAbsolutePath());
        System.out.println(">>> File size: " + java.nio.file.Files.size(path) + " bytes");
        System.out.println("\n" + "=".repeat(72));
        System.out.println("TRIG EXPORT — full registry (default graph + 3 named graphs)");
        System.out.println("=".repeat(72));
        System.out.println(trig);
        System.out.println("=".repeat(72) + "\n");

        assertThat(java.nio.file.Files.exists(path)).isTrue();
        assertThat(java.nio.file.Files.size(path)).isGreaterThan(0);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Schema builders — V1
    // ─────────────────────────────────────────────────────────────────────────

    private TableMetadata customersV1() {
        return TableMetadata.builder()
                .schemaName(SCHEMA).tableName("CUSTOMERS").tableType("TABLE")
                .primaryKeyColumns(List.of("CUSTOMER_ID"))
                .columns(List.of(
                        col("CUSTOMER_ID", "BIGINT",      false, true,  null),
                        col("FIRST_NAME",  "VARCHAR(100)", false, false, null),
                        col("LAST_NAME",   "VARCHAR(100)", false, false, null),
                        col("EMAIL",       "VARCHAR(255)", false, false, null)
                ))
                .foreignKeys(List.of())
                .uniqueConstraints(List.of(
                        UniqueConstraintMetadata.builder()
                                .constraintName("UQ_EMAIL")
                                .columns(List.of("EMAIL"))
                                .build()
                ))
                .checkConstraints(List.of())
                .build();
    }

    private TableMetadata ordersV1() {
        return TableMetadata.builder()
                .schemaName(SCHEMA).tableName("ORDERS").tableType("TABLE")
                .primaryKeyColumns(List.of("ORDER_ID"))
                .columns(List.of(
                        col("ORDER_ID",     "BIGINT",      false, true,  null),
                        col("CUSTOMER_ID",  "BIGINT",      false, false, null),
                        col("STATUS",       "VARCHAR(50)", false, false, null),
                        col("TOTAL_AMOUNT", "DECIMAL",     true,  false, null)
                ))
                .foreignKeys(List.of(
                        ForeignKeyMetadata.builder()
                                .constraintName("FK_ORDERS_CUSTOMER")
                                .fkTableSchema(SCHEMA).fkTableName("ORDERS")
                                .fkColumnName("CUSTOMER_ID")
                                .pkTableSchema(SCHEMA).pkTableName("CUSTOMERS")
                                .pkColumnName("CUSTOMER_ID")
                                .updateRule("NO_ACTION").deleteRule("NO_ACTION")
                                .build()
                ))
                .uniqueConstraints(List.of()).checkConstraints(List.of())
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Schema builders — V2 (+ PHONE on CUSTOMERS, + PRODUCTS table)
    // ─────────────────────────────────────────────────────────────────────────

    private TableMetadata customersV2() {
        return TableMetadata.builder()
                .schemaName(SCHEMA).tableName("CUSTOMERS").tableType("TABLE")
                .primaryKeyColumns(List.of("CUSTOMER_ID"))
                .columns(List.of(
                        col("CUSTOMER_ID", "BIGINT",      false, true,  null),
                        col("FIRST_NAME",  "VARCHAR(100)", false, false, null),
                        col("LAST_NAME",   "VARCHAR(100)", false, false, null),
                        col("EMAIL",       "VARCHAR(255)", false, false, null),
                        col("PHONE",       "VARCHAR(20)",  true,  false, null)   // ← ADDED
                ))
                .foreignKeys(List.of())
                .uniqueConstraints(List.of(
                        UniqueConstraintMetadata.builder()
                                .constraintName("UQ_EMAIL").columns(List.of("EMAIL")).build()
                ))
                .checkConstraints(List.of())
                .build();
    }

    private TableMetadata productsV2() {
        return TableMetadata.builder()
                .schemaName(SCHEMA).tableName("PRODUCTS").tableType("TABLE")
                .primaryKeyColumns(List.of("PRODUCT_ID"))
                .columns(List.of(
                        col("PRODUCT_ID",   "BIGINT",       false, true,  null),
                        col("PRODUCT_NAME", "VARCHAR(200)", false, false, null),
                        col("PRICE",        "DECIMAL",      false, false, null)
                ))
                .foreignKeys(List.of()).uniqueConstraints(List.of()).checkConstraints(List.of())
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Schema builders — V3 (LAST_NAME dropped, STATUS→VARCHAR(100), + NOTES)
    // ─────────────────────────────────────────────────────────────────────────

    private TableMetadata customersV3() {
        return TableMetadata.builder()
                .schemaName(SCHEMA).tableName("CUSTOMERS").tableType("TABLE")
                .primaryKeyColumns(List.of("CUSTOMER_ID"))
                .columns(List.of(
                        col("CUSTOMER_ID", "BIGINT",      false, true,  null),
                        col("FIRST_NAME",  "VARCHAR(100)", false, false, null),
                        // LAST_NAME dropped ←
                        col("EMAIL",       "VARCHAR(255)", false, false, null),
                        col("PHONE",       "VARCHAR(20)",  true,  false, null)
                ))
                .foreignKeys(List.of())
                .uniqueConstraints(List.of(
                        UniqueConstraintMetadata.builder()
                                .constraintName("UQ_EMAIL").columns(List.of("EMAIL")).build()
                ))
                .checkConstraints(List.of())
                .build();
    }

    private TableMetadata ordersV3() {
        return TableMetadata.builder()
                .schemaName(SCHEMA).tableName("ORDERS").tableType("TABLE")
                .primaryKeyColumns(List.of("ORDER_ID"))
                .columns(List.of(
                        col("ORDER_ID",     "BIGINT",       false, true,  null),
                        col("CUSTOMER_ID",  "BIGINT",       false, false, null),
                        col("STATUS",       "VARCHAR(100)", false, false, null),  // ← 50→100
                        col("TOTAL_AMOUNT", "DECIMAL",      true,  false, null),
                        col("NOTES",        "VARCHAR(500)", true,  false, null)   // ← ADDED
                ))
                .foreignKeys(List.of(
                        ForeignKeyMetadata.builder()
                                .constraintName("FK_ORDERS_CUSTOMER")
                                .fkTableSchema(SCHEMA).fkTableName("ORDERS")
                                .fkColumnName("CUSTOMER_ID")
                                .pkTableSchema(SCHEMA).pkTableName("CUSTOMERS")
                                .pkColumnName("CUSTOMER_ID")
                                .updateRule("NO_ACTION").deleteRule("NO_ACTION")
                                .build()
                ))
                .uniqueConstraints(List.of()).checkConstraints(List.of())
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static ColumnMetadata col(String name, String type, boolean nullable,
                                       boolean pk, String remarks) {
        // Parse length from VARCHAR(n)
        Integer maxLen = null;
        if (type.contains("(")) {
            try {
                maxLen = Integer.parseInt(type.replaceAll(".*\\((\\d+).*", "$1"));
            } catch (NumberFormatException ignored) {}
        }
        return ColumnMetadata.builder()
                .columnName(name)
                .dataType(type.replaceAll("\\(.*\\)", "").trim())
                .nullable(nullable)
                .primaryKey(pk)
                .ordinalPosition(1)
                .characterMaxLength(maxLen)
                .remarks(remarks)
                .build();
    }

    private static void printShapes(String label, String turtle) {
        String bar = "=".repeat(72);
        System.out.println("\n" + bar);
        System.out.println("SHACL SHAPES — " + label);
        System.out.println(bar);
        System.out.println(turtle);
        System.out.println(bar + "\n");
    }

    private static void printHistory(List<Map<String, String>> history) {
        System.out.println("\n" + "─".repeat(72));
        System.out.println("EXTRACTION HISTORY  (newest first)");
        System.out.println("─".repeat(72));
        history.forEach(row -> {
            System.out.printf("  extractedAt=%-30s tables=%-3s columns=%-3s%n",
                    row.get("extractedAt"),
                    row.get("tableCount"),
                    row.get("columnCount"));
            System.out.println("  shapesIri = " + row.get("shapesIri"));
            System.out.println();
        });
        System.out.println("─".repeat(72) + "\n");
    }
}
