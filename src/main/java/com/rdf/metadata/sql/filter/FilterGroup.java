package com.rdf.metadata.sql.filter;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * A recursive tree node that composes {@link FilterCriterion}s and nested
 * {@link FilterGroup}s with a {@link LogicalOperator}.
 *
 * <h3>Structure</h3>
 * <pre>
 * FilterGroup (AND)
 *   ├── FilterCriterion: STATUS = 'ACTIVE'
 *   ├── FilterCriterion: TOTAL_AMOUNT >= 100
 *   └── FilterGroup (OR)
 *         ├── FilterCriterion: CUSTOMER_ID IN (1, 2, 3)
 *         └── FilterCriterion: REGION = 'EMEA'
 * </pre>
 *
 * <p>Produces:
 * <pre>{@code
 * (
 *   STATUS = :STATUS_0
 *   AND TOTAL_AMOUNT >= :TOTAL_AMOUNT_1
 *   AND (
 *     CUSTOMER_ID IN (:CUSTOMER_ID_2_0, :CUSTOMER_ID_2_1, :CUSTOMER_ID_2_2)
 *     OR REGION = :REGION_3
 *   )
 * )
 * }</pre>
 *
 * <h3>JSON request example</h3>
 * <pre>{@code
 * {
 *   "operator": "AND",
 *   "criteria": [
 *     { "column": "STATUS",       "operator": "EQ",  "value": "ACTIVE" },
 *     { "column": "TOTAL_AMOUNT", "operator": "GTE", "value": "100"    }
 *   ],
 *   "groups": [
 *     {
 *       "operator": "OR",
 *       "criteria": [
 *         { "column": "CUSTOMER_ID", "operator": "IN",  "values": ["1","2","3"] },
 *         { "column": "REGION",      "operator": "EQ",  "value":  "EMEA"       }
 *       ]
 *     }
 *   ]
 * }
 * }</pre>
 */
@Data
public class FilterGroup {

    /**
     * Logical operator joining all entries in this group.
     * Defaults to AND.
     */
    private LogicalOperator operator = LogicalOperator.AND;

    /** Leaf predicates at this level of the tree. */
    private List<FilterCriterion> criteria = new ArrayList<>();

    /** Nested sub-groups at this level of the tree. */
    private List<FilterGroup> groups = new ArrayList<>();

    /** True if both {@code criteria} and {@code groups} are empty. */
    public boolean isEmpty() {
        return criteria.isEmpty() && groups.isEmpty();
    }
}
