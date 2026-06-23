package com.rdf.metadata.model;

/**
 * Snowflake authentication mode.
 *
 * <ul>
 *   <li>{@link #PASSWORD} — standard username/password (default)</li>
 *   <li>{@link #KEY_PAIR} — RSA private key authentication (PKCS#8 PEM, file path, or Base64)</li>
 * </ul>
 */
public enum SnowflakeAuthMode {
    PASSWORD,
    KEY_PAIR
}
