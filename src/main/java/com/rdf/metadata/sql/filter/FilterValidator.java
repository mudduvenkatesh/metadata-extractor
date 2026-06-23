package com.rdf.metadata.sql.filter;

import com.rdf.metadata.sql.ShapeColumn;
import com.rdf.metadata.sql.ShapeTable;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Validates a {@link SelectFilterRequest} against the {@link ShapeTable} parsed
 * from the SHACL model, catching errors before SQL generation.
 *
 * <h3>Checks performed</h3>
 * <ol>
 *   <li>Table name matches the shape (case-insensitive)</li>
 *   <li>Every column in {@code selectColumns} exists in the shape</li>
 *   <li>Every column in every {@link FilterCriterion} exists in the shape</li>
 *   <li>The operator is compatible with the column's XSD datatype:
 *       <ul>
 *         <li>LIKE / ILIKE / NOT_LIKE require {@code xsd:string}</li>
 *         <li>GT / GTE / LT / LTE / BETWEEN require numeric or date types</li>
 *         <li>IS_NULL / IS_NOT_NULL are only valid for nullable columns</li>
 *       </ul>
 *   </li>
 *   <li>Required values are present (value for scalar, values for IN, valueTo for BETWEEN)</li>
 *   <li>ORDER BY columns exist in the shape</li>
 * </ol>
 */
@Slf4j
@Component
public class FilterValidator {

    // XSD types considered "numeric" for comparison operators
    private static final Set<String> NUMERIC_TYPES = Set.of(
            XSD.INTEGER.stringValue(), XSD.LONG.stringValue(),
            XSD.SHORT.stringValue(),   XSD.BYTE.stringValue(),
            XSD.DECIMAL.stringValue(), XSD.FLOAT.stringValue(),
            XSD.DOUBLE.stringValue(),  XSD.NON_NEGATIVE_INTEGER.stringValue()
    );

    // XSD types considered "temporal" for comparison operators
    private static final Set<String> TEMPORAL_TYPES = Set.of(
            XSD.DATE.stringValue(),      XSD.TIME.stringValue(),
            XSD.DATETIME.stringValue(),  XSD.DATETIMESTAMP.stringValue()
    );

    /**
     * Validate the request against the shape. Collects all errors before throwing
     * so the caller receives a complete list rather than the first error only.
     *
     * @throws FilterValidationException if any validation rule is violated
     */
    public void validate(SelectFilterRequest request, ShapeTable shape) {
        List<String> errors = new ArrayList<>();

        // Build a fast lookup of column name → ShapeColumn (case-insensitive)
        Map<String, ShapeColumn> colMap = buildColumnMap(shape);

        // 1. Validate projection columns
        for (String col : request.getSelectColumns()) {
            if (!colMap.containsKey(col.toUpperCase())) {
                errors.add("SELECT column '" + col + "' does not exist in shape for table '"
                        + shape.getTableName() + "'");
            }
        }

        // 2. Validate filter group
        if (request.getFilterGroup() != null && !request.getFilterGroup().isEmpty()) {
            validateGroup(request.getFilterGroup(), colMap, errors, new int[]{0});
        }

        // 3. Validate ORDER BY columns
        for (OrderByClause ob : request.getOrderBy()) {
            if (!colMap.containsKey(ob.getColumn().toUpperCase())) {
                errors.add("ORDER BY column '" + ob.getColumn()
                        + "' does not exist in shape for table '" + shape.getTableName() + "'");
            }
        }

        if (!errors.isEmpty()) {
            log.warn("Filter validation failed with {} error(s) for table '{}'",
                    errors.size(), shape.getTableName());
            throw new FilterValidationException(errors);
        }

        log.debug("Filter validation passed for table '{}'", shape.getTableName());
    }

    // ─────────────────────────────────────────────────────────────────────────

    private void validateGroup(FilterGroup group, Map<String, ShapeColumn> colMap,
                                List<String> errors, int[] counter) {
        for (FilterCriterion criterion : group.getCriteria()) {
            validateCriterion(criterion, colMap, errors, counter[0]++);
        }
        for (FilterGroup nested : group.getGroups()) {
            validateGroup(nested, colMap, errors, counter);
        }
    }

    private void validateCriterion(FilterCriterion criterion,
                                    Map<String, ShapeColumn> colMap,
                                    List<String> errors, int index) {
        String colKey = criterion.getColumn().toUpperCase();
        ShapeColumn col = colMap.get(colKey);

        // Column existence
        if (col == null) {
            errors.add("Filter column '" + criterion.getColumn()
                    + "' does not exist in the shape");
            return; // Can't do type checks without the column definition
        }

        FilterOperator op = criterion.getOperator();

        // Value presence checks
        if (op.requiresScalar() && isBlank(criterion.getValue())) {
            errors.add("Operator " + op + " on column '" + criterion.getColumn()
                    + "' requires a non-null 'value'");
        }
        if (op.requiresList()) {
            if (criterion.getValues() == null || criterion.getValues().isEmpty()) {
                errors.add("Operator " + op + " on column '" + criterion.getColumn()
                        + "' requires a non-empty 'values' list");
            }
        }
        if (op.requiresRange()) {
            if (isBlank(criterion.getValue())) {
                errors.add("Operator " + op + " on column '" + criterion.getColumn()
                        + "' requires 'value' (lower bound)");
            }
            if (isBlank(criterion.getValueTo())) {
                errors.add("Operator " + op + " on column '" + criterion.getColumn()
                        + "' requires 'valueTo' (upper bound)");
            }
        }

        // Type compatibility checks (only for non-FK columns with a known XSD type)
        if (!col.isForeignKey() && col.getXsdDatatype() != null) {
            validateTypeCompatibility(criterion, col, errors);
        }

        // Nullability check for IS_NULL / IS_NOT_NULL
        if (op == FilterOperator.IS_NULL && !col.isNullable()) {
            errors.add("Operator IS_NULL on column '" + criterion.getColumn()
                    + "' is always false — the column is declared NOT NULL in the shape");
        }
    }

    private void validateTypeCompatibility(FilterCriterion criterion,
                                            ShapeColumn col,
                                            List<String> errors) {
        FilterOperator op      = criterion.getOperator();
        String         xsdType = col.getXsdDatatype();

        // LIKE / ILIKE / NOT_LIKE only make sense on string columns
        if ((op == FilterOperator.LIKE || op == FilterOperator.NOT_LIKE
                || op == FilterOperator.ILIKE)
                && !XSD.STRING.stringValue().equals(xsdType)) {
            errors.add("Operator " + op + " on column '" + criterion.getColumn()
                    + "' requires a string (xsd:string) column, but column type is "
                    + shortXsd(xsdType));
        }

        // GT / GTE / LT / LTE / BETWEEN require numeric or temporal types
        if ((op == FilterOperator.GT  || op == FilterOperator.GTE
                || op == FilterOperator.LT  || op == FilterOperator.LTE
                || op == FilterOperator.BETWEEN || op == FilterOperator.NOT_BETWEEN)
                && !NUMERIC_TYPES.contains(xsdType) && !TEMPORAL_TYPES.contains(xsdType)) {
            errors.add("Operator " + op + " on column '" + criterion.getColumn()
                    + "' requires a numeric or date/time column, but column type is "
                    + shortXsd(xsdType));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private Map<String, ShapeColumn> buildColumnMap(ShapeTable shape) {
        Map<String, ShapeColumn> map = new LinkedHashMap<>();
        for (ShapeColumn col : shape.getColumns()) {
            map.put(col.getColumnName().toUpperCase(), col);
        }
        return map;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Shorten {@code http://www.w3.org/2001/XMLSchema#decimal} to {@code xsd:decimal}. */
    private String shortXsd(String iri) {
        int hash = iri.lastIndexOf('#');
        return hash >= 0 ? "xsd:" + iri.substring(hash + 1) : iri;
    }
}
