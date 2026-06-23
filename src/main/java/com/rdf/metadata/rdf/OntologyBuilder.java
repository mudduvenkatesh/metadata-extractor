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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Transforms a {@link SchemaMetadata} instance into an RDF4J {@link Model}
 * containing OWL ontology statements.
 *
 * <p><b>Mapping strategy:</b>
 * <ul>
 *   <li>Each TABLE → {@code owl:Class}</li>
 *   <li>Each non-FK COLUMN → {@code owl:DatatypeProperty} with {@code rdfs:domain} (table class)
 *       and {@code rdfs:range} (XSD datatype IRI)</li>
 *   <li>Each FOREIGN KEY → {@code owl:ObjectProperty} with domain (FK table) and range (PK table)</li>
 *   <li>NOT NULL columns → {@code owl:Restriction} with {@code owl:minCardinality 1}
 *       and {@code owl:maxCardinality 1}</li>
 *   <li>PRIMARY KEY → {@code owl:hasKey} blank-node list on the class</li>
 *   <li>UNIQUE constraints → additional {@code owl:hasKey} lists</li>
 *   <li>CHECK constraints → {@code rdfs:comment} annotations on the class</li>
 * </ul>
 *
 * <p>All triple construction uses {@link SimpleValueFactory} — no Jena dependency.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OntologyBuilder {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    private final RdfProperties rdfProperties;

    /**
     * Build and return an RDF4J {@link Model} representing the OWL ontology.
     */
    public Model build(SchemaMetadata schema) {
        Model model = new LinkedHashModel();

        String base   = rdfProperties.getBaseNamespace();
        IRI ontIri    = iri(base + IriUtils.sanitise(schema.getDatabaseName())
                            + "/" + IriUtils.sanitise(schema.getSchemaName()));

        // ── Ontology header ──────────────────────────────────────────────────
        add(model, ontIri, RDF.TYPE,          OWL.ONTOLOGY);
        add(model, ontIri, RDFS.COMMENT,      lit("OWL ontology auto-generated from "
                + schema.getDatabaseType() + " schema " + schema.getSchemaName()));
        add(model, ontIri, OWL.VERSIONINFO,   lit("1.0"));

        // ── First pass: one owl:Class per table ──────────────────────────────
        Map<String, IRI> classMap = new HashMap<>();
        for (TableMetadata table : schema.getTables()) {
            IRI classIri = buildClass(model, table, base);
            classMap.put(table.getTableName(), classIri);
            log.debug("Created owl:Class <{}>", classIri);
        }

        // ── Second pass: properties + restrictions ───────────────────────────
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

        long datatypeProps = model.filter(null, RDF.TYPE, OWL.DATATYPEPROPERTY).size();
        long objectProps   = model.filter(null, RDF.TYPE, OWL.OBJECTPROPERTY).size();
        log.info("OWL ontology built: {} classes, {} datatype props, {} object props",
                classMap.size(), datatypeProps, objectProps);

        return model;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private builders
    // ─────────────────────────────────────────────────────────────────────────

    private IRI buildClass(Model model, TableMetadata table, String base) {
        IRI classIri = iri(IriUtils.classIri(base, table.getTableName()));

        add(model, classIri, RDF.TYPE,    OWL.CLASS);
        add(model, classIri, RDFS.LABEL,  lit(table.getTableName()));

        if (isSet(table.getRemarks())) {
            add(model, classIri, RDFS.COMMENT, lit(table.getRemarks()));
        }
        // Custom annotation property for table type (TABLE vs VIEW)
        IRI tableTypeProp = iri(base + "tableType");
        add(model, tableTypeProp, RDF.TYPE, OWL.ANNOTATIONPROPERTY);
        add(model, classIri, tableTypeProp, lit(table.getTableType()));

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

        // NOT NULL → owl:minCardinality 1 + owl:maxCardinality 1 restrictions
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

    /**
     * Encode {@code owl:hasKey} as an RDF list of property IRIs attached to the class.
     * RDF4J has no built-in OWL API, so we build the RDF list manually.
     *
     * <pre>
     * :MyClass owl:hasKey ( :MyClass_col1 :MyClass_col2 ) .
     * </pre>
     */
    private void addHasKey(Model model, IRI classIri, TableMetadata table,
                            List<String> columnNames, String base) {
        if (columnNames.isEmpty()) return;

        // Build the RDF list chain: first → rest → rest → rdf:nil
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

    /**
     * Add a cardinality restriction blank node as a superclass of {@code domainClass}.
     *
     * <pre>
     * :MyClass rdfs:subClassOf [
     *     a owl:Restriction ;
     *     owl:onProperty :prop ;
     *     owl:minCardinality "1"^^xsd:nonNegativeInteger
     * ] .
     * </pre>
     */
    private void addCardinalityRestriction(Model model, IRI domainClass,
                                            IRI propIri, IRI cardinalityProp, int card) {
        BNode restriction = bnode();
        add(model, restriction, RDF.TYPE,        OWL.RESTRICTION);
        add(model, restriction, OWL.ONPROPERTY,  propIri);
        add(model, restriction, cardinalityProp,
                VF.createLiteral(card, XSD.NON_NEGATIVE_INTEGER));
        add(model, domainClass, RDFS.SUBCLASSOF, restriction);
    }

    // ─── Convenience ─────────────────────────────────────────────────────────

    private static IRI iri(String uri) {
        return VF.createIRI(uri);
    }

    private static BNode bnode() {
        return VF.createBNode();
    }

    private static Literal lit(String value) {
        return VF.createLiteral(value);
    }

    private static void add(Model model, Resource s, IRI p, Value o) {
        model.add(s, p, o);
    }

    private static boolean isSet(String v) {
        return v != null && !v.isBlank();
    }

    private static boolean isForeignKeyColumn(String columnName, TableMetadata table) {
        return table.getForeignKeys().stream()
                .anyMatch(fk -> fk.getFkColumnName().equalsIgnoreCase(columnName));
    }
}
