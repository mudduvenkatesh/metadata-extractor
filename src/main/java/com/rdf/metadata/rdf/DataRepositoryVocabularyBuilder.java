package com.rdf.metadata.rdf;

import org.eclipse.rdf4j.model.*;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.*;
import org.springframework.stereotype.Component;

import static com.rdf.metadata.rdf.DataRepositoryVocabulary.*;

/**
 * Writes the {@code dr:} vocabulary itself into an RDF4J {@link Model} as
 * first-class RDF statements.
 *
 * <p>After calling {@link #emitVocabulary}, the model will contain full
 * {@code owl:Class}, {@code owl:ObjectProperty}, and {@code owl:DatatypeProperty}
 * declarations for every term in {@link DataRepositoryVocabulary}, each with
 * {@code rdfs:label}, {@code rdfs:comment}, {@code rdfs:domain}, and
 * {@code rdfs:range} triples.
 *
 * <p>This means a SPARQL query or any RDF consumer can discover the vocabulary
 * by querying the model — no out-of-band documentation needed.
 *
 * <h3>Example triples emitted</h3>
 * <pre>{@code
 * dr:DataRepository  a              owl:Class ;
 *                    rdfs:label     "DataRepository" ;
 *                    rdfs:comment   "A connected relational database source." .
 *
 * dr:databaseType    a              owl:DatatypeProperty ;
 *                    rdfs:label     "databaseType" ;
 *                    rdfs:comment   "Database engine type (SNOWFLAKE, POSTGRESQL, …)" ;
 *                    rdfs:domain    dr:DataRepository ;
 *                    rdfs:range     xsd:string .
 *
 * dr:hasSchema       a              owl:ObjectProperty ;
 *                    rdfs:label     "hasSchema" ;
 *                    rdfs:domain    dr:DataRepository ;
 *                    rdfs:range     dr:DatabaseSchema .
 * }</pre>
 */
@Component
public class DataRepositoryVocabularyBuilder {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    /**
     * Emit all {@code dr:} vocabulary declarations into the given model.
     * Safe to call on an existing model — triples are simply added.
     */
    public void emitVocabulary(Model model) {
        emitClasses(model);
        emitObjectProperties(model);
        emitDatatypeProperties(model);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Classes
    // ─────────────────────────────────────────────────────────────────────────

    private void emitClasses(Model model) {

        owlClass(model, DataRepository,
                "DataRepository",
                "A connected relational database source from which schema metadata has been extracted.");

        owlClass(model, DatabaseSchema,
                "DatabaseSchema",
                "A named schema within a DataRepository (e.g. PUBLIC, dbo).");

        owlClass(model, DatabaseTable,
                "DatabaseTable",
                "A table or view within a DatabaseSchema.");

        owlClass(model, TableColumn,
                "TableColumn",
                "A column within a DatabaseTable, including its data type and constraints.");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Object properties (structural links between classes)
    // ─────────────────────────────────────────────────────────────────────────

    private void emitObjectProperties(Model model) {

        objectProp(model, hasSchema,
                "hasSchema",
                "Links a DataRepository to its DatabaseSchema.",
                DataRepository, DatabaseSchema);

        objectProp(model, hasTable,
                "hasTable",
                "Links a DatabaseSchema to a DatabaseTable.",
                DatabaseSchema, DatabaseTable);

        objectProp(model, hasColumn,
                "hasColumn",
                "Links a DatabaseTable to a TableColumn.",
                DatabaseTable, TableColumn);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Datatype properties (literal-valued attributes)
    // ─────────────────────────────────────────────────────────────────────────

    private void emitDatatypeProperties(Model model) {

        // ── DataRepository connection properties ──────────────────────────────
        dtProp(model, databaseType,   "databaseType",
                "Database engine type (SNOWFLAKE, POSTGRESQL, …).",
                DataRepository, XSD.STRING);

        dtProp(model, host,            "host",
                "Hostname or IP address of the database server.",
                DataRepository, XSD.STRING);

        dtProp(model, port,            "port",
                "TCP port number of the database server.",
                DataRepository, XSD.INTEGER);

        dtProp(model, databaseName,    "databaseName",
                "Database / catalog name.",
                DataRepository, XSD.STRING);

        dtProp(model, jdbcUrl,         "jdbcUrl",
                "Full JDBC connection URL, if provided directly.",
                DataRepository, XSD.STRING);

        dtProp(model, warehouse,       "warehouse",
                "Snowflake virtual warehouse name.",
                DataRepository, XSD.STRING);

        dtProp(model, role,            "role",
                "Snowflake role used for the connection.",
                DataRepository, XSD.STRING);

        dtProp(model, authMode,        "authMode",
                "Authentication mode: PASSWORD or KEY_PAIR.",
                DataRepository, XSD.STRING);

        dtProp(model, extractedAt,     "extractedAt",
                "ISO-8601 timestamp of when the metadata was extracted.",
                DataRepository, XSD.DATETIME);

        // ── DatabaseSchema properties ─────────────────────────────────────────
        dtProp(model, schemaName,      "schemaName",
                "SQL schema name (e.g. PUBLIC).",
                DatabaseSchema, XSD.STRING);

        // ── DatabaseTable properties ──────────────────────────────────────────
        dtProp(model, tableName,       "tableName",
                "SQL table name.",
                DatabaseTable, XSD.STRING);

        dtProp(model, tableType,       "tableType",
                "Object type: TABLE or VIEW.",
                DatabaseTable, XSD.STRING);

        dtProp(model, tableRemarks,    "tableRemarks",
                "Optional comment or description on the table.",
                DatabaseTable, XSD.STRING);

        // ── TableColumn properties ────────────────────────────────────────────
        dtProp(model, columnName,      "columnName",
                "SQL column name.",
                TableColumn, XSD.STRING);

        dtProp(model, sqlDataType,     "sqlDataType",
                "SQL type name as reported by the database driver (e.g. VARCHAR, BIGINT).",
                TableColumn, XSD.STRING);

        dtProp(model, ordinalPosition, "ordinalPosition",
                "1-based ordinal position of the column within its table.",
                TableColumn, XSD.INTEGER);

        dtProp(model, isNullable,      "isNullable",
                "True if the column allows NULL values.",
                TableColumn, XSD.BOOLEAN);

        dtProp(model, isPrimaryKey,    "isPrimaryKey",
                "True if the column is part of the primary key.",
                TableColumn, XSD.BOOLEAN);

        dtProp(model, isForeignKey,    "isForeignKey",
                "True if the column is a foreign key referencing another table.",
                TableColumn, XSD.BOOLEAN);

        dtProp(model, maxLength,       "maxLength",
                "Maximum character length for string columns.",
                TableColumn, XSD.INTEGER);

        dtProp(model, numericPrecision,"numericPrecision",
                "Total number of significant digits for numeric columns.",
                TableColumn, XSD.INTEGER);

        dtProp(model, numericScale,    "numericScale",
                "Number of digits to the right of the decimal point for numeric columns.",
                TableColumn, XSD.INTEGER);

        dtProp(model, columnRemarks,   "columnRemarks",
                "Optional comment or description on the column.",
                TableColumn, XSD.STRING);

        dtProp(model, defaultValue,    "defaultValue",
                "Default value expression for the column.",
                TableColumn, XSD.STRING);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Emit helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void owlClass(Model model, IRI classIri, String label, String comment) {
        add(model, classIri, RDF.TYPE,      OWL.CLASS);
        add(model, classIri, RDFS.LABEL,    lit(label));
        add(model, classIri, RDFS.COMMENT,  lit(comment));
    }

    private void objectProp(Model model, IRI propIri, String label, String comment,
                             IRI domain, IRI range) {
        add(model, propIri, RDF.TYPE,      OWL.OBJECTPROPERTY);
        add(model, propIri, RDFS.LABEL,    lit(label));
        add(model, propIri, RDFS.COMMENT,  lit(comment));
        add(model, propIri, RDFS.DOMAIN,   domain);
        add(model, propIri, RDFS.RANGE,    range);
    }

    private void dtProp(Model model, IRI propIri, String label, String comment,
                         IRI domain, IRI range) {
        add(model, propIri, RDF.TYPE,      OWL.DATATYPEPROPERTY);
        add(model, propIri, RDFS.LABEL,    lit(label));
        add(model, propIri, RDFS.COMMENT,  lit(comment));
        add(model, propIri, RDFS.DOMAIN,   domain);
        add(model, propIri, RDFS.RANGE,    range);
    }

    private static void add(Model m, Resource s, IRI p, Value o) {
        m.add(s, p, o);
    }

    private static Literal lit(String value) {
        return VF.createLiteral(value);
    }
}
