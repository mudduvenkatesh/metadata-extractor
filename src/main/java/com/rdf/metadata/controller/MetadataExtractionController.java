package com.rdf.metadata.controller;

import com.rdf.metadata.model.*;
import com.rdf.metadata.service.MetadataExtractionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST API for schema metadata extraction and RDF transformation.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/metadata")
@RequiredArgsConstructor
@Tag(name = "Metadata Extraction", description = "Extract DB schema metadata and convert to RDF/SHACL")
public class MetadataExtractionController {

    private final MetadataExtractionService extractionService;

    /**
     * Full pipeline: connect to DB, extract schema, produce OWL + SHACL RDF.
     */
    @PostMapping("/extract")
    @Operation(
        summary = "Extract schema and convert to RDF",
        description = """
            Connects to the specified database, extracts relational schema metadata
            (tables, columns, PK, FK, constraints), and transforms it into:
            - OWL ontology (classes, datatype/object properties, cardinality restrictions)
            - SHACL node shapes and property shapes

            Returns both serialized RDF graphs in the requested format.

            Snowflake supports two authMode values:
            PASSWORD (default) - supply username + password.
            KEY_PAIR - supply username + one of: privateKeyPem, privateKeyPath, privateKeyBase64.
            Optionally supply privateKeyPassphrase if the key is encrypted.
            """
    )
    public ResponseEntity<ExtractionResult> extract(@Valid @RequestBody ExtractionRequest request) {
        log.info("POST /extract → db={}, schema={}, format={}",
                request.getDatabaseType(), request.getSchemaName(), request.getRdfFormat());

        ExtractionResult result = extractionService.extract(request);
        return ResponseEntity.ok(result);
    }

    /**
     * Preview metadata only — no RDF transformation.
     */
    @PostMapping("/preview")
    @Operation(
        summary = "Preview schema metadata (no RDF)",
        description = "Returns raw relational schema metadata as JSON without RDF transformation. " +
                      "Useful for inspecting what tables/columns will be processed."
    )
    public ResponseEntity<SchemaMetadata> preview(@Valid @RequestBody ExtractionRequest request) {
        log.info("POST /preview → db={}, schema={}", request.getDatabaseType(), request.getSchemaName());

        SchemaMetadata metadata = extractionService.previewMetadata(request);
        return ResponseEntity.ok(metadata);
    }

    /**
     * Convenience: return only the ontology RDF as plain text (Turtle by default).
     */
    @PostMapping(value = "/ontology", produces = "text/turtle")
    @Operation(
        summary = "Return OWL ontology as Turtle",
        description = "Returns only the OWL ontology (classes + properties) as Turtle text."
    )
    public ResponseEntity<String> ontologyAsTurtle(@Valid @RequestBody ExtractionRequest request) {
        request.setRdfFormat(RdfFormat.TURTLE);
        request.setIncludeShaclShapes(false);
        request.setIncludeOwlAxioms(true);

        ExtractionResult result = extractionService.extract(request);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/turtle"))
                .body(result.getOntologyRdf());
    }

    /**
     * Convenience: return only the SHACL shapes graph as plain text (Turtle).
     */
    @PostMapping(value = "/shacl", produces = "text/turtle")
    @Operation(
        summary = "Return SHACL shapes as Turtle",
        description = "Returns only the SHACL shapes graph (node + property shapes) as Turtle text."
    )
    public ResponseEntity<String> shaclAsTurtle(@Valid @RequestBody ExtractionRequest request) {
        request.setRdfFormat(RdfFormat.TURTLE);
        request.setIncludeShaclShapes(true);
        request.setIncludeOwlAxioms(false);

        ExtractionResult result = extractionService.extract(request);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/turtle"))
                .body(result.getShaclRdf());
    }

    /**
     * Health / connectivity test — attempt connection and return table count only.
     */
    @PostMapping("/ping")
    @Operation(
        summary = "Test database connectivity",
        description = "Connects to the database and returns metadata counts without full RDF generation."
    )
    public ResponseEntity<PingResponse> ping(@Valid @RequestBody ExtractionRequest request) {
        request.setIncludeOwlAxioms(false);
        request.setIncludeShaclShapes(false);

        SchemaMetadata meta = extractionService.previewMetadata(request);

        return ResponseEntity.ok(new PingResponse(
                "OK",
                meta.getDatabaseName(),
                meta.getSchemaName(),
                meta.getTables().size()
        ));
    }

    /** Simple ping response record. */
    public record PingResponse(String status, String database, String schema, int tableCount) {}
}
