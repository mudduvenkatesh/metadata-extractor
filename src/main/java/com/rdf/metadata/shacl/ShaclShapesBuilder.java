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
 * <p><b>Mapping strategy:</b>
 * <ul>
 *   <li>Each TABLE → {@code sh:NodeShape} with {@code sh:targetClass}</li>
 *   <li>Each COLUMN → blank-node {@code sh:PropertyShape} attached via {@code sh:property}:
 *     <ul>
 *       <li>{@code sh:path} → data or object property IRI</li>
 *       <li>{@code sh:datatype} → XSD type IRI (non-FK columns)</li>
 *       <li>{@code sh:class} → referenced OWL class IRI (FK columns)</li>
 *       <li>{@code sh:nodeKind sh:Literal} or {@code sh:IRI}</li>
 *       <li>{@code sh:minCount 1} for NOT NULL</li>
 *       <li>{@code sh:maxCount 1} for all scalar columns</li>
 *       <li>{@code sh:maxLength n} for VARCHAR/CHAR with a defined length</li>
 *     </ul>
 *   </li>
 *   <li>CHECK constraints → {@code sh:sparql} blank-node constraint with the SQL preserved</li>
 *   <li>UNIQUE constraints → {@code sh:sparql} blank-node noting the unique column set</li>
 * </ul>
 *
 * <p>Uses only RDF4J {@link SimpleValueFactory} — no Apache Jena dependency.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShaclShapesBuilder {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    /** RDF4J 5.x ships {@link org.eclipse.rdf4j.model.vocabulary.SHACL} — use it directly. */
    private final RdfProperties rdfProperties;

    /**
     * Build and return an RDF4J {@link Model} containing all SHACL shapes.
     */
    public Model build(SchemaMetadata schema) {
        Model model = new LinkedHashModel();
        String base = rdfProperties.getBaseNamespace();

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

    private IRI buildNodeShape(Model model, TableMetadata table, String base) {
        IRI shapeIri = iri(IriUtils.nodeShapeIri(base, table.getTableName()));
        IRI classIri = iri(IriUtils.classIri(base, table.getTableName()));

        add(model, shapeIri, RDF.TYPE,             SHACL.NODE_SHAPE);
        add(model, shapeIri, SHACL.TARGET_CLASS,   classIri);
        add(model, shapeIri, RDFS.LABEL,           lit(table.getTableName() + "Shape"));

        if (isSet(table.getRemarks())) {
            add(model, shapeIri, SHACL.DESCRIPTION, lit(table.getRemarks()));
        }
        return shapeIri;
    }

    private void buildPropertyShapes(Model model, IRI nodeShapeIri,
                                      TableMetadata table, String base) {
        for (ColumnMetadata col : table.getColumns()) {
            BNode propShape = VF.createBNode();

            add(model, propShape, RDF.TYPE,     SHACL.PROPERTY_SHAPE);
            add(model, propShape, SHACL.NAME,   lit(col.getColumnName()));

            if (isSet(col.getRemarks())) {
                add(model, propShape, SHACL.DESCRIPTION, lit(col.getRemarks()));
            }

            boolean isFkCol = isForeignKeyColumn(col.getColumnName(), table);

            if (isFkCol) {
                ForeignKeyMetadata fk = getFkForColumn(col.getColumnName(), table);
                IRI propIri  = iri(IriUtils.objectPropertyIri(base, fk.getFkTableName(), fk.getPkTableName()));
                IRI rangeIri = iri(IriUtils.classIri(base, fk.getPkTableName()));

                add(model, propShape, SHACL.PATH,      propIri);
                add(model, propShape, SHACL.CLASS,     rangeIri);
                add(model, propShape, SHACL.NODE_KIND, SHACL.IRI);
            } else {
                IRI propIri = iri(IriUtils.dataPropertyIri(base, table.getTableName(), col.getColumnName()));
                IRI xsdIri  = XsdTypeMapper.toXsd(col.getDataType());

                add(model, propShape, SHACL.PATH,      propIri);
                add(model, propShape, SHACL.DATATYPE,  xsdIri);
                add(model, propShape, SHACL.NODE_KIND, SHACL.LITERAL);

                if (col.getCharacterMaxLength() != null
                        && col.getCharacterMaxLength() > 0
                        && isStringType(col.getDataType())) {
                    add(model, propShape, SHACL.MAX_LENGTH,
                            VF.createLiteral(col.getCharacterMaxLength()));
                }
            }

            // Cardinality
            if (!col.isNullable()) {
                add(model, propShape, SHACL.MIN_COUNT, VF.createLiteral(1));
            }
            add(model, propShape, SHACL.MAX_COUNT, VF.createLiteral(1));

            // Attach to NodeShape
            add(model, nodeShapeIri, SHACL.PROPERTY, propShape);
        }
    }

    private void addConstraintShapes(Model model, IRI nodeShapeIri,
                                      TableMetadata table, String base) {
        // CHECK constraints → sh:sparql blank node with SQL preserved
        for (CheckConstraintMetadata cc : table.getCheckConstraints()) {
            BNode sparqlNode = VF.createBNode();
            add(model, sparqlNode, RDF.TYPE,         SHACL.SPARQL_CONSTRAINT);
            add(model, sparqlNode, SHACL.MESSAGE,
                    lit("CHECK constraint violated: " + cc.getConstraintName()));
            add(model, sparqlNode, RDFS.COMMENT,
                    lit("SQL CHECK: " + cc.getCheckClause()));
            add(model, sparqlNode, SHACL.SELECT,
                    lit(buildSparqlStub(cc.getCheckClause(), base)));
            add(model, nodeShapeIri, SHACL.SPARQL, sparqlNode);
        }

        // UNIQUE constraints → sh:sparql blank node with column note
        for (UniqueConstraintMetadata uc : table.getUniqueConstraints()) {
            BNode uniqueNode = VF.createBNode();
            add(model, uniqueNode, RDF.TYPE,        SHACL.SPARQL_CONSTRAINT);
            add(model, uniqueNode, SHACL.MESSAGE,
                    lit("UNIQUE constraint violated: " + uc.getConstraintName()
                        + " (" + String.join(", ", uc.getColumns()) + ")"));
            add(model, uniqueNode, RDFS.COMMENT,
                    lit("UNIQUE on columns: " + String.join(", ", uc.getColumns())));
            add(model, nodeShapeIri, SHACL.SPARQL, uniqueNode);
        }
    }

    // ─── Utilities ────────────────────────────────────────────────────────────

    /**
     * Best-effort SPARQL stub for a SQL CHECK clause.
     * Full SQL→SPARQL translation requires a dedicated expression parser;
     * the raw SQL expression is preserved as a comment for reference.
     */
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

    private static IRI iri(String uri)          { return VF.createIRI(uri); }
    private static Literal lit(String value)    { return VF.createLiteral(value); }
    private static void add(Model m, Resource s, IRI p, Value o) { m.add(s, p, o); }

    private static boolean isSet(String v)      { return v != null && !v.isBlank(); }

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
