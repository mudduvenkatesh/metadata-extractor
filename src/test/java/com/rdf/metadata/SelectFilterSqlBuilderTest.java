package com.rdf.metadata;

import com.rdf.metadata.sql.ShapeColumn;
import com.rdf.metadata.sql.ShapeTable;
import com.rdf.metadata.sql.SqlDialect;
import com.rdf.metadata.sql.SqlStatement;
import com.rdf.metadata.sql.filter.*;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SelectFilterSqlBuilder}.
 *
 * <p>Uses a hand-crafted {@link ShapeTable} for an ORDERS table with:
 * ORDER_ID (BIGINT PK), CUSTOMER_ID (FK), STATUS (VARCHAR 50), TOTAL_AMOUNT (DECIMAL nullable),
 * ORDER_DATE (TIMESTAMP), DELETED_AT (TIMESTAMP nullable).
 */
class SelectFilterSqlBuilderTest {

    private SelectFilterSqlBuilder builder;
    private ShapeTable             shape;

    @BeforeEach
    void setUp() {
        builder = new SelectFilterSqlBuilder();

        shape = ShapeTable.builder()
                .tableName("Orders")
                .nodeShapeIri("http://example.org/schema#OrdersShape")
                .primaryKeyColumns(List.of("ORDER_ID"))
                .columns(List.of(
                        col("ORDER_ID",     XSD.LONG.stringValue(),    false, true,  false, null),
                        col("CUSTOMER_ID",  null,                      false, false, true,  "Customers"),
                        col("STATUS",       XSD.STRING.stringValue(),  false, false, false, null),
                        col("TOTAL_AMOUNT", XSD.DECIMAL.stringValue(), true,  false, false, null),
                        col("ORDER_DATE",   XSD.DATETIME.stringValue(),false, false, false, null),
                        col("DELETED_AT",   XSD.DATETIME.stringValue(),true,  false, false, null)
                ))
                .uniqueConstraintMessages(List.of())
                .checkConstraintMessages(List.of())
                .build();
    }

    // ─── Column projection ────────────────────────────────────────────────────

    @Test
    @DisplayName("Empty selectColumns produces SELECT * (all shape columns)")
    void selectAllColumnsWhenProjectionEmpty() {
        SqlStatement stmt = build(req("Orders", SqlDialect.POSTGRESQL));
        assertThat(stmt.getSql())
                .contains("\"ORDER_ID\"")
                .contains("\"STATUS\"")
                .contains("\"TOTAL_AMOUNT\"");
        assertThat(stmt.getSql()).doesNotContain("WHERE");
    }

    @Test
    @DisplayName("Specified selectColumns are projected correctly")
    void selectSpecifiedColumns() {
        SelectFilterRequest req = req("Orders", SqlDialect.POSTGRESQL);
        req.setSelectColumns(List.of("ORDER_ID", "STATUS"));

        SqlStatement stmt = build(req);
        assertThat(stmt.getSql())
                .contains("\"ORDER_ID\"")
                .contains("\"STATUS\"")
                .doesNotContain("\"TOTAL_AMOUNT\"");
    }

    // ─── Scalar operators ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Scalar operators")
    class ScalarOperators {

        @Test @DisplayName("EQ → col = :param")
        void eq() {
            SqlStatement stmt = buildWithCriterion("STATUS", FilterOperator.EQ, "ACTIVE");
            assertThat(stmt.getSql()).contains("\"STATUS\" = :STATUS_0");
            assertThat(stmt.getBindParameters()).containsExactly("STATUS_0");
        }

        @Test @DisplayName("NEQ → col != :param")
        void neq() {
            assertThat(buildWithCriterion("STATUS", FilterOperator.NEQ, "CANCELLED").getSql())
                    .contains("\"STATUS\" != :STATUS_0");
        }

        @Test @DisplayName("GT → col > :param")
        void gt() {
            assertThat(buildWithCriterion("TOTAL_AMOUNT", FilterOperator.GT, "100").getSql())
                    .contains("\"TOTAL_AMOUNT\" > :TOTAL_AMOUNT_0");
        }

        @Test @DisplayName("GTE → col >= :param")
        void gte() {
            assertThat(buildWithCriterion("TOTAL_AMOUNT", FilterOperator.GTE, "100").getSql())
                    .contains("\"TOTAL_AMOUNT\" >= :TOTAL_AMOUNT_0");
        }

        @Test @DisplayName("LT → col < :param")
        void lt() {
            assertThat(buildWithCriterion("TOTAL_AMOUNT", FilterOperator.LT, "500").getSql())
                    .contains("\"TOTAL_AMOUNT\" < :TOTAL_AMOUNT_0");
        }

        @Test @DisplayName("LTE → col <= :param")
        void lte() {
            assertThat(buildWithCriterion("TOTAL_AMOUNT", FilterOperator.LTE, "500").getSql())
                    .contains("\"TOTAL_AMOUNT\" <= :TOTAL_AMOUNT_0");
        }

        @Test @DisplayName("LIKE → col LIKE :param")
        void like() {
            assertThat(buildWithCriterion("STATUS", FilterOperator.LIKE, "%ACT%").getSql())
                    .contains("\"STATUS\" LIKE :STATUS_0");
        }

        @Test @DisplayName("NOT_LIKE → col NOT LIKE :param")
        void notLike() {
            assertThat(buildWithCriterion("STATUS", FilterOperator.NOT_LIKE, "%CANCEL%").getSql())
                    .contains("\"STATUS\" NOT LIKE :STATUS_0");
        }
    }

    // ─── ILIKE ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ILIKE on PostgreSQL uses native ILIKE")
    void iLikePostgresql() {
        assertThat(buildWithCriterion("STATUS", FilterOperator.ILIKE, "%active%",
                SqlDialect.POSTGRESQL).getSql())
                .contains("\"STATUS\" ILIKE :STATUS_0");
    }

    @Test
    @DisplayName("ILIKE on Snowflake uses LOWER(col) LIKE LOWER(:param)")
    void iLikeSnowflake() {
        assertThat(buildWithCriterion("STATUS", FilterOperator.ILIKE, "%active%",
                SqlDialect.SNOWFLAKE).getSql())
                .contains("LOWER(STATUS) LIKE LOWER(:STATUS_0)");
    }

    // ─── IN / NOT_IN ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("IN → col IN (:param_0, :param_1, ...)")
    void inOperator() {
        FilterCriterion c = criterion("STATUS", FilterOperator.IN, null);
        c.setValues(List.of("ACTIVE", "PENDING", "SHIPPED"));

        SqlStatement stmt = buildWithCriteria(List.of(c), SqlDialect.POSTGRESQL);
        assertThat(stmt.getSql())
                .contains("\"STATUS\" IN (:STATUS_0_0, :STATUS_0_1, :STATUS_0_2)");
        assertThat(stmt.getBindParameters())
                .containsExactly("STATUS_0_0", "STATUS_0_1", "STATUS_0_2");
    }

    @Test
    @DisplayName("NOT_IN → col NOT IN (:param_0, ...)")
    void notIn() {
        FilterCriterion c = criterion("STATUS", FilterOperator.NOT_IN, null);
        c.setValues(List.of("CANCELLED", "RETURNED"));

        assertThat(buildWithCriteria(List.of(c), SqlDialect.POSTGRESQL).getSql())
                .contains("\"STATUS\" NOT IN (:STATUS_0_0, :STATUS_0_1)");
    }

    // ─── BETWEEN ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("BETWEEN → col BETWEEN :param AND :param_TO")
    void between() {
        FilterCriterion c = criterion("TOTAL_AMOUNT", FilterOperator.BETWEEN, "100");
        c.setValueTo("500");

        SqlStatement stmt = buildWithCriteria(List.of(c), SqlDialect.POSTGRESQL);
        assertThat(stmt.getSql())
                .contains("\"TOTAL_AMOUNT\" BETWEEN :TOTAL_AMOUNT_0 AND :TOTAL_AMOUNT_0_TO");
        assertThat(stmt.getBindParameters())
                .containsExactly("TOTAL_AMOUNT_0", "TOTAL_AMOUNT_0_TO");
    }

    @Test
    @DisplayName("NOT_BETWEEN → col NOT BETWEEN :param AND :param_TO")
    void notBetween() {
        FilterCriterion c = criterion("TOTAL_AMOUNT", FilterOperator.NOT_BETWEEN, "100");
        c.setValueTo("500");
        assertThat(buildWithCriteria(List.of(c), SqlDialect.POSTGRESQL).getSql())
                .contains("\"TOTAL_AMOUNT\" NOT BETWEEN :TOTAL_AMOUNT_0 AND :TOTAL_AMOUNT_0_TO");
    }

    // ─── IS NULL / IS NOT NULL ────────────────────────────────────────────────

    @Test
    @DisplayName("IS_NULL → col IS NULL, no bind param")
    void isNull() {
        SqlStatement stmt = buildWithCriterion("DELETED_AT", FilterOperator.IS_NULL, null);
        assertThat(stmt.getSql()).contains("\"DELETED_AT\" IS NULL");
        assertThat(stmt.getBindParameters()).isEmpty();
    }

    @Test
    @DisplayName("IS_NOT_NULL → col IS NOT NULL, no bind param")
    void isNotNull() {
        assertThat(buildWithCriterion("DELETED_AT", FilterOperator.IS_NOT_NULL, null).getSql())
                .contains("\"DELETED_AT\" IS NOT NULL");
    }

    // ─── Nested AND / OR groups ───────────────────────────────────────────────

    @Test
    @DisplayName("AND group: multiple criteria joined with AND")
    void andGroup() {
        FilterGroup group = new FilterGroup();
        group.setOperator(LogicalOperator.AND);
        group.getCriteria().add(criterion("STATUS", FilterOperator.EQ, "ACTIVE"));
        group.getCriteria().add(criterion("TOTAL_AMOUNT", FilterOperator.GTE, "100"));

        SelectFilterRequest req = req("Orders", SqlDialect.POSTGRESQL);
        req.setFilterGroup(group);

        String sql = build(req).getSql();
        assertThat(sql)
                .contains("\"STATUS\" = :STATUS_0")
                .contains("\"TOTAL_AMOUNT\" >= :TOTAL_AMOUNT_1")
                .contains("AND");
    }

    @Test
    @DisplayName("Nested OR inside AND group is parenthesised")
    void nestedOrInsideAnd() {
        FilterGroup outer = new FilterGroup();
        outer.setOperator(LogicalOperator.AND);
        outer.getCriteria().add(criterion("STATUS", FilterOperator.EQ, "ACTIVE"));

        FilterGroup inner = new FilterGroup();
        inner.setOperator(LogicalOperator.OR);
        inner.getCriteria().add(criterion("TOTAL_AMOUNT", FilterOperator.LT, "100"));
        inner.getCriteria().add(criterion("ORDER_DATE",   FilterOperator.IS_NULL, null));
        outer.getGroups().add(inner);

        SelectFilterRequest req = req("Orders", SqlDialect.POSTGRESQL);
        req.setFilterGroup(outer);

        String sql = build(req).getSql();
        assertThat(sql)
                .contains("\"STATUS\" = :STATUS_0")
                .contains("OR")
                .contains("(");   // inner group is parenthesised
    }

    // ─── ORDER BY ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ORDER BY is appended after WHERE")
    void orderBy() {
        SelectFilterRequest req = req("Orders", SqlDialect.POSTGRESQL);
        

        OrderByClause ob1 = new OrderByClause();
        ob1.setColumn("TOTAL_AMOUNT");
        ob1.setDirection(OrderByClause.SortOrder.DESC);
        OrderByClause ob2 = new OrderByClause();
        ob2.setColumn("ORDER_ID");
        ob2.setDirection(OrderByClause.SortOrder.ASC);
        req.setOrderBy(List.of(ob1, ob2));

        String sql = build(req).getSql();
        assertThat(sql).contains("ORDER BY \"TOTAL_AMOUNT\" DESC, \"ORDER_ID\" ASC");
    }

    // ─── LIMIT / OFFSET ───────────────────────────────────────────────────────

    @Test
    @DisplayName("LIMIT and OFFSET are appended")
    void limitAndOffset() {
        SelectFilterRequest req = req("Orders", SqlDialect.POSTGRESQL);
        req.setLimit(50);
        req.setOffset(100);

        String sql = build(req).getSql();
        assertThat(sql).contains("LIMIT 50").contains("OFFSET 100");
    }

    @Test
    @DisplayName("OFFSET 0 is omitted from SQL")
    void offsetZeroOmitted() {
        SelectFilterRequest req = req("Orders", SqlDialect.POSTGRESQL);
        req.setOffset(0);
        assertThat(build(req).getSql()).doesNotContain("OFFSET");
    }

    // ─── Dialect: Snowflake unquoted ──────────────────────────────────────────

    @Test
    @DisplayName("Snowflake dialect produces unquoted identifiers")
    void snowflakeUnquotedIdentifiers() {
        String sql = buildWithCriterion("STATUS", FilterOperator.EQ, "ACTIVE",
                SqlDialect.SNOWFLAKE).getSql();
        assertThat(sql)
                .contains("STATUS = :STATUS_0")
                .doesNotContain("\"STATUS\"");
    }

    // ─── No filter group → no WHERE clause ────────────────────────────────────

    @Test
    @DisplayName("Null filterGroup produces SELECT without WHERE")
    void noFilterGroupProducesNoWhere() {
        SelectFilterRequest req = req("Orders", SqlDialect.POSTGRESQL);
        req.setFilterGroup(null);
        assertThat(build(req).getSql()).doesNotContain("WHERE");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private SqlStatement build(SelectFilterRequest request) {
        return builder.build(request, shape);
    }

    private SqlStatement buildWithCriterion(String col, FilterOperator op, String value) {
        return buildWithCriterion(col, op, value, SqlDialect.POSTGRESQL);
    }

    private SqlStatement buildWithCriterion(String col, FilterOperator op,
                                              String value, SqlDialect dialect) {
        return buildWithCriteria(List.of(criterion(col, op, value)), dialect);
    }

    private SqlStatement buildWithCriteria(List<FilterCriterion> criteria, SqlDialect dialect) {
        FilterGroup group = new FilterGroup();
        group.setOperator(LogicalOperator.AND);
        group.getCriteria().addAll(criteria);

        SelectFilterRequest req = req("Orders", dialect);
        req.setFilterGroup(group);
        return build(req);
    }

    private FilterCriterion criterion(String col, FilterOperator op, String value) {
        FilterCriterion c = new FilterCriterion();
        c.setColumn(col);
        c.setOperator(op);
        c.setValue(value);
        return c;
    }

    private SelectFilterRequest req(String tableName, SqlDialect dialect) {
        SelectFilterRequest req = new SelectFilterRequest();
        req.setTableName(tableName);
        req.setDialect(dialect);
        return req;
    }

    private ShapeColumn col(String name, String xsd, boolean nullable,
                             boolean pk, boolean fk, String refTable) {
        return ShapeColumn.builder()
                .columnName(name)
                .xsdDatatype(xsd)
                .nullable(nullable)
                .isPrimaryKey(pk)
                .isForeignKey(fk)
                .referencedTable(refTable)
                .build();
    }
}
