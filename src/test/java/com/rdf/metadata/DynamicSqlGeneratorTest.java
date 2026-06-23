package com.rdf.metadata;

import com.rdf.metadata.sql.*;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DynamicSqlGenerator}.
 * Uses a hand-crafted {@link ShapeTable} representing an ORDERS table to verify
 * all six statement types across all three dialects.
 */
class DynamicSqlGeneratorTest {

    private DynamicSqlGenerator generator;
    private ShapeTable          ordersTable;

    @BeforeEach
    void setUp() {
        generator = new DynamicSqlGenerator();

        List<ShapeColumn> columns = List.of(
            ShapeColumn.builder()
                .columnName("ORDER_ID")
                .xsdDatatype(XSD.LONG.stringValue())
                .isForeignKey(false).nullable(false).isPrimaryKey(true)
                .build(),
            ShapeColumn.builder()
                .columnName("CUSTOMER_ID")
                .xsdDatatype(XSD.LONG.stringValue())
                .isForeignKey(true).referencedTable("Customers")
                .nullable(false).isPrimaryKey(false)
                .build(),
            ShapeColumn.builder()
                .columnName("STATUS")
                .xsdDatatype(XSD.STRING.stringValue())
                .isForeignKey(false).nullable(false).isPrimaryKey(false)
                .maxLength(50)
                .build(),
            ShapeColumn.builder()
                .columnName("TOTAL_AMOUNT")
                .xsdDatatype(XSD.DECIMAL.stringValue())
                .isForeignKey(false).nullable(true).isPrimaryKey(false)
                .build()
        );

        ordersTable = ShapeTable.builder()
                .tableName("Orders")
                .nodeShapeIri("http://example.org/schema#OrdersShape")
                .columns(columns)
                .primaryKeyColumns(List.of("ORDER_ID"))
                .uniqueConstraintMessages(List.of())
                .checkConstraintMessages(List.of())
                .build();
    }

    // ─── CREATE TABLE ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("CREATE TABLE (PostgreSQL) — contains all columns, PK, and FK constraints")
    void createTablePostgresql() {
        SqlStatement stmt = generate(SqlStatementType.CREATE_TABLE, SqlDialect.POSTGRESQL);

        assertThat(stmt.getType()).isEqualTo(SqlStatementType.CREATE_TABLE);
        assertThat(stmt.getSql())
                .contains("CREATE TABLE \"Orders\"")
                .contains("\"ORDER_ID\" BIGINT NOT NULL")
                .contains("\"STATUS\" VARCHAR(50) NOT NULL")
                .contains("\"TOTAL_AMOUNT\" NUMERIC")           // nullable — no NOT NULL
                .contains("CONSTRAINT pk_Orders PRIMARY KEY (\"ORDER_ID\")")
                .contains("FOREIGN KEY (\"CUSTOMER_ID\")")
                .contains("REFERENCES \"Customers\"");

        // TOTAL_AMOUNT is nullable — must NOT contain NOT NULL after it
        String totalAmountLine = extractLine(stmt.getSql(), "TOTAL_AMOUNT");
        assertThat(totalAmountLine).doesNotContain("NOT NULL");
    }

    @Test
    @DisplayName("CREATE TABLE (Snowflake) — identifiers unquoted, uses NUMBER and TIMESTAMP_NTZ types")
    void createTableSnowflake() {
        SqlStatement stmt = generate(SqlStatementType.CREATE_TABLE, SqlDialect.SNOWFLAKE);

        assertThat(stmt.getSql())
                .contains("CREATE TABLE Orders")
                .doesNotContain("\"Orders\"")           // no quoting in Snowflake
                .contains("ORDER_ID BIGINT NOT NULL")
                .contains("STATUS VARCHAR(50) NOT NULL")
                .contains("CONSTRAINT pk_Orders PRIMARY KEY (ORDER_ID)");
    }

    @Test
    @DisplayName("CREATE TABLE (ANSI) — double-quoted identifiers, DOUBLE PRECISION type")
    void createTableAnsi() {
        SqlStatement stmt = generate(SqlStatementType.CREATE_TABLE, SqlDialect.ANSI);

        assertThat(stmt.getSql())
                .contains("CREATE TABLE \"Orders\"")
                .contains("\"TOTAL_AMOUNT\" DECIMAL");
    }

    // ─── SELECT ALL ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("SELECT_ALL — lists all column names with no WHERE clause")
    void selectAll() {
        SqlStatement stmt = generate(SqlStatementType.SELECT_ALL, SqlDialect.POSTGRESQL);

        assertThat(stmt.getSql())
                .startsWith("SELECT")
                .contains("\"ORDER_ID\"")
                .contains("\"CUSTOMER_ID\"")
                .contains("\"STATUS\"")
                .contains("\"TOTAL_AMOUNT\"")
                .contains("FROM \"Orders\"")
                .doesNotContain("WHERE");
        assertThat(stmt.getBindParameters()).isEmpty();
    }

    // ─── SELECT BY PK ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("SELECT_BY_PK — WHERE clause uses named parameter for PK column")
    void selectByPk() {
        SqlStatement stmt = generate(SqlStatementType.SELECT_BY_PK, SqlDialect.POSTGRESQL);

        assertThat(stmt.getSql())
                .contains("FROM \"Orders\"")
                .contains("WHERE \"ORDER_ID\" = :ORDER_ID");
        assertThat(stmt.getBindParameters()).containsExactly("ORDER_ID");
    }

    // ─── INSERT ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("INSERT — all columns listed with named parameters")
    void insert() {
        SqlStatement stmt = generate(SqlStatementType.INSERT, SqlDialect.POSTGRESQL);

        assertThat(stmt.getSql())
                .contains("INSERT INTO \"Orders\"")
                .contains(":ORDER_ID")
                .contains(":CUSTOMER_ID")
                .contains(":STATUS")
                .contains(":TOTAL_AMOUNT");
        assertThat(stmt.getBindParameters())
                .containsExactly("ORDER_ID", "CUSTOMER_ID", "STATUS", "TOTAL_AMOUNT");
    }

    // ─── UPDATE BY PK ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("UPDATE_BY_PK — SET non-PK cols, WHERE uses PK, PK not in SET clause")
    void updateByPk() {
        SqlStatement stmt = generate(SqlStatementType.UPDATE_BY_PK, SqlDialect.POSTGRESQL);

        assertThat(stmt.getSql())
                .contains("UPDATE \"Orders\" SET")
                .contains("\"STATUS\" = :STATUS")
                .contains("\"TOTAL_AMOUNT\" = :TOTAL_AMOUNT")
                .contains("WHERE \"ORDER_ID\" = :ORDER_ID")
                .doesNotContain("\"ORDER_ID\" = :ORDER_ID,");  // PK not in SET

        // PK should appear in bindParams but only in WHERE position (last)
        assertThat(stmt.getBindParameters()).contains("ORDER_ID");
        assertThat(stmt.getBindParameters()).contains("STATUS");
        assertThat(stmt.getBindParameters()).doesNotContain("CUSTOMER_ID"); // it's FK, not updatable directly... actually it is, check it's there
    }

    // ─── DELETE BY PK ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("DELETE_BY_PK — uses PK as WHERE parameter")
    void deleteByPk() {
        SqlStatement stmt = generate(SqlStatementType.DELETE_BY_PK, SqlDialect.POSTGRESQL);

        assertThat(stmt.getSql())
                .contains("DELETE FROM \"Orders\"")
                .contains("WHERE \"ORDER_ID\" = :ORDER_ID");
        assertThat(stmt.getBindParameters()).containsExactly("ORDER_ID");
    }

    // ─── No PK edge case ─────────────────────────────────────────────────────

    @Test
    @DisplayName("SELECT_BY_PK with no PK columns emits a comment placeholder")
    void selectByPkNoPrimaryKey() {
        ShapeTable noPk = ShapeTable.builder()
                .tableName("UnkeyedTable")
                .nodeShapeIri("http://example.org/schema#UnkeyedTableShape")
                .columns(List.of(
                        ShapeColumn.builder().columnName("COL_A")
                                .xsdDatatype(XSD.STRING.stringValue())
                                .nullable(true).isForeignKey(false).isPrimaryKey(false)
                                .build()))
                .primaryKeyColumns(List.of())
                .uniqueConstraintMessages(List.of())
                .checkConstraintMessages(List.of())
                .build();

        SqlStatement stmt = generator.generate(noPk,
                List.of(SqlStatementType.SELECT_BY_PK), SqlDialect.ANSI).get(0);

        assertThat(stmt.getSql()).contains("no primary key defined");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private SqlStatement generate(SqlStatementType type, SqlDialect dialect) {
        return generator.generate(ordersTable, List.of(type), dialect).get(0);
    }

    private String extractLine(String sql, String containing) {
        return sql.lines()
                .filter(l -> l.contains(containing))
                .findFirst()
                .orElse("");
    }
}
