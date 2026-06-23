package com.rdf.metadata.controller;

import com.rdf.metadata.connector.DatabaseConnectionException;
import com.rdf.metadata.extractor.MetadataExtractionException;
import com.rdf.metadata.service.ExtractionServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DatabaseConnectionException.class)
    public ProblemDetail handleConnectionException(DatabaseConnectionException ex) {
        log.error("Database connection failed: {}", ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY, ex.getMessage());
        pd.setTitle("Database Connection Failed");
        pd.setType(URI.create("urn:rdf-extractor:connection-error"));
        return pd;
    }

    @ExceptionHandler(MetadataExtractionException.class)
    public ProblemDetail handleExtractionException(MetadataExtractionException ex) {
        log.error("Metadata extraction failed: {}", ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
        pd.setTitle("Metadata Extraction Failed");
        pd.setType(URI.create("urn:rdf-extractor:extraction-error"));
        return pd;
    }

    @ExceptionHandler(ExtractionServiceException.class)
    public ProblemDetail handleServiceException(ExtractionServiceException ex) {
        log.error("Extraction service error: {}", ex.getMessage(), ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
        pd.setTitle("Extraction Service Error");
        pd.setType(URI.create("urn:rdf-extractor:service-error"));
        return pd;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationException(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        fe -> fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "invalid",
                        (a, b) -> a));

        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setTitle("Validation Failed");
        pd.setDetail("One or more request fields are invalid");
        pd.setType(URI.create("urn:rdf-extractor:validation-error"));
        pd.setProperty("fieldErrors", fieldErrors);
        return pd;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGenericException(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        pd.setTitle("Internal Server Error");
        pd.setType(URI.create("urn:rdf-extractor:internal-error"));
        return pd;
    }
}
