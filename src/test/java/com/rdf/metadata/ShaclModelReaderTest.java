package com.rdf.metadata;

import com.rdf.metadata.config.RdfProperties;
import com.rdf.metadata.model.*;
import com.rdf.metadata.shacl.ShaclShapesBuilder;
import com.rdf.metadata.sql.ShaclModelReader;
import com.rdf.metadata.sql.ShapeColumn;
import com.rdf.metadata.sql.ShapeTable;
import org.eclipse.rdf4j.model.Model;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test between {@link ShaclShapesBuilder} and {@link ShaclModelReader}.
 *
 * <p>Builds a real SHACL {@link Model} from hand-crafted {@link SchemaMetadata},
 * then reads it back through {@link ShaclModelReader} and asserts the round-trip
 * is lossless for all column properties.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShaclModelReaderTest {

    private ShaclShapesBuilder shaclShapesBuilder;
    private ShaclModelReader   shaclModelReader;
    private List<ShapeTable>   tables;

    @BeforeAll
    void buildAndReadModel() {
        RdfProperties props = new RdfProperties();
        props.setBaseNamespace("http://example.org/schema#");

        shaclShapesBuilder = new ShaclShapesBuilder(props);
        shaclModelReader   = new ShaclModelReader();

        // Build SchemaMetadata with ORDERS and CUSTOMERS tables
        SchemaMetadata schema = SchemaMetadata.builder()
                .databaseType("POSTGRESQL")
                .databaseName("testdb")
                .schemaName("public")
                .tables(List.of(buildCustomersTable(), buildOrdersTable()))
                .build();

        Model model = shaclShapesBuilder.build(schema);
        tables = shaclModelReader.readTables(model);
    }

    // ─── Table discovery ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Reads two NodeShapes — one per table")
    void shouldReadTwoTables() {
        assertThat(tables).hasSize(2);
    }

    @Test
    @DisplayName("Table names match the original schema")
    void shouldHaveCorrectTableNames() {
        List<String> names = tables.stream().map(ShapeTable::getTableName).toList();
        assertThat(names).containsExactlyInAnyOrder("Customers", "Orders");
    }

    // ─── Customers table ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Customers: columns are present")
    void customersHasCorrectColumns() {
        ShapeTable customers = tableByName("Customers");
        List<String> colNames = customers.getColumns().stream()
                .map(ShapeColumn::getColumnName).toList();
        assertThat(colNames).containsExactlyInAnyOrder(
                "CUSTOMER_ID", "FIRST_NAME", "LAST_NAME", "EMAIL");
    }

    @Test
    @DisplayName("Customers: CUSTOMER_ID is NOT NULL")
    void customersIdIsNotNullable() {
        ShapeColumn id = columnOf("Customers", "CUSTOMER_ID");
        assertThat(id.isNullable()).isFalse();
    }

    @Test
    @DisplayName("Customers: FIRST_NAME has maxLength 100")
    void customersFirstNameHasMaxLength() {
        ShapeColumn firstName = columnOf("Customers", "FIRST_NAME");
        assertThat(firstName.getMaxLength()).isEqualTo(100);
    }

    // ─── Orders table ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Orders: CUSTOMER_ID is detected as FK column")
    void ordersFkColumnDetected() {
        ShapeColumn fkCol = columnOf("Orders", "CUSTOMER_ID");
        assertThat(fkCol.isForeignKey()).isTrue();
        assertThat(fkCol.getReferencedTable()).isEqualTo("Customers");
    }

    @Test
    @DisplayName("Orders: TOTAL_AMOUNT is nullable")
    void ordersTotalAmountIsNullable() {
        ShapeColumn total = columnOf("Orders", "TOTAL_AMOUNT");
        assertThat(total.isNullable()).isTrue();
    }

    // ─── localName / camelToUpperSnake utilities ──────────────────────────────

    @Test
    @DisplayName("camelToUpperSnake converts correctly")
    void camelToUpperSnake() {
        assertThat(ShaclModelReader.camelToUpperSnake("orderId")).isEqualTo("ORDER_ID");
        assertThat(ShaclModelReader.camelToUpperSnake("firstName")).isEqualTo("FIRST_NAME");
        assertThat(ShaclModelReader.camelToUpperSnake("totalAmount")).isEqualTo("TOTAL_AMOUNT");
        assertThat(ShaclModelReader.camelToUpperSnake("status")).isEqualTo("STATUS");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private ShapeTable tableByName(String name) {
        return tables.stream()
                .filter(t -> t.getTableName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Table not found: " + name));
    }

    private ShapeColumn columnOf(String tableName, String columnName) {
        return tableByName(tableName).getColumns().stream()
                .filter(c -> c.getColumnName().equals(columnName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Column " + columnName + " not found in " + tableName));
    }

    private TableMetadata buildCustomersTable() {
        return TableMetadata.builder()
                .schemaName("public")
                .tableName("CUSTOMERS")
                .tableType("TABLE")
                .primaryKeyColumns(List.of("CUSTOMER_ID"))
                .columns(List.of(
                        ColumnMetadata.builder().columnName("CUSTOMER_ID")
                                .dataType("BIGINT").nullable(false).ordinalPosition(1).build(),
                        ColumnMetadata.builder().columnName("FIRST_NAME")
                                .dataType("VARCHAR").nullable(false).ordinalPosition(2)
                                .characterMaxLength(100).build(),
                        ColumnMetadata.builder().columnName("LAST_NAME")
                                .dataType("VARCHAR").nullable(false).ordinalPosition(3)
                                .characterMaxLength(100).build(),
                        ColumnMetadata.builder().columnName("EMAIL")
                                .dataType("VARCHAR").nullable(false).ordinalPosition(4)
                                .characterMaxLength(255).build()
                ))
                .foreignKeys(List.of())
                .uniqueConstraints(List.of())
                .checkConstraints(List.of())
                .build();
    }

    private TableMetadata buildOrdersTable() {
        return TableMetadata.builder()
                .schemaName("public")
                .tableName("ORDERS")
                .tableType("TABLE")
                .primaryKeyColumns(List.of("ORDER_ID"))
                .columns(List.of(
                        ColumnMetadata.builder().columnName("ORDER_ID")
                                .dataType("BIGINT").nullable(false).ordinalPosition(1).build(),
                        ColumnMetadata.builder().columnName("CUSTOMER_ID")
                                .dataType("BIGINT").nullable(false).ordinalPosition(2).build(),
                        ColumnMetadata.builder().columnName("STATUS")
                                .dataType("VARCHAR").nullable(false).ordinalPosition(3)
                                .characterMaxLength(50).build(),
                        ColumnMetadata.builder().columnName("TOTAL_AMOUNT")
                                .dataType("DECIMAL").nullable(true).ordinalPosition(4).build()
                ))
                .foreignKeys(List.of(
                        ForeignKeyMetadata.builder()
                                .constraintName("FK_ORDERS_CUSTOMER")
                                .fkTableSchema("public").fkTableName("ORDERS")
                                .fkColumnName("CUSTOMER_ID")
                                .pkTableSchema("public").pkTableName("CUSTOMERS")
                                .pkColumnName("CUSTOMER_ID")
                                .updateRule("NO_ACTION").deleteRule("NO_ACTION")
                                .build()
                ))
                .uniqueConstraints(List.of())
                .checkConstraints(List.of())
                .build();
    }
}
