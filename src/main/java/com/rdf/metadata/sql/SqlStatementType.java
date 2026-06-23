package com.rdf.metadata.sql;

/**
 * Types of SQL statements the generator can produce from a SHACL model.
 */
public enum SqlStatementType {
    CREATE_TABLE,
    SELECT_ALL,
    SELECT_BY_PK,
    INSERT,
    UPDATE_BY_PK,
    DELETE_BY_PK
}
