package com.rdf.metadata.sql;

import lombok.Builder;
import lombok.Data;

/**
 * Intermediate representation of a column parsed from a SHACL {@code sh:PropertyShape}.
 *
 * <p>This is an internal value object used by {@link ShaclModelReader} and consumed
 * by {@link DynamicSqlGenerator}. It contains every piece of information derivable
 * from the shape model that is relevant for SQL generation:
 *
 * <ul>
 *   <li>{@code columnName}    — from {@code sh:name}</li>
 *   <li>{@code xsdDatatype}   — from {@code sh:datatype} (full XSD IRI string)</li>
 *   <li>{@code isForeignKey}  — true when {@code sh:class} is present instead of {@code sh:datatype}</li>
 *   <li>{@code referencedTable} — the PK table name when {@code isForeignKey == true}</li>
 *   <li>{@code nullable}      — derived from absence of {@code sh:minCount 1}</li>
 *   <li>{@code maxLength}     — from {@code sh:maxLength} (null if not constrained)</li>
 *   <li>{@code isPrimaryKey}  — populated by cross-referencing {@code owl:hasKey} on the class</li>
 * </ul>
 */
@Data
@Builder
public class ShapeColumn {

    private String  columnName;
    private String  xsdDatatype;       // full XSD IRI, e.g. "http://www.w3.org/2001/XMLSchema#string"
    private boolean isForeignKey;
    private String  referencedTable;   // only set when isForeignKey == true
    private boolean nullable;          // true = nullable (no sh:minCount 1)
    private Integer maxLength;         // from sh:maxLength, or null
    private boolean isPrimaryKey;      // cross-referenced from owl:hasKey
}
