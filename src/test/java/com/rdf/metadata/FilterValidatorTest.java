package com.rdf.metadata;

import com.rdf.metadata.sql.ShapeColumn;
import com.rdf.metadata.sql.ShapeTable;
import com.rdf.metadata.sql.SqlDialect;
import com.rdf.metadata.sql.filter.*;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link FilterValidator}.
 */
class FilterValidatorTest {

    private FilterValidator validator;
    private ShapeTable      shape;

    @BeforeEach
    void setUp() {
        validator = new FilterValidator();
        shape = ShapeTable.builder()
                .tableName("Orders")
                .nodeShapeIri("http://example.org/schema#OrdersShape")
                .primaryKeyColumns(List.of("ORDER_ID"))
                .columns(List.of(
                        ShapeColumn.builder().columnName("ORDER_ID")
                                .xsdDatatype(XSD.LONG.stringValue())
                                .nullable(false).isPrimaryKey(true).isForeignKey(false).build(),
                        ShapeColumn.builder().columnName("STATUS")
                                .xsdDatatype(XSD.STRING.stringValue())
                                .nullable(false).isPrimaryKey(false).isForeignKey(false).build(),
                        ShapeColumn.builder().columnName("TOTAL_AMOUNT")
                                .xsdDatatype(XSD.DECIMAL.stringValue())
                                .nullable(true).isPrimaryKey(false).isForeignKey(false).build(),
                        ShapeColumn.builder().columnName("DELETED_AT")
                                .xsdDatatype(XSD.DATETIME.stringValue())
                                .nullable(true).isPrimaryKey(false).isForeignKey(false).build()
                ))
                .uniqueConstraintMessages(List.of())
                .checkConstraintMessages(List.of())
                .build();
    }

    // ─── Valid requests ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Valid EQ filter on string column passes")
    void validEqFilter() {
        assertThatNoException().isThrownBy(() ->
                validator.validate(requestWith("STATUS", FilterOperator.EQ, "ACTIVE"), shape));
    }

    @Test
    @DisplayName("Valid BETWEEN filter on numeric column passes")
    void validBetweenFilter() {
        FilterCriterion c = criterion("TOTAL_AMOUNT", FilterOperator.BETWEEN, "100");
        c.setValueTo("500");
        assertThatNoException().isThrownBy(() ->
                validator.validate(requestWithCriterion(c), shape));
    }

    @Test
    @DisplayName("Valid IS_NULL on nullable column passes")
    void validIsNullOnNullableColumn() {
        assertThatNoException().isThrownBy(() ->
                validator.validate(requestWith("DELETED_AT", FilterOperator.IS_NULL, null), shape));
    }

    @Test
    @DisplayName("Valid IN filter passes")
    void validInFilter() {
        FilterCriterion c = criterion("STATUS", FilterOperator.IN, null);
        c.setValues(List.of("ACTIVE", "PENDING"));
        assertThatNoException().isThrownBy(() ->
                validator.validate(requestWithCriterion(c), shape));
    }

    // ─── Column existence ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Unknown column in filter criteria throws")
    void unknownFilterColumn() {
        FilterValidationException ex = catchThrowableOfType(
                () -> validator.validate(requestWith("NONEXISTENT", FilterOperator.EQ, "x"), shape),
                FilterValidationException.class);
        assertThat(ex.getErrors()).anyMatch(e -> e.contains("NONEXISTENT"));
    }

    @Test
    @DisplayName("Unknown column in selectColumns throws")
    void unknownSelectColumn() {
        SelectFilterRequest req = new SelectFilterRequest();
        req.setTableName("Orders");
        req.setDialect(SqlDialect.POSTGRESQL);
        req.setSelectColumns(List.of("ORDER_ID", "GHOST_COLUMN"));

        FilterValidationException ex = catchThrowableOfType(
                () -> validator.validate(req, shape), FilterValidationException.class);
        assertThat(ex.getErrors()).anyMatch(e -> e.contains("GHOST_COLUMN"));
    }

    @Test
    @DisplayName("Unknown column in ORDER BY throws")
    void unknownOrderByColumn() {
        SelectFilterRequest req = new SelectFilterRequest();
        req.setTableName("Orders");
        req.setDialect(SqlDialect.POSTGRESQL);
        OrderByClause ob = new OrderByClause();
        ob.setColumn("PHANTOM");
        req.setOrderBy(List.of(ob));

        FilterValidationException ex = catchThrowableOfType(
                () -> validator.validate(req, shape), FilterValidationException.class);
        assertThat(ex.getErrors()).anyMatch(e -> e.contains("PHANTOM"));
    }

    // ─── Type compatibility ───────────────────────────────────────────────────

    @Test
    @DisplayName("LIKE on numeric column throws")
    void likeOnNumericColumn() {
        FilterValidationException ex = catchThrowableOfType(
                () -> validator.validate(
                        requestWith("TOTAL_AMOUNT", FilterOperator.LIKE, "%100%"), shape),
                FilterValidationException.class);
        assertThat(ex.getErrors()).anyMatch(e -> e.contains("LIKE"));
    }

    @Test
    @DisplayName("GT on string column throws")
    void gtOnStringColumn() {
        FilterValidationException ex = catchThrowableOfType(
                () -> validator.validate(requestWith("STATUS", FilterOperator.GT, "Z"), shape),
                FilterValidationException.class);
        assertThat(ex.getErrors()).anyMatch(e -> e.contains("GT"));
    }

    @Test
    @DisplayName("BETWEEN on string column throws")
    void betweenOnStringColumn() {
        FilterCriterion c = criterion("STATUS", FilterOperator.BETWEEN, "A");
        c.setValueTo("Z");
        FilterValidationException ex = catchThrowableOfType(
                () -> validator.validate(requestWithCriterion(c), shape),
                FilterValidationException.class);
        assertThat(ex.getErrors()).anyMatch(e -> e.contains("BETWEEN"));
    }

    // ─── Value arity ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("BETWEEN without valueTo throws")
    void betweenMissingValueTo() {
        FilterCriterion c = criterion("TOTAL_AMOUNT", FilterOperator.BETWEEN, "100");
        // valueTo is null
        FilterValidationException ex = catchThrowableOfType(
                () -> validator.validate(requestWithCriterion(c), shape),
                FilterValidationException.class);
        assertThat(ex.getErrors()).anyMatch(e -> e.contains("valueTo"));
    }

    @Test
    @DisplayName("IN with empty values list throws")
    void inWithEmptyValues() {
        FilterCriterion c = criterion("STATUS", FilterOperator.IN, null);
        c.setValues(List.of());
        FilterValidationException ex = catchThrowableOfType(
                () -> validator.validate(requestWithCriterion(c), shape),
                FilterValidationException.class);
        assertThat(ex.getErrors()).anyMatch(e -> e.contains("values"));
    }

    @Test
    @DisplayName("EQ without value throws")
    void eqMissingValue() {
        FilterValidationException ex = catchThrowableOfType(
                () -> validator.validate(requestWith("STATUS", FilterOperator.EQ, null), shape),
                FilterValidationException.class);
        assertThat(ex.getErrors()).anyMatch(e -> e.contains("value"));
    }

    // ─── Multiple errors collected ────────────────────────────────────────────

    @Test
    @DisplayName("Multiple errors are collected before throwing")
    void multipleErrorsCollected() {
        FilterGroup group = new FilterGroup();
        group.getCriteria().add(criterion("NONEXISTENT_1", FilterOperator.EQ, "x"));
        group.getCriteria().add(criterion("NONEXISTENT_2", FilterOperator.EQ, "y"));

        SelectFilterRequest req = new SelectFilterRequest();
        req.setTableName("Orders");
        req.setDialect(SqlDialect.POSTGRESQL);
        req.setFilterGroup(group);

        FilterValidationException ex = catchThrowableOfType(
                () -> validator.validate(req, shape), FilterValidationException.class);
        assertThat(ex.getErrors()).hasSizeGreaterThanOrEqualTo(2);
    }

    // ─── IS_NULL on NOT NULL column warns ────────────────────────────────────

    @Test
    @DisplayName("IS_NULL on NOT NULL column produces a validation error")
    void isNullOnNotNullColumn() {
        FilterValidationException ex = catchThrowableOfType(
                () -> validator.validate(requestWith("STATUS", FilterOperator.IS_NULL, null), shape),
                FilterValidationException.class);
        assertThat(ex.getErrors()).anyMatch(e -> e.contains("IS_NULL") || e.contains("NOT NULL"));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private SelectFilterRequest requestWith(String col, FilterOperator op, String value) {
        return requestWithCriterion(criterion(col, op, value));
    }

    private SelectFilterRequest requestWithCriterion(FilterCriterion c) {
        FilterGroup group = new FilterGroup();
        group.getCriteria().add(c);

        SelectFilterRequest req = new SelectFilterRequest();
        req.setTableName("Orders");
        req.setDialect(SqlDialect.POSTGRESQL);
        req.setFilterGroup(group);
        return req;
    }

    private FilterCriterion criterion(String col, FilterOperator op, String value) {
        FilterCriterion c = new FilterCriterion();
        c.setColumn(col);
        c.setOperator(op);
        c.setValue(value);
        return c;
    }
}
