package com.rdf.metadata.sql.filter;

import com.rdf.metadata.sql.ShapeColumn;
import com.rdf.metadata.sql.ShapeTable;
import com.rdf.metadata.sql.SqlDialect;
import com.rdf.metadata.sql.SqlStatement;
import com.rdf.metadata.sql.SqlStatementType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Builds a filter-based SELECT SQL statement from a {@link SelectFilterRequest}
 * and the corresponding {@link ShapeTable} parsed from the SHACL model.
 *
 * <h3>Pipeline</h3>
 * <ol>
 *   <li>Assign unique parameter keys to every {@link FilterCriterion} (avoids collisions
 *       when the same column appears multiple times)</li>
 *   <li>Build the SELECT projection list</li>
 *   <li>Recursively render the {@link FilterGroup} tree into a WHERE clause</li>
 *   <li>Append ORDER BY, LIMIT, OFFSET</li>
 *   <li>Collect all bind parameter names in declaration order</li>
 * </ol>
 *
 * <p>All named parameters use the {@code :paramKey} style compatible with Spring's
 * {@link org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate}.
 */
@Slf4j
@Component
public class SelectFilterSqlBuilder {

    /**
     * Build the SELECT SQL from a pre-validated request.
     * Call {@link FilterValidator#validate} before this method.
     *
     * @param request the filter request (must already be validated)
     * @param shape   the SHACL shape for the target table
     * @return a fully rendered {@link SqlStatement} with bind parameters
     */
    public SqlStatement build(SelectFilterRequest request, ShapeTable shape) {
        SqlDialect dialect = request.getDialect();

        // Step 1: assign unique param keys to all criteria in the tree
        AtomicInteger counter = new AtomicInteger(0);
        if (request.getFilterGroup() != null) {
            assignParamKeys(request.getFilterGroup(), counter);
        }

        // Step 2: SELECT projection
        String selectClause = buildSelectClause(request, shape, dialect);

        // Step 3: WHERE clause
        String whereClause = (request.getFilterGroup() == null
                || request.getFilterGroup().isEmpty())
                ? null
                : renderGroup(request.getFilterGroup(), dialect);

        // Step 4: ORDER BY
        String orderByClause = buildOrderByClause(request, dialect);

        // Step 5: assemble SQL
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT\n").append(selectClause)
           .append("\nFROM ").append(quote(shape.getTableName(), dialect));

        if (whereClause != null) {
            sql.append("\nWHERE ").append(whereClause);
        }
        if (orderByClause != null) {
            sql.append("\nORDER BY ").append(orderByClause);
        }
        if (request.getLimit() != null) {
            sql.append("\nLIMIT ").append(request.getLimit());
        }
        if (request.getOffset() != null && request.getOffset() > 0) {
            sql.append("\nOFFSET ").append(request.getOffset());
        }
        sql.append(";");

        // Step 6: collect bind parameters in declaration order
        List<String> bindParams = collectBindParams(request.getFilterGroup());

        log.debug("Built filter SELECT for table '{}': {} bind params, whereClause={}",
                shape.getTableName(), bindParams.size(), whereClause != null);

        return SqlStatement.builder()
                .type(SqlStatementType.SELECT_ALL)   // re-uses SELECT_ALL type; filter makes it distinct
                .tableName(shape.getTableName())
                .sql(sql.toString())
                .dialect(dialect)
                .bindParameters(bindParams)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 1 — Assign unique parameter keys
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Walk the filter tree and assign a unique {@code paramKey} to every criterion.
     * Keys are formatted as {@code COLUMN_N} where N is a monotonically increasing counter.
     * This prevents collisions when the same column appears in multiple criteria:
     * e.g. {@code TOTAL_AMOUNT >= :TOTAL_AMOUNT_2  AND  TOTAL_AMOUNT <= :TOTAL_AMOUNT_5}.
     */
    private void assignParamKeys(FilterGroup group, AtomicInteger counter) {
        for (FilterCriterion criterion : group.getCriteria()) {
            if (!criterion.getOperator().isUnary()) {
                // For IN/NOT_IN operators, assign separate keys per value
                int idx = counter.getAndIncrement();
                criterion.setParamKey(criterion.getColumn().toUpperCase() + "_" + idx);
            }
        }
        for (FilterGroup nested : group.getGroups()) {
            assignParamKeys(nested, counter);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 2 — SELECT clause
    // ─────────────────────────────────────────────────────────────────────────

    private String buildSelectClause(SelectFilterRequest request,
                                      ShapeTable shape, SqlDialect dialect) {
        List<String> cols = request.getSelectColumns().isEmpty()
                // No projection specified → all shape columns
                ? shape.getColumns().stream()
                        .map(ShapeColumn::getColumnName)
                        .toList()
                : request.getSelectColumns();

        return cols.stream()
                .map(c -> "    " + quote(c.toUpperCase(), dialect))
                .collect(Collectors.joining(",\n"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 3 — WHERE clause (recursive)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Recursively render a {@link FilterGroup} into a parenthesised SQL fragment.
     *
     * <p>Each group is wrapped in parentheses so nested AND/OR logic is unambiguous:
     * <pre>{@code (a = :a AND (b = :b OR c = :c)) }</pre>
     */
    private String renderGroup(FilterGroup group, SqlDialect dialect) {
        List<String> parts = new ArrayList<>();

        // Render leaf criteria
        for (FilterCriterion criterion : group.getCriteria()) {
            parts.add(renderCriterion(criterion, dialect));
        }

        // Render nested groups recursively
        for (FilterGroup nested : group.getGroups()) {
            if (!nested.isEmpty()) {
                parts.add(renderGroup(nested, dialect));
            }
        }

        if (parts.isEmpty()) return "";

        String joiner = "\n  " + group.getOperator().name() + " ";
        String body   = String.join(joiner, parts);

        // Wrap in parentheses only when there is more than one part, or it's a nested group
        return parts.size() > 1 ? "(\n  " + body + "\n)" : body;
    }

    /**
     * Render a single {@link FilterCriterion} into a SQL predicate fragment.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code "STATUS" = :STATUS_0}</li>
     *   <li>{@code "TOTAL_AMOUNT" BETWEEN :TOTAL_AMOUNT_2 AND :TOTAL_AMOUNT_2_TO}</li>
     *   <li>{@code "CUSTOMER_ID" IN (:CUSTOMER_ID_3_0, :CUSTOMER_ID_3_1)}</li>
     *   <li>{@code LOWER("EMAIL") LIKE LOWER(:EMAIL_4)}  (ILIKE on ANSI/Snowflake)</li>
     *   <li>{@code "DELETED_AT" IS NULL}</li>
     * </ul>
     */
    private String renderCriterion(FilterCriterion criterion, SqlDialect dialect) {
        String col    = quote(criterion.getColumn().toUpperCase(), dialect);
        String param  = criterion.getParamKey();
        FilterOperator op = criterion.getOperator();

        return switch (op) {
            case EQ       -> col + " = :"       + param;
            case NEQ      -> col + " != :"      + param;
            case GT       -> col + " > :"       + param;
            case GTE      -> col + " >= :"      + param;
            case LT       -> col + " < :"       + param;
            case LTE      -> col + " <= :"      + param;
            case LIKE     -> col + " LIKE :"    + param;
            case NOT_LIKE -> col + " NOT LIKE :" + param;
            case ILIKE    -> renderIlike(col, param, dialect);

            case IN       -> col + " IN ("
                    + renderInParams(param, criterion.getValues()) + ")";
            case NOT_IN   -> col + " NOT IN ("
                    + renderInParams(param, criterion.getValues()) + ")";

            case BETWEEN  ->
                    col + " BETWEEN :" + param + " AND :" + param + "_TO";
            case NOT_BETWEEN ->
                    col + " NOT BETWEEN :" + param + " AND :" + param + "_TO";

            case IS_NULL     -> col + " IS NULL";
            case IS_NOT_NULL -> col + " IS NOT NULL";
        };
    }

    /**
     * ILIKE rendering:
     * <ul>
     *   <li>PostgreSQL: use native {@code ILIKE} operator</li>
     *   <li>ANSI / Snowflake: emulate with {@code LOWER(col) LIKE LOWER(:param)}</li>
     * </ul>
     */
    private String renderIlike(String quotedCol, String param, SqlDialect dialect) {
        if (dialect == SqlDialect.POSTGRESQL) {
            return quotedCol + " ILIKE :" + param;
        }
        return "LOWER(" + quotedCol + ") LIKE LOWER(:" + param + ")";
    }

    /**
     * Generate named parameters for IN / NOT_IN:
     * {@code :CUSTOMER_ID_3_0, :CUSTOMER_ID_3_1, :CUSTOMER_ID_3_2}
     */
    private String renderInParams(String baseParam, List<String> values) {
        List<String> params = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            params.add(":" + baseParam + "_" + i);
        }
        return String.join(", ", params);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 4 — ORDER BY clause
    // ─────────────────────────────────────────────────────────────────────────

    private String buildOrderByClause(SelectFilterRequest request, SqlDialect dialect) {
        if (request.getOrderBy().isEmpty()) return null;

        return request.getOrderBy().stream()
                .map(ob -> quote(ob.getColumn().toUpperCase(), dialect)
                        + " " + ob.getDirection().name())
                .collect(Collectors.joining(", "));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 6 — Collect bind parameter names in declaration order
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Walk the filter tree and collect bind parameter names in the order they
     * appear in the SQL — critical for JDBC positional binding and logging.
     *
     * <p>For BETWEEN, emits both {@code paramKey} and {@code paramKey_TO}.
     * For IN/NOT_IN, emits {@code paramKey_0 … paramKey_N}.
     */
    private List<String> collectBindParams(FilterGroup group) {
        List<String> params = new ArrayList<>();
        if (group == null) return params;
        collectFromGroup(group, params);
        return params;
    }

    private void collectFromGroup(FilterGroup group, List<String> params) {
        for (FilterCriterion c : group.getCriteria()) {
            FilterOperator op = c.getOperator();
            if (op.isUnary()) continue;

            String key = c.getParamKey();
            if (op.requiresRange()) {
                params.add(key);
                params.add(key + "_TO");
            } else if (op.requiresList()) {
                for (int i = 0; i < c.getValues().size(); i++) {
                    params.add(key + "_" + i);
                }
            } else {
                params.add(key);
            }
        }
        for (FilterGroup nested : group.getGroups()) {
            collectFromGroup(nested, params);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Quoting
    // ─────────────────────────────────────────────────────────────────────────

    private String quote(String identifier, SqlDialect dialect) {
        return switch (dialect) {
            case SNOWFLAKE -> identifier;
            default        -> "\"" + identifier + "\"";
        };
    }
}
