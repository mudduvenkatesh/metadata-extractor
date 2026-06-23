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
 * Builds the <b>Schema Ontology</b> — an OWL domain ontology derived from
 * relational schema metadata.
 *
 * <h3>What this graph contains</h3>
 * <ul>
 *   <li>One {@code owl:Class} per table/view</li>
 *   <li>One {@code owl:DatatypeProperty} per non-FK column</li>
 *   <li>One {@code owl:ObjectProperty} per foreign key relationship</li>
 *   <li>{@code owl:minCardinality}/{@code owl:maxCardinality} restrictions for NOT NULL columns</li>
 *   <li>{@code owl:hasKey} for primary key and unique constraints</li>
 *   <li>{@code rdfs:comment} for CHECK constraints</li>
 * </ul>
 *
 * <h3>What this graph does NOT contain</h3>
 * <ul>
 *   <li>Any {@code dr:} instance data — that lives in the repo instance ontology</li>
 *   <li>Cross-graph links to the repo instance — those live in the linking ontology</li>
 * </ul>
 *
 * <h3>Ontology IRI pattern</h3>
 * <pre>{@code <base>ontology/<databaseName>/<schemaName> }</pre>
 * e.g. {@code <http://example.org/schema#ontology/MY_DATABASE/PUBLIC>}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchemaOntologyBuilder {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    private final RdfProperties rdfProperties;

    /**
     * Build the schema ontology from extracted relational metadata.
     *
     * @param schema    source relational metadata
     * @return an {@link OntologyGraph} containing the schema ontology IRI and model
     */
    public OntologyGraph build(SchemaMetadata schema) {
        Model model = new LinkedHashModel();
        String base = rdfProperties.getBaseNamespace();

        // ── Ontology header ───────────────────────────────────────────────────
        IRI ontIri = schemaOntIri(schema, base);
        add(model, ontIri, RDF.TYPE,        OWL.ONTOLOGY);
        add(model, ontIri, RDFS.LABEL,      lit("Schema Ontology: "
                + schema.getDatabaseName() + " / " + schema.getSchemaName()));
        add(model, ontIri, RDFS.COMMENT,    lit("OWL domain ontology auto-generated from "
                + schema.getDatabaseType() + " schema '" + schema.getSchemaName() + "'"));
        add(model, ontIri, OWL.VERSIONINFO, lit("1.0"));

        // owl:imports the vocabulary ontology (uses dr: properties for sourceTable links)
        add(model, ontIri, OWL.IMPORTS, RepoInstanceBuilder.repoVocabOntIri(base));

        setNamespaces(model, base);

        // ── First pass: one owl:Class per table ───────────────────────────────
        Map<String, IRI> classMap = new HashMap<>();
        for (TableMetadata table : schema.getTables()) {
            IRI classIri = buildClass(model, table, base);
            classMap.put(table.getTableName(), classIri);
        }

        // ── Second pass: properties and restrictions ──────────────────────────
        for (TableMetadata table : schema.getTables()) {
            IRI domainClass = classMap.get(table.getTableName());

            for (ColumnMetadata col : table.getColumns()) {
                if (isFkColumn(col.getColumnName(), table)) continue;
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

        long classes  = model.filter(null, RDF.TYPE, OWL.CLASS).size();
        long dtProps  = model.filter(null, RDF.TYPE, OWL.DATATYPEPROPERTY).size();
        long objProps = model.filter(null, RDF.TYPE, OWL.OBJECTPROPERTY).size();
        log.info("Schema ontology built: {} triples | {} classes | {} dtProps | {} objProps",
                model.size(), classes, dtProps, objProps);

        return new OntologyGraph(ontIri, model);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // owl:Class
    // ─────────────────────────────────────────────────────────────────────────

    private IRI buildClass(Model model, TableMetadata table, String base) {
        IRI classIri = iri(IriUtils.classIri(base, table.getTableName()));

        add(model, classIri, RDF.TYPE,   OWL.CLASS);
        add(model, classIri, RDFS.LABEL, lit(table.getTableName()));
        if (isSet(table.getRemarks())) {
            add(model, classIri, RDFS.COMMENT, lit(table.getRemarks()));
        }

        // Annotate table type (TABLE vs VIEW) using a local annotation property
        IRI tableTypeProp = iri(base + "tableType");
        add(model, tableTypeProp, RDF.TYPE,   OWL.ANNOTATIONPROPERTY);
        add(model, tableTypeProp, RDFS.LABEL, lit("tableType"));
        add(model, classIri, tableTypeProp, lit(table.getTableType()));

        return classIri;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // owl:DatatypeProperty
    // ─────────────────────────────────────────────────────────────────────────

    private void buildDatatypeProperty(Model model, TableMetadata table,
                                        ColumnMetadata col, IRI domain, String base) {
        IRI propIri  = iri(IriUtils.dataPropertyIri(base, table.getTableName(), col.getColumnName()));
        IRI xsdRange = XsdTypeMapper.toXsd(col.getDataType());

        add(model, propIri, RDF.TYPE,    OWL.DATATYPEPROPERTY);
        add(model, propIri, RDFS.LABEL,  lit(col.getColumnName()));
        add(model, propIri, RDFS.DOMAIN, domain);
        add(model, propIri, RDFS.RANGE,  xsdRange);
        if (isSet(col.getRemarks())) add(model, propIri, RDFS.COMMENT, lit(col.getRemarks()));

        if (!col.isNullable()) {
            addCardinalityRestriction(model, domain, propIri, OWL.MINCARDINALITY, 1);
            addCardinalityRestriction(model, domain, propIri, OWL.MAXCARDINALITY, 1);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // owl:ObjectProperty
    // ─────────────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────────────
    // owl:hasKey (RDF list)
    // ─────────────────────────────────────────────────────────────────────────

    private void addHasKey(Model model, IRI classIri, TableMetadata table,
                            List<String> columnNames, String base) {
        if (columnNames.isEmpty()) return;
        BNode head = bnode(), current = head;
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

    // ─────────────────────────────────────────────────────────────────────────
    // Cardinality restriction
    // ─────────────────────────────────────────────────────────────────────────

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
    // IRI helpers (package-visible so the linker can reference them)
    // ─────────────────────────────────────────────────────────────────────────

    static IRI schemaOntIri(SchemaMetadata schema, String base) {
        return SimpleValueFactory.getInstance().createIRI(
                base + "ontology/" + IriUtils.sanitise(schema.getDatabaseName())
                + "/" + IriUtils.sanitise(schema.getSchemaName()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────────────────────────────────

    private void setNamespaces(Model model, String base) {
        model.setNamespace("schema", base);
        model.setNamespace("dr",     DataRepositoryVocabulary.NS);
        model.setNamespace("owl",    OWL.NAMESPACE);
        model.setNamespace("rdfs",   RDFS.NAMESPACE);
        model.setNamespace("xsd",    XSD.NAMESPACE);
    }

    private static IRI iri(String uri)          { return VF.createIRI(uri); }
    private static BNode bnode()                { return VF.createBNode(); }
    private static Literal lit(String v)        { return VF.createLiteral(v); }
    private static void add(Model m, Resource s, IRI p, Value o) { m.add(s, p, o); }
    private static boolean isSet(String v)      { return v != null && !v.isBlank(); }

    private static boolean isFkColumn(String colName, TableMetadata table) {
        return table.getForeignKeys().stream()
                .anyMatch(fk -> fk.getFkColumnName().equalsIgnoreCase(colName));
    }
}
