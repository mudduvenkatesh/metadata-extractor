package com.rdf.metadata.shacl;

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

/**
 * Builds a SHACL shapes graph as an RDF4J {@link Model} from a {@link SchemaMetadata} object.
 *
 * <h3>Mapping strategy</h3>
 * <ul>
 *   <li>Each TABLE → named {@code sh:NodeShape} IRI: {@code <base>OrdersShape}</li>
 *   <li>Each non-FK COLUMN → named {@code sh:PropertyShape} IRI:
 *       {@code <base>OrdersShape/orderDateShape}</li>
 *   <li>Each FK COLUMN → named {@code sh:PropertyShape} IRI:
 *       {@code <base>OrdersShape/hasCustomersShape}</li>
 *   <li>Each CHECK/UNIQUE constraint → named {@code sh:SPARQLConstraint} IRI:
 *       {@code <base>OrdersShape/constraint/CHK_PRICE}</li>
 * </ul>
 *
 * <h3>Why named IRIs instead of blank nodes</h3>
 * <p>Blank nodes are anonymous — once serialized they cannot be referenced,
 * queried, or hyperlinked. Named PropertyShape IRIs enable:
 * <ul>
 *   <li>Direct HTTP dereference of any individual property constraint</li>
 *   <li>SPARQL {@code BIND} / {@code VALUES} targeting a specific shape</li>
 *   <li>Cross-graph {@code sh:property <OrdersShape/orderDateShape>} reuse</li>
 *   <li>Human-readable shape names in validation reports</li>
 *   <li>GraphDB / Stardog visual browsers can show the shape graph as a linked tree</li>
 * </ul>
 *
 * <h3>IRI patterns</h3>
 * <pre>{@code
 * NodeShape:           <base>OrdersShape
 * Data PropertyShape:  <base>OrdersShape/orderDateShape
 * FK PropertyShape:    <base>OrdersShape/hasCustomersShape
 * Constraint:          <base>OrdersShape/constraint/FK_ORDERS_CUSTOMER
 * }</pre>
 *
 * <p>Uses only RDF4J {@link SimpleValueFactory} — no Apache Jena dependency.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShaclShapesBuilder {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    private final RdfProperties rdfProperties;

    /**
     * Build and return an RDF4J {@link Model} containing all SHACL shapes.
     * Every shape node is a named IRI — no blank nodes are emitted.
     */
    public Model build(SchemaMetadata schema) {
        Model  model = new LinkedHashModel();
        String base  = rdfProperties.getBaseNamespace();

        int shapeCount = 0;
        for (TableMetadata table : schema.getTables()) {
            IRI nodeShapeIri = buildNodeShape(model, table, base);
            buildPropertyShapes(model, nodeShapeIri, table, base);
            addConstraintShapes(model, nodeShapeIri, table, base);
            shapeCount++;
        }

        log.info("SHACL shapes built: {} NodeShapes for schema '{}'",
                shapeCount, schema.getSchemaName());
        return model;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NodeShape
    // ─────────────────────────────────────────────────────────────────────────

    private IRI buildNodeShape(Model model, TableMetadata table, String base) {
        IRI shapeIri = iri(IriUtils.nodeShapeIri(base, table.getTableName()));
        IRI classIri = iri(IriUtils.classIri(base, table.getTableName()));

        add(model, shapeIri, RDF.TYPE,           SHACL.NODE_SHAPE);
        add(model, shapeIri, SHACL.TARGET_CLASS, classIri);
        add(model, shapeIri, RDFS.LABEL,         lit(table.getTableName() + "Shape"));

        if (isSet(table.getRemarks())) {
            add(model, shapeIri, SHACL.DESCRIPTION, lit(table.getRemarks()));
        }
        return shapeIri;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PropertyShapes — named IRIs, not blank nodes
    // ─────────────────────────────────────────────────────────────────────────

    private void buildPropertyShapes(Model model, IRI nodeShapeIri,
                                      TableMetadata table, String base) {
        for (ColumnMetadata col : table.getColumns()) {

            boolean isFkCol = isForeignKeyColumn(col.getColumnName(), table);

            // ── Named IRI for the PropertyShape ──────────────────────────────
            IRI propShapeIri;
            if (isFkCol) {
                ForeignKeyMetadata fk = getFkForColumn(col.getColumnName(), table);
                propShapeIri = iri(IriUtils.fkPropertyShapeIri(base,
                        fk.getFkTableName(), fk.getPkTableName()));
            } else {
                propShapeIri = iri(IriUtils.propertyShapeIri(base,
                        table.getTableName(), col.getColumnName()));
            }

            // ── PropertyShape declarations ────────────────────────────────────
            add(model, propShapeIri, RDF.TYPE,    SHACL.PROPERTY_SHAPE);
            add(model, propShapeIri, SHACL.NAME,  lit(col.getColumnName()));
            add(model, propShapeIri, RDFS.LABEL,
                    lit(table.getTableName() + "." + col.getColumnName() + " shape"));

            if (isSet(col.getRemarks())) {
                add(model, propShapeIri, SHACL.DESCRIPTION, lit(col.getRemarks()));
            }

            if (isFkCol) {
                ForeignKeyMetadata fk = getFkForColumn(col.getColumnName(), table);
                IRI pathIri  = iri(IriUtils.objectPropertyIri(base,
                        fk.getFkTableName(), fk.getPkTableName()));
                IRI rangeIri = iri(IriUtils.classIri(base, fk.getPkTableName()));

                add(model, propShapeIri, SHACL.PATH,      pathIri);
                add(model, propShapeIri, SHACL.CLASS,     rangeIri);
                add(model, propShapeIri, SHACL.NODE_KIND, SHACL.IRI);
            } else {
                IRI pathIri = iri(IriUtils.dataPropertyIri(base,
                        table.getTableName(), col.getColumnName()));
                IRI xsdIri  = XsdTypeMapper.toXsd(col.getDataType());

                add(model, propShapeIri, SHACL.PATH,      pathIri);
                add(model, propShapeIri, SHACL.DATATYPE,  xsdIri);
                add(model, propShapeIri, SHACL.NODE_KIND, SHACL.LITERAL);

                if (col.getCharacterMaxLength() != null
                        && col.getCharacterMaxLength() > 0
                        && isStringType(col.getDataType())) {
                    add(model, propShapeIri, SHACL.MAX_LENGTH,
                            VF.createLiteral(col.getCharacterMaxLength()));
                }
            }

            // Cardinality
            if (!col.isNullable()) {
                add(model, propShapeIri, SHACL.MIN_COUNT, VF.createLiteral(1));
            }
            add(model, propShapeIri, SHACL.MAX_COUNT, VF.createLiteral(1));

            // ── Attach named PropertyShape IRI to NodeShape ───────────────────
            add(model, nodeShapeIri, SHACL.PROPERTY, propShapeIri);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Constraint shapes — named IRIs, not blank nodes
    // ─────────────────────────────────────────────────────────────────────────

    private void addConstraintShapes(Model model, IRI nodeShapeIri,
                                      TableMetadata table, String base) {
        // CHECK constraints
        for (CheckConstraintMetadata cc : table.getCheckConstraints()) {
            IRI constraintIri = iri(IriUtils.constraintShapeIri(base,
                    table.getTableName(), cc.getConstraintName()));

            add(model, constraintIri, RDF.TYPE,     SHACL.SPARQL_CONSTRAINT);
            add(model, constraintIri, RDFS.LABEL,
                    lit(cc.getConstraintName()));
            add(model, constraintIri, SHACL.MESSAGE,
                    lit("CHECK constraint violated: " + cc.getConstraintName()));
            add(model, constraintIri, RDFS.COMMENT,
                    lit("SQL CHECK: " + cc.getCheckClause()));
            add(model, constraintIri, SHACL.SELECT,
                    lit(buildSparqlStub(cc.getCheckClause(), base)));
            add(model, nodeShapeIri, SHACL.SPARQL, constraintIri);
        }

        // UNIQUE constraints
        for (UniqueConstraintMetadata uc : table.getUniqueConstraints()) {
            IRI constraintIri = iri(IriUtils.constraintShapeIri(base,
                    table.getTableName(), uc.getConstraintName()));

            add(model, constraintIri, RDF.TYPE,     SHACL.SPARQL_CONSTRAINT);
            add(model, constraintIri, RDFS.LABEL,
                    lit(uc.getConstraintName()));
            add(model, constraintIri, SHACL.MESSAGE,
                    lit("UNIQUE constraint violated: " + uc.getConstraintName()
                        + " (" + String.join(", ", uc.getColumns()) + ")"));
            add(model, constraintIri, RDFS.COMMENT,
                    lit("UNIQUE on columns: " + String.join(", ", uc.getColumns())));
            add(model, nodeShapeIri, SHACL.SPARQL, constraintIri);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────────────────────────────────

    private String buildSparqlStub(String checkClause, String base) {
        return String.format("""
                PREFIX schema: <%s>
                SELECT $this
                WHERE {
                  # SQL CHECK: %s
                  # TODO: translate SQL expression to SPARQL FILTER
                  FILTER(false)
                }
                """, base, checkClause.replace("*/", "* /"));
    }

    private static IRI iri(String uri)       { return VF.createIRI(uri); }
    private static Literal lit(String value) { return VF.createLiteral(value); }
    private static void add(Model m, Resource s, IRI p, Value o) { m.add(s, p, o); }

    private static boolean isSet(String v)   { return v != null && !v.isBlank(); }

    private static boolean isStringType(String sqlType) {
        if (sqlType == null) return false;
        String u = sqlType.toUpperCase();
        return u.startsWith("VARCHAR") || u.startsWith("CHAR")
            || u.startsWith("NVARCHAR") || u.startsWith("TEXT")
            || u.startsWith("STRING");
    }

    private static boolean isForeignKeyColumn(String colName, TableMetadata table) {
        return table.getForeignKeys().stream()
                .anyMatch(fk -> fk.getFkColumnName().equalsIgnoreCase(colName));
    }

    private static ForeignKeyMetadata getFkForColumn(String colName, TableMetadata table) {
        return table.getForeignKeys().stream()
                .filter(fk -> fk.getFkColumnName().equalsIgnoreCase(colName))
                .findFirst()
                .orElseThrow();
    }
}
