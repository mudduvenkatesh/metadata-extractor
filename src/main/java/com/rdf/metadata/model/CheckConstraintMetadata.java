package com.rdf.metadata.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CheckConstraintMetadata {
    private String constraintName;
    private String checkClause;   // The raw SQL check expression
}
