package com.rdf.metadata.sql.filter;

/**
 * SQL WHERE clause operators supported by the dynamic filter builder.
 *
 * <p>Operators are categorised by the value arity they require:
 * <ul>
 *   <li><b>Unary</b>  ({@link #IS_NULL}, {@link #IS_NOT_NULL}) — no value needed</li>
 *   <li><b>Scalar</b> ({@link #EQ} … {@link #ILIKE}) — single {@code value} required</li>
 *   <li><b>List</b>   ({@link #IN}, {@link #NOT_IN}) — {@code values} list required</li>
 *   <li><b>Range</b>  ({@link #BETWEEN}, {@link #NOT_BETWEEN}) — exactly two values:
 *       {@code value} (lower bound) and {@code valueTo} (upper bound)</li>
 * </ul>
 */
public enum FilterOperator {

    // ── Equality ──────────────────────────────────────────────────────────────
    /** {@code col = :val} */
    EQ,
    /** {@code col != :val} */
    NEQ,

    // ── Comparison ────────────────────────────────────────────────────────────
    /** {@code col > :val} */
    GT,
    /** {@code col >= :val} */
    GTE,
    /** {@code col < :val} */
    LT,
    /** {@code col <= :val} */
    LTE,

    // ── String matching ───────────────────────────────────────────────────────
    /** {@code col LIKE :val}  — case-sensitive pattern match */
    LIKE,
    /** {@code col NOT LIKE :val} */
    NOT_LIKE,
    /**
     * {@code LOWER(col) LIKE LOWER(:val)} — case-insensitive pattern match.
     * Falls back to {@code ILIKE} on PostgreSQL where the native operator is available.
     */
    ILIKE,

    // ── Set membership ────────────────────────────────────────────────────────
    /** {@code col IN (:val1, :val2, ...)} — requires {@code values} list */
    IN,
    /** {@code col NOT IN (:val1, :val2, ...)} */
    NOT_IN,

    // ── Range ─────────────────────────────────────────────────────────────────
    /** {@code col BETWEEN :val AND :valTo} — requires {@code value} and {@code valueTo} */
    BETWEEN,
    /** {@code col NOT BETWEEN :val AND :valTo} */
    NOT_BETWEEN,

    // ── Nullability ───────────────────────────────────────────────────────────
    /** {@code col IS NULL} — no value needed */
    IS_NULL,
    /** {@code col IS NOT NULL} — no value needed */
    IS_NOT_NULL;

    /** True if no value is needed (unary operators). */
    public boolean isUnary() {
        return this == IS_NULL || this == IS_NOT_NULL;
    }

    /** True if a list of values is required. */
    public boolean requiresList() {
        return this == IN || this == NOT_IN;
    }

    /** True if exactly two bound values are required ({@code value} + {@code valueTo}). */
    public boolean requiresRange() {
        return this == BETWEEN || this == NOT_BETWEEN;
    }

    /** True if a single scalar value is required. */
    public boolean requiresScalar() {
        return !isUnary() && !requiresList() && !requiresRange();
    }
}
