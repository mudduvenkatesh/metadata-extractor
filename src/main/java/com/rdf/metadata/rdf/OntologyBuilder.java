package com.rdf.metadata.rdf;

import com.rdf.metadata.config.RdfProperties;
import com.rdf.metadata.model.*;
import com.rdf.metadata.util.IriUtils;
import com.rdf.metadata.util.XsdTypeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.model.*;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.*;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.rdf.metadata.rdf.DataRepositoryVocabulary.*;

/**
 * Transforms a {@link SchemaMetadata} instance into an RDF4J {@link Model}
 * containing an OWL ontology enriched with a {@code dr:DataRepository} instance graph.
 *
 * <h3>Graph structure</h3>
 *
 * <p><b>1. dr: Vocabulary</b> (emitted by {@link DataRepositoryVocabularyBuilder})<br>
 * Full {@code owl:Class}, {@code owl:DatatypeProperty}, and {@code owl:ObjectProperty}
 * declarations for every term in the {@code dr:} namespace — the graph is self-describing.
 *
 * <p><b>2. DataRepository instance</b><br>
 * A concrete {@code dr:DataRepository} individual representing the source database,
 * with all connection properties stored as RDF literal statements:
 * <pre>{@code
 * dr:MyDatabase_PUBLIC  a               dr:DataRepository ;
 *                       dr:databaseType "SNOWFLAKE" ;
 *                       dr:databaseName "MY_DATABASE" ;
 *                       dr:warehouse    "COMPUTE_WH" ;
 *                       dr:role         "SYSADMIN" ;
 *                       dr:authMode     "KEY_PAIR" ;
 *                       dr:extractedAt  "2026-06-23T10:00:00Z"^^xsd:dateTime ;
 *                       dr:hasSchema    dr:MyDatabase_PUBLIC_Schema .
 * }</pre>
 *
 * <p><b>3. DatabaseSchema instance</b><br>
 * <pre>{@code
 * dr:MyDatabase_PUBLIC_Schema  a            dr:DatabaseSchema ;
 *                               dr:schemaName "PUBLIC" ;
 *                               dr:hasTable   dr:Orders_TableNode ,
 *                                             dr:Customers_TableNode .
 * }</pre>
 *
 * <p><b>4. DatabaseTable instances</b><br>
 * One {@code dr:DatabaseTable} individual per table, linked to column individuals:
 * <pre>{@code
 * dr:Orders_TableNode  a              dr:DatabaseTable ;
 *                      dr:tableName   "ORDERS" ;
 *                      dr:tableType   "TABLE" ;
 *                      dr:hasColumn   dr:Orders_OrderId_ColNode ,
 *                                     dr:Orders_Status_ColNode .
 * }</pre>
 *
 * <p><b>5. TableColumn instances</b><br>
 * One {@code dr:TableColumn} individual per column, with full property metadata:
 * <pre>{@code
 * dr:Orders_OrderId_ColNode  a                  dr:TableColumn ;
 *                             dr:columnName      "ORDER_ID" ;
 *                             dr:sqlDataType     "BIGINT" ;
 *                             dr:ordinalPosition 1 ;
 *                             dr:isNullable      false ;
 *                             dr:isPrimaryKey    true ;
 *                             dr:isForeignKey    false .
 * }</pre>
 *
 * <p><b>6. OWL ontology axioms</b><br>
 * Standard OWL mapping (unchanged from previous version):
 * {@code owl:Class}, {@code owl:DatatypeProperty}, {@code owl:ObjectProperty},
 * cardinality restrictions, {@code owl:hasKey}.
 */
/**
 * @deprecated Use {@link OntologyOrchestrator} which produces four separated,
 *             independently loadable ontology graphs. This class now serves
 *             as a reference only and is no longer wired into the service pipeline.
 */
@Deprecated(since = "2.0", forRemoval = true)
@Slf4j
@Component
@RequiredArgsConstructor
public class OntologyBuilder {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final DateTimeFormatter ISO_DT = DateTimeFormatter.ISO_INSTANT;

    private final RdfProperties                  rdfProperties;
    private final DataRepositoryVocabularyBuilder vocabularyBuilder;

    /**
     * Build and return an RDF4J {@link Model} containing:
     * <ol>
     *   <li>The {@code dr:} vocabulary declarations</li>
     *   <li>A {@code dr:DataRepository} instance graph describing the source database</li>
     *   <li>OWL ontology axioms (classes, properties, restrictions)</li>
     * </ol>
     */
    public Model build(SchemaMetadata schema) {
        Model model = new LinkedHashModel();
        String base = rdfProperties.getBaseNamespace();

        // ── 1. Emit dr: vocabulary ────────────────────────────────────────────
        vocabularyBuilder.emitVocabulary(model);

        // ── 2. Ontology header ────────────────────────────────────────────────
        IRI ontIri = iri(base + IriUtils.sanitise(schema.getDatabaseName())
                       + "/" + IriUtils.sanitise(schema.getSchemaName()));

        add(model, ontIri, RDF.TYPE,        OWL.ONTOLOGY);
        add(model, ontIri, RDFS.COMMENT,    lit("OWL ontology auto-generated from "
                + schema.getDatabaseType() + " schema " + schema.getSchemaName()));
        add(model, ontIri, OWL.VERSIONINFO, lit("1.0"));
        model.setNamespace("dr",     DataRepositoryVocabulary.NS);
        model.setNamespace("schema", base);
        model.setNamespace("owl",    OWL.NAMESPACE);
        model.setNamespace("rdfs",   RDFS.NAMESPACE);
        model.setNamespace("xsd",    XSD.NAMESPACE);

        // ── 3. DataRepository instance ────────────────────────────────────────
        IRI repoIri = buildDataRepositoryInstance(model, schema, base);

        // ── 4. DatabaseSchema instance ────────────────────────────────────────
        IRI schemaIri = buildDatabaseSchemaInstance(model, schema, repoIri, base);

        // ── 5. DatabaseTable + TableColumn instances ─────────────────────────
        for (TableMetadata table : schema.getTables()) {
            buildDatabaseTableInstance(model, table, schemaIri, base);
        }

        // ── 6. OWL axioms: classes, properties, restrictions ─────────────────
        Map<String, IRI> classMap = new HashMap<>();
        for (TableMetadata table : schema.getTables()) {
            IRI classIri = buildOwlClass(model, table, base);
            classMap.put(table.getTableName(), classIri);
        }

        for (TableMetadata table : schema.getTables()) {
            IRI domainClass = classMap.get(table.getTableName());

            for (ColumnMetadata col : table.getColumns()) {
                if (isForeignKeyColumn(col.getColumnName(), table)) continue;
                buildDatatypeProperty(model, table, col, domainClass, base);
            }

            for (ForeignKeyMetadata fk : table.getForeignKeys()) {
                IRI rangeClass = classMap.get(fk.getPkTableName());
                if (rangeClass == null) {
                    log.warn("FK references table not in schema: {}", fk.getPkTableName());
                    continue;
                }
                buildObjectProperty(model, fk, domainClass, rangeClass, base);
            }

            if (!table.getPrimaryKeyColumns().isEmpty()) {
                addHasKey(model, domainClass, table, table.getPrimaryKeyColumns(), base);
            }

            for (UniqueConstraintMetadata uc : table.getUniqueConstraints()) {
                addHasKey(model, domainClass, table, uc.getColumns(), base);
            }

            for (CheckConstraintMetadata cc : table.getCheckConstraints()) {
                add(model, domainClass, RDFS.COMMENT,
                        lit("CHECK[" + cc.getConstraintName() + "]: " + cc.getCheckClause()));
            }
        }

        long classes   = model.filter(null, RDF.TYPE, OWL.CLASS).size();
        long dtProps   = model.filter(null, RDF.TYPE, OWL.DATATYPEPROPERTY).size();
        long objProps  = model.filter(null, RDF.TYPE, OWL.OBJECTPROPERTY).size();
        log.info("Ontology built: {} triples | {} classes | {} dtProps | {} objProps",
                model.size(), classes, dtProps, objProps);

        return model;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. DataRepository instance
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Emit a {@code dr:DataRepository} individual whose properties come from
     * {@link SchemaMetadata}'s connection fields — stored entirely as RDF statements.
     */
    private IRI buildDataRepositoryInstance(Model model, SchemaMetadata schema, String base) {
        String repoLocalName = IriUtils.sanitise(schema.getDatabaseName())
                             + "_" + IriUtils.sanitise(schema.getSchemaName())
                             + "_Repository";
        IRI repoIri = iri(base + repoLocalName);

        add(model, repoIri, RDF.TYPE,      DataRepository);
        add(model, repoIri, RDFS.LABEL,    lit(schema.getDatabaseName()
                + " / " + schema.getSchemaName()));

        // Connection properties stored as RDF literal statements
        addIfSet(model, repoIri, databaseType, schema.getDatabaseType());
        addIfSet(model, repoIri, databaseName, schema.getDatabaseName());
        addIfSet(model, repoIri, host,         schema.getHost());
        addIfSet(model, repoIri, jdbcUrl,      schema.getJdbcUrl());
        addIfSet(model, repoIri, warehouse,    schema.getWarehouse());
        addIfSet(model, repoIri, role,         schema.getRole());
        addIfSet(model, repoIri, authMode,     schema.getAuthMode());

        if (schema.getPort() != null) {
            add(model, repoIri, port, VF.createLiteral(schema.getPort()));
        }
        if (schema.getExtractedAt() != null) {
            add(model, repoIri, extractedAt,
                    VF.createLiteral(ISO_DT.format(schema.getExtractedAt()),
                            XSD.DATETIME));
        }

        log.debug("Created dr:DataRepository instance <{}>", repoIri);
        return repoIri;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. DatabaseSchema instance
    // ─────────────────────────────────────────────────────────────────────────

    private IRI buildDatabaseSchemaInstance(Model model, SchemaMetadata schema,
                                             IRI repoIri, String base) {
        IRI schemaIri = iri(base + IriUtils.sanitise(schema.getDatabaseName())
                          + "_" + IriUtils.sanitise(schema.getSchemaName()) + "_Schema");

        add(model, schemaIri, RDF.TYPE,   DatabaseSchema);
        add(model, schemaIri, RDFS.LABEL, lit(schema.getSchemaName()));
        add(model, schemaIri, schemaName, lit(schema.getSchemaName()));

        // Link repository → schema
        add(model, repoIri, hasSchema, schemaIri);

        log.debug("Created dr:DatabaseSchema instance <{}>", schemaIri);
        return schemaIri;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. DatabaseTable + TableColumn instances
    // ─────────────────────────────────────────────────────────────────────────

    private void buildDatabaseTableInstance(Model model, TableMetadata table,
                                             IRI schemaIri, String base) {
        IRI tableNodeIri = iri(base + IriUtils.toPascalCase(table.getTableName()) + "_TableNode");

        add(model, tableNodeIri, RDF.TYPE,    DatabaseTable);
        add(model, tableNodeIri, RDFS.LABEL,  lit(table.getTableName()));
        add(model, tableNodeIri, tableName,   lit(table.getTableName()));
        addIfSet(model, tableNodeIri, tableType,    table.getTableType());
        addIfSet(model, tableNodeIri, tableRemarks, table.getRemarks());

        // Link schema → table
        add(model, schemaIri, hasTable, tableNodeIri);

        // Build column individuals
        for (ColumnMetadata col : table.getColumns()) {
            buildTableColumnInstance(model, col, table, tableNodeIri, base);
        }

        log.debug("Created dr:DatabaseTable instance <{}>", tableNodeIri);
    }

    private void buildTableColumnInstance(Model model, ColumnMetadata col,
                                           TableMetadata table, IRI tableNodeIri, String base) {
        IRI colNodeIri = iri(base + IriUtils.toPascalCase(table.getTableName())
                           + "_" + IriUtils.toPascalCase(col.getColumnName()) + "_ColNode");

        boolean fkCol = isForeignKeyColumn(col.getColumnName(), table);

        add(model, colNodeIri, RDF.TYPE,         TableColumn);
        add(model, colNodeIri, RDFS.LABEL,       lit(col.getColumnName()));
        add(model, colNodeIri, columnName,       lit(col.getColumnName()));
        addIfSet(model, colNodeIri, sqlDataType, col.getDataType());
        add(model, colNodeIri, ordinalPosition,  VF.createLiteral(col.getOrdinalPosition()));
        add(model, colNodeIri, isNullable,       VF.createLiteral(col.isNullable()));
        add(model, colNodeIri, isPrimaryKey,     VF.createLiteral(col.isPrimaryKey()));
        add(model, colNodeIri, isForeignKey,     VF.createLiteral(fkCol));

        if (col.getCharacterMaxLength() != null) {
            add(model, colNodeIri, maxLength, VF.createLiteral(col.getCharacterMaxLength()));
        }
        if (col.getNumericPrecision() != null) {
            add(model, colNodeIri, numericPrecision, VF.createLiteral(col.getNumericPrecision()));
        }
        if (col.getNumericScale() != null) {
            add(model, colNodeIri, numericScale, VF.createLiteral(col.getNumericScale()));
        }
        addIfSet(model, colNodeIri, columnRemarks, col.getRemarks());
        addIfSet(model, colNodeIri, defaultValue,  col.getDefaultValue());

        // Link table → column
        add(model, tableNodeIri, hasColumn, colNodeIri);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. OWL axioms (class, datatype property, object property, restrictions)
    // ─────────────────────────────────────────────────────────────────────────

    private IRI buildOwlClass(Model model, TableMetadata table, String base) {
        IRI classIri = iri(IriUtils.classIri(base, table.getTableName()));

        add(model, classIri, RDF.TYPE,   OWL.CLASS);
        add(model, classIri, RDFS.LABEL, lit(table.getTableName()));

        if (isSet(table.getRemarks())) {
            add(model, classIri, RDFS.COMMENT, lit(table.getRemarks()));
        }

        // Link the OWL class back to the DataRepository table individual
        IRI tableNodeIri = iri(base + IriUtils.toPascalCase(table.getTableName()) + "_TableNode");
        IRI describedBy  = iri(base + "describedBy");
        add(model, describedBy, RDF.TYPE,   OWL.ANNOTATIONPROPERTY);
        add(model, describedBy, RDFS.LABEL, lit("describedBy"));
        add(model, classIri, describedBy, tableNodeIri);

        return classIri;
    }

    private void buildDatatypeProperty(Model model, TableMetadata table,
                                        ColumnMetadata col, IRI domain, String base) {
        IRI propIri  = iri(IriUtils.dataPropertyIri(base, table.getTableName(), col.getColumnName()));
        IRI xsdRange = XsdTypeMapper.toXsd(col.getDataType());

        add(model, propIri, RDF.TYPE,    OWL.DATATYPEPROPERTY);
        add(model, propIri, RDFS.LABEL,  lit(col.getColumnName()));
        add(model, propIri, RDFS.DOMAIN, domain);
        add(model, propIri, RDFS.RANGE,  xsdRange);

        if (isSet(col.getRemarks())) {
            add(model, propIri, RDFS.COMMENT, lit(col.getRemarks()));
        }

        if (!col.isNullable()) {
            addCardinalityRestriction(model, domain, propIri, OWL.MINCARDINALITY, 1);
            addCardinalityRestriction(model, domain, propIri, OWL.MAXCARDINALITY, 1);
        }
    }

    private void buildObjectProperty(Model model, ForeignKeyMetadata fk,
                                      IRI domain, IRI range, String base) {
        IRI propIri = iri(IriUtils.objectPropertyIri(base, fk.getFkTableName(), fk.getPkTableName()));

        add(model, propIri, RDF.TYPE,    OWL.OBJECTPROPERTY);
        add(model, propIri, RDFS.LABEL,  lit("references" + IriUtils.toPascalCase(fk.getPkTableName())));
        add(model, propIri, RDFS.DOMAIN, domain);
        add(model, propIri, RDFS.RANGE,  range);

        if (isSet(fk.getConstraintName())) {
            add(model, propIri, RDFS.COMMENT,
                    lit("FK: " + fk.getConstraintName()
                        + " | UPDATE=" + fk.getUpdateRule()
                        + " | DELETE=" + fk.getDeleteRule()));
        }
    }

    private void addHasKey(Model model, IRI classIri, TableMetadata table,
                            List<String> columnNames, String base) {
        if (columnNames.isEmpty()) return;

        BNode head = bnode();
        BNode current = head;

        for (int i = 0; i < columnNames.size(); i++) {
            IRI propIri = iri(IriUtils.dataPropertyIri(base, table.getTableName(), columnNames.get(i)));
            add(model, current, RDF.FIRST, propIri);

            if (i < columnNames.size() - 1) {
                BNode next = bnode();
                add(model, current, RDF.REST, next);
                current = next;
            } else {
                add(model, current, RDF.REST, RDF.NIL);
            }
        }

        add(model, classIri, OWL.HASKEY, head);
    }

    private void addCardinalityRestriction(Model model, IRI domainClass,
                                            IRI propIri, IRI cardinalityProp, int card) {
        BNode restriction = bnode();
        add(model, restriction, RDF.TYPE,       OWL.RESTRICTION);
        add(model, restriction, OWL.ONPROPERTY, propIri);
        add(model, restriction, cardinalityProp,
                VF.createLiteral(card, XSD.NON_NEGATIVE_INTEGER));
        add(model, domainClass, RDFS.SUBCLASSOF, restriction);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────────────────────────────────

    private static IRI iri(String uri)          { return VF.createIRI(uri); }
    private static BNode bnode()                { return VF.createBNode(); }
    private static Literal lit(String value)    { return VF.createLiteral(value); }

    private static void add(Model m, Resource s, IRI p, Value o) { m.add(s, p, o); }

    private void addIfSet(Model model, IRI subject, IRI predicate, String value) {
        if (isSet(value)) add(model, subject, predicate, lit(value));
    }

    private static boolean isSet(String v)      { return v != null && !v.isBlank(); }

    private static boolean isForeignKeyColumn(String columnName, TableMetadata table) {
        return table.getForeignKeys().stream()
                .anyMatch(fk -> fk.getFkColumnName().equalsIgnoreCase(columnName));
    }
}
