package com.rdf.metadata.controller;

import com.rdf.metadata.sql.*;
import com.rdf.metadata.sql.filter.FilterSelectApiRequest;
import com.rdf.metadata.sql.filter.FilterValidationException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST API for dynamic SQL generation from SHACL shapes.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/sql")
@RequiredArgsConstructor
@Tag(name = "Dynamic SQL Generation", description = "Generate SQL statements from SHACL shape models")
public class DynamicSqlController {

    private final DynamicSqlService dynamicSqlService;

    @PostMapping("/generate")
    @Operation(
        summary = "Generate SQL statements from schema metadata via SHACL",
        description = "Generates CREATE_TABLE, SELECT_ALL, SELECT_BY_PK, INSERT, UPDATE_BY_PK, DELETE_BY_PK statements from SHACL shapes."
    )
    public ResponseEntity<SqlGenerationResult> generate(
            @Valid @RequestBody SqlGenerationRequest request) {
        log.info("POST /sql/generate → dialect={}, types={}", request.getDialect(), request.getStatementTypes());
        return ResponseEntity.ok(dynamicSqlService.generateFromDatabase(request));
    }

    /**
     * Generate a filter-based SELECT from SHACL shape metadata.
     *
     * <p>Validates all filter criteria against the SHACL shape (column existence,
     * type/operator compatibility) and returns the rendered SQL with named bind parameters.
     * Returns HTTP 422 with a structured error list if validation fails.
     */
    @PostMapping("/select/filter")
    @Operation(
        summary = "Generate a filter-based SELECT statement",
        description = """
            Connects to the database, builds the SHACL model, validates filter criteria
            against column shapes (type compatibility, column existence, value arity),
            and returns a rendered SELECT with named :param placeholders.

            Operators: EQ, NEQ, GT, GTE, LT, LTE, LIKE, NOT_LIKE, ILIKE,
                       IN, NOT_IN, BETWEEN, NOT_BETWEEN, IS_NULL, IS_NOT_NULL

            Features: nested AND/OR groups, column projection, ORDER BY, LIMIT, OFFSET.
            Returns HTTP 422 with a list of validation errors if criteria are invalid.
            """
    )
    public ResponseEntity<?> filterSelect(@Valid @RequestBody FilterSelectApiRequest request) {
        log.info("POST /sql/select/filter → table={}, dialect={}",
                request.getFilterRequest().getTableName(),
                request.getFilterRequest().getDialect());
        try {
            SqlStatement stmt = dynamicSqlService.generateFilterSelect(
                    request.getExtractionRequest(),
                    request.getFilterRequest());
            return ResponseEntity.ok(stmt);
        } catch (FilterValidationException e) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(new ValidationErrorResponse(e.getErrors()));
        }
    }

    public record ValidationErrorResponse(List<String> errors) {}
}
