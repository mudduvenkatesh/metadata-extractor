package com.rdf.metadata.sql;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.model.*;
import org.eclipse.rdf4j.model.vocabulary.OWL;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.model.vocabulary.SHACL;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Reads an RDF4J {@link Model} containing SHACL shapes and OWL axioms and
 * extracts a list of {@link ShapeTable} objects — one per {@code sh:NodeShape}.
 *
 * <h3>What it reads from the model</h3>
 *
 * <p>For each {@code sh:NodeShape}:
 * <ul>
 *   <li>{@code sh:targetClass} → table name (local part of the class IRI)</li>
 *   <li>{@code sh:property} → blank-node PropertyShapes → columns</li>
 * </ul>
 *
 * <p>For each {@code sh:PropertyShape} blank node:
 * <ul>
 *   <li>{@code sh:name}      → column name</li>
 *   <li>{@code sh:datatype}  → XSD IRI → non-FK column type</li>
 *   <li>{@code sh:class}     → referenced class → FK column</li>
 *   <li>{@code sh:minCount}  → 1 means NOT NULL</li>
 *   <li>{@code sh:maxLength} → VARCHAR length cap</li>
 * </ul>
 *
 * <p>Primary key columns are resolved by reading {@code owl:hasKey} triples on the
 * OWL class that corresponds to the NodeShape's {@code sh:targetClass}.
 *
 * <p>Unique and check constraint messages are extracted from {@code sh:sparql}
 * blank nodes attached to the NodeShape.
 */
@Slf4j
@Component
public class ShaclModelReader {

    /**
     * Parse all {@code sh:NodeShape} resources from the model
     * and return one {@link ShapeTable} per shape.
     *
     * @param model the combined SHACL + OWL RDF4J model
     * @return list of shape tables in the order they appear in the model
     */
    public List<ShapeTable> readTables(Model model) {
        List<ShapeTable> tables = new ArrayList<>();

        // Find every resource that is a sh:NodeShape
        Set<Resource> nodeShapes = new LinkedHashSet<>();
        model.filter(null, RDF.TYPE, SHACL.NODE_SHAPE)
             .forEach(stmt -> nodeShapes.add(stmt.getSubject()));

        for (Resource nodeShape : nodeShapes) {
            ShapeTable table = readTable(model, nodeShape);
            if (table != null) {
                tables.add(table);
                log.debug("Read ShapeTable '{}' with {} columns",
                        table.getTableName(), table.getColumns().size());
            }
        }

        log.info("ShaclModelReader extracted {} tables from model", tables.size());
        return tables;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NodeShape → ShapeTable
    // ─────────────────────────────────────────────────────────────────────────

    private ShapeTable readTable(Model model, Resource nodeShape) {
        // sh:targetClass → the OWL class IRI that names the table
        IRI targetClass = objectIri(model, nodeShape, SHACL.TARGET_CLASS);
        if (targetClass == null) {
            log.warn("NodeShape {} has no sh:targetClass — skipping", nodeShape);
            return null;
        }

        String tableName = localName(targetClass);

        // Resolve primary key columns from owl:hasKey on the target class
        List<String> pkColumns = readPrimaryKeyColumns(model, targetClass);

        // Read all sh:property blank nodes
        List<ShapeColumn> columns = readColumns(model, nodeShape, pkColumns);

        // Extract constraint messages from sh:sparql blank nodes
        List<String> uniqueMessages = new ArrayList<>();
        List<String> checkMessages  = new ArrayList<>();
        readConstraintMessages(model, nodeShape, uniqueMessages, checkMessages);

        return ShapeTable.builder()
                .tableName(tableName)
                .nodeShapeIri(nodeShape.stringValue())
                .columns(columns)
                .primaryKeyColumns(pkColumns)
                .uniqueConstraintMessages(uniqueMessages)
                .checkConstraintMessages(checkMessages)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Primary key resolution from owl:hasKey
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Read {@code owl:hasKey} RDF lists on the target class and return the
     * local names of each property IRI in the list.
     *
     * <p>The RDF list is a chain of {@code rdf:first}/{@code rdf:rest} blank nodes
     * terminated by {@code rdf:nil}, as written by {@link com.rdf.metadata.rdf.OntologyBuilder}.
     */
    private List<String> readPrimaryKeyColumns(Model model, IRI targetClass) {
        List<String> pkCols = new ArrayList<>();

        model.filter(targetClass, OWL.HASKEY, null).forEach(stmt -> {
            Value listHead = stmt.getObject();
            if (listHead instanceof Resource head) {
                traverseRdfList(model, head, pkCols);
            }
        });

        return pkCols;
    }

    private void traverseRdfList(Model model, Resource node, List<String> result) {
        // rdf:nil marks the end of the list
        if (node.equals(RDF.NIL)) return;

        // rdf:first → the property IRI at this position
        Value first = objectValue(model, node, RDF.FIRST);
        if (first instanceof IRI propIri) {
            // The local name of the property IRI encodes: TableName_columnName
            // e.g. "Orders_orderId" → extract "orderId" then convert to SQL name
            String localPart = localName(propIri);
            int underscore   = localPart.indexOf('_');
            if (underscore >= 0 && underscore < localPart.length() - 1) {
                // Convert camelCase back to UPPER_SNAKE for SQL column name
                String camelCol = localPart.substring(underscore + 1);
                result.add(camelToUpperSnake(camelCol));
            } else {
                result.add(localPart.toUpperCase());
            }
        }

        // rdf:rest → recurse
        Value rest = objectValue(model, node, RDF.REST);
        if (rest instanceof Resource nextNode) {
            traverseRdfList(model, nextNode, result);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PropertyShape → ShapeColumn
    // ─────────────────────────────────────────────────────────────────────────

    private List<ShapeColumn> readColumns(Model model, Resource nodeShape, List<String> pkColumns) {
        List<ShapeColumn> columns = new ArrayList<>();
        Set<String> pkSet = new HashSet<>(pkColumns);

        model.filter(nodeShape, SHACL.PROPERTY, null).forEach(stmt -> {
            Value propShapeVal = stmt.getObject();
            if (!(propShapeVal instanceof Resource propShape)) return;

            ShapeColumn col = readColumn(model, propShape, pkSet);
            if (col != null) columns.add(col);
        });

        return columns;
    }

    private ShapeColumn readColumn(Model model, Resource propShape, Set<String> pkSet) {
        // sh:name → column name
        String columnName = stringObject(model, propShape, SHACL.NAME);
        if (columnName == null) {
            log.warn("PropertyShape {} has no sh:name — skipping", propShape);
            return null;
        }

        // Determine if FK: sh:class present means object property / FK
        IRI shClass    = objectIri(model, propShape, SHACL.CLASS);
        IRI shDatatype = objectIri(model, propShape, SHACL.DATATYPE);

        boolean isFk = shClass != null;

        // Nullability: sh:minCount 1 → NOT NULL
        String minCountStr = stringObject(model, propShape, SHACL.MIN_COUNT);
        boolean nullable   = !"1".equals(minCountStr);

        // sh:maxLength
        String maxLengthStr = stringObject(model, propShape, SHACL.MAX_LENGTH);
        Integer maxLength   = maxLengthStr != null ? parseIntSafe(maxLengthStr) : null;

        // Resolve referenced table name from the class IRI local name
        String referencedTable = isFk ? localName(shClass) : null;

        return ShapeColumn.builder()
                .columnName(columnName.toUpperCase())
                .xsdDatatype(shDatatype != null ? shDatatype.stringValue() : null)
                .isForeignKey(isFk)
                .referencedTable(referencedTable)
                .nullable(nullable)
                .maxLength(maxLength)
                .isPrimaryKey(pkSet.contains(columnName.toUpperCase()))
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Constraint message extraction from sh:sparql blank nodes
    // ─────────────────────────────────────────────────────────────────────────

    private void readConstraintMessages(Model model, Resource nodeShape,
                                         List<String> uniqueMessages,
                                         List<String> checkMessages) {
        model.filter(nodeShape, SHACL.SPARQL, null).forEach(stmt -> {
            if (!(stmt.getObject() instanceof Resource sparqlNode)) return;

            String message = stringObject(model, sparqlNode, SHACL.MESSAGE);
            String comment = stringObject(model, sparqlNode, RDFS.COMMENT);

            if (message != null) {
                if (message.startsWith("UNIQUE")) {
                    uniqueMessages.add(comment != null ? comment : message);
                } else if (message.startsWith("CHECK")) {
                    checkMessages.add(comment != null ? comment : message);
                }
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Model traversal helpers
    // ─────────────────────────────────────────────────────────────────────────

    private IRI objectIri(Model model, Resource subject, IRI predicate) {
        return model.filter(subject, predicate, null).stream()
                .map(Statement::getObject)
                .filter(v -> v instanceof IRI)
                .map(v -> (IRI) v)
                .findFirst()
                .orElse(null);
    }

    private Value objectValue(Model model, Resource subject, IRI predicate) {
        return model.filter(subject, predicate, null).stream()
                .map(Statement::getObject)
                .findFirst()
                .orElse(null);
    }

    private String stringObject(Model model, Resource subject, IRI predicate) {
        return model.filter(subject, predicate, null).stream()
                .map(Statement::getObject)
                .filter(v -> v instanceof Literal)
                .map(v -> ((Literal) v).stringValue())
                .findFirst()
                .orElse(null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // IRI / name utilities
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Extract the local name from an IRI — the part after the last {@code #} or {@code /}.
     * e.g. {@code http://example.org/schema#Orders} → {@code "Orders"}
     */
    static String localName(IRI iri) {
        String str = iri.stringValue();
        int hash   = str.lastIndexOf('#');
        int slash  = str.lastIndexOf('/');
        int idx    = Math.max(hash, slash);
        return idx >= 0 ? str.substring(idx + 1) : str;
    }

    /**
     * Convert a camelCase property local name back to UPPER_SNAKE_CASE SQL column name.
     * e.g. {@code "orderId"} → {@code "ORDER_ID"}
     */
    public static String camelToUpperSnake(String camel) {
        if (camel == null || camel.isEmpty()) return camel;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c) && i > 0) sb.append('_');
            sb.append(Character.toUpperCase(c));
        }
        return sb.toString();
    }

    private Integer parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return null; }
    }
}
