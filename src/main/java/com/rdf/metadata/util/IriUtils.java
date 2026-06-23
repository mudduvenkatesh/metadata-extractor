package com.rdf.metadata.util;

/**
 * Utility methods for generating valid RDF IRIs from relational identifiers.
 */
public final class IriUtils {

    private IriUtils() {}

    /**
     * Convert a SQL identifier (table name, column name) to a PascalCase class name.
     * e.g. "order_items" -> "OrderItems", "CUSTOMER_ID" -> "CustomerId"
     */
    public static String toPascalCase(String identifier) {
        if (identifier == null || identifier.isBlank()) return "Unknown";
        String[] parts = identifier.toLowerCase().split("[_\\s]+");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0)));
                sb.append(part.substring(1));
            }
        }
        return sb.toString();
    }

    /**
     * Convert a SQL identifier to camelCase property name.
     * e.g. "customer_id" -> "customerId", "ORDER_DATE" -> "orderDate"
     */
    public static String toCamelCase(String identifier) {
        String pascal = toPascalCase(identifier);
        if (pascal.isEmpty()) return pascal;
        return Character.toLowerCase(pascal.charAt(0)) + pascal.substring(1);
    }

    /**
     * Build a full IRI string for a class (table).
     * e.g. base="http://example.org/schema#", table="order_items" -> "http://example.org/schema#OrderItems"
     */
    public static String classIri(String baseNs, String tableName) {
        return baseNs + toPascalCase(tableName);
    }

    /**
     * Build a full IRI string for a data property (column).
     * e.g. base="http://example.org/schema#", table="orders", col="order_date"
     *      -> "http://example.org/schema#Orders_orderDate"
     */
    public static String dataPropertyIri(String baseNs, String tableName, String columnName) {
        return baseNs + toPascalCase(tableName) + "_" + toCamelCase(columnName);
    }

    /**
     * Build a full IRI string for an object property (FK relationship).
     * e.g. "http://example.org/schema#OrderItems_hasProduct"
     */
    public static String objectPropertyIri(String baseNs, String fkTable, String pkTable) {
        return baseNs + toPascalCase(fkTable) + "_has" + toPascalCase(pkTable);
    }

    /**
     * Build the IRI for a SHACL NodeShape.
     * e.g. "http://example.org/schema#OrderItemsShape"
     */
    public static String nodeShapeIri(String baseNs, String tableName) {
        return baseNs + toPascalCase(tableName) + "Shape";
    }

    /**
     * Sanitise a raw string for use as an IRI local name (remove illegal chars).
     */
    public static String sanitise(String raw) {
        if (raw == null) return "unknown";
        return raw.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }
}
