package com.rdf.metadata.sql.filter;

import com.rdf.metadata.sql.SqlDialect;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Request payload for building a filter-based SELECT statement from a SHACL shape.
 *
 * <h3>Full example</h3>
 * <pre>{@code
 * {
 *   "tableName": "Orders",
 *   "dialect": "POSTGRESQL",
 *   "selectColumns": ["ORDER_ID", "STATUS", "TOTAL_AMOUNT"],
 *   "filterGroup": {
 *     "operator": "AND",
 *     "criteria": [
 *       { "column": "STATUS",       "operator": "IN",     "values": ["ACTIVE","PENDING"] },
 *       { "column": "TOTAL_AMOUNT", "operator": "BETWEEN","value": "100", "valueTo": "500" },
 *       { "column": "DELETED_AT",   "operator": "IS_NULL" }
 *     ],
 *     "groups": [
 *       {
 *         "operator": "OR",
 *         "criteria": [
 *           { "column": "CUSTOMER_ID", "operator": "EQ", "value": "42" },
 *           { "column": "REGION",      "operator": "EQ", "value": "EMEA" }
 *         ]
 *       }
 *     ]
 *   },
 *   "orderBy": [
 *     { "column": "TOTAL_AMOUNT", "direction": "DESC" },
 *     { "column": "ORDER_ID",     "direction": "ASC"  }
 *   ],
 *   "limit": 100,
 *   "offset": 0
 * }
 * }</pre>
 */
@Data
public class SelectFilterRequest {

    /**
     * Target table name — must match a {@code sh:NodeShape} label in the SHACL model.
     * Case-insensitive matching is applied.
     */
    @NotBlank(message = "tableName is required")
    private String tableName;

    /** Target SQL dialect. Defaults to ANSI. */
    @NotNull
    private SqlDialect dialect = SqlDialect.ANSI;

    /**
     * Columns to project in the SELECT list.
     * Empty list = {@code SELECT *} (all columns from the shape).
     */
    private List<String> selectColumns = new ArrayList<>();

    /**
     * The root filter group.
     * Null or empty = no WHERE clause (equivalent to SELECT ALL).
     */
    @Valid
    private FilterGroup filterGroup;

    /** ORDER BY clauses in priority order. */
    @Valid
    private List<OrderByClause> orderBy = new ArrayList<>();

    /**
     * Maximum rows to return.
     * Null = no LIMIT clause.
     */
    @Positive(message = "limit must be positive")
    private Integer limit;

    /**
     * Row offset for pagination.
     * Null or 0 = no OFFSET clause.
     */
    private Integer offset;
}
