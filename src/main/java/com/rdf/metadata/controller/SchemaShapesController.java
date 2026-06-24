package com.rdf.metadata.controller;

import com.rdf.metadata.service.MetadataExtractionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for SHACL shape evolution queries.
 *
 * <p>Shapes are always aligned with the latest schema extraction — every
 * call to {@code POST /api/v1/metadata/extract} automatically regenerates
 * and registers fresh shapes in the version registry.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/shapes")
@RequiredArgsConstructor
@Tag(name = "Schema Shapes", description = "Query SHACL shapes aligned to the latest extracted schema")
public class SchemaShapesController {

    private final MetadataExtractionService extractionService;

    /**
     * Return the SHACL shapes for the most recent extraction of the given schema.
     * Shapes are always in sync with the latest extraction — no manual update needed.
     */
    @GetMapping(value = "/{database}/{schema}/latest", produces = "text/turtle")
    @Operation(
        summary = "Latest SHACL shapes for a schema",
        description = """
            Returns the SHACL shapes graph generated from the most recent
            extraction of the specified database/schema. Because shapes are
            regenerated from scratch on every extraction, this endpoint always
            reflects the current database structure.

            The underlying SPARQL query:
            ```sparql
            SELECT ?shapesIri ?extractedAt WHERE {
                ?schema dr:schemaName "<schema>" ;
                        dr:hasVersion  ?record .
                ?record dr:extractedAt ?extractedAt ;
                        dr:hasShapes   ?shapesIri .
            }
            ORDER BY DESC(?extractedAt) LIMIT 1
            ```
            """
    )
    public ResponseEntity<String> latestShapes(
            @PathVariable String database,
            @PathVariable String schema) {

        log.info("GET /shapes/{}/{}/latest", database, schema);
        String turtle = extractionService.latestShapes(database, schema);

        if (turtle == null || turtle.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/turtle"))
                .body(turtle);
    }

    /**
     * List all extraction records for a schema, newest first.
     * Each entry shows extractedAt, tableCount, columnCount, and the shapes IRI.
     */
    @GetMapping("/{database}/{schema}/history")
    @Operation(
        summary = "Extraction history for a schema",
        description = """
            Returns all extraction records for the given schema, ordered newest
            first. Each record includes the extraction timestamp, table/column
            counts, and the IRI of the shapes graph for that snapshot.

            Use this to understand how the schema has evolved over time.
            """
    )
    public ResponseEntity<List<Map<String, String>>> extractionHistory(
            @PathVariable String database,
            @PathVariable String schema) {

        log.info("GET /shapes/{}/{}/history", database, schema);
        return ResponseEntity.ok(extractionService.listExtractions(database, schema));
    }
}
