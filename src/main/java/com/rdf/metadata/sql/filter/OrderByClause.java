package com.rdf.metadata.sql.filter;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * A single ORDER BY column with direction.
 */
@Data
public class OrderByClause {

    @NotBlank(message = "column is required for ORDER BY")
    private String column;

    /** ASC or DESC — defaults to ASC. */
    private SortOrder direction = SortOrder.ASC;

    public enum SortOrder { ASC, DESC }
}
