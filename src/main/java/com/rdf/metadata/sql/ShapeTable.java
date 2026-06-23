package com.rdf.metadata.sql;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Intermediate representation of a table parsed from a SHACL {@code sh:NodeShape}.
 *
 * <p>Aggregates all {@link ShapeColumn}s belonging to this shape, the primary key
 * column names (from {@code owl:hasKey} cross-reference), and any unique/check
 * constraint comments extracted from {@code sh:sparql} blank nodes.
 */
@Data
@Builder
public class ShapeTable {

    /** The SQL table name — the local name extracted from {@code sh:targetClass}. */
    private String           tableName;

    /** The full IRI of the {@code sh:NodeShape}. */
    private String           nodeShapeIri;

    /** Ordered list of columns parsed from {@code sh:property} blank nodes. */
    private List<ShapeColumn> columns;

    /** Column names forming the primary key (from {@code owl:hasKey} triples). */
    private List<String>      primaryKeyColumns;

    /** Raw unique constraint messages from {@code sh:sparql} blank nodes. */
    private List<String>      uniqueConstraintMessages;

    /** Raw check constraint messages from {@code sh:sparql} blank nodes. */
    private List<String>      checkConstraintMessages;
}
