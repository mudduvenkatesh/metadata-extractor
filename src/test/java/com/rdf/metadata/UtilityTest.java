package com.rdf.metadata;

import com.rdf.metadata.util.IriUtils;
import com.rdf.metadata.util.XsdTypeMapper;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class UtilityTest {

    // ─── XsdTypeMapper ────────────────────────────────────────────────────────

    @ParameterizedTest(name = "SQL type [{0}] → XSD URI [{1}]")
    @CsvSource({
        "VARCHAR,             http://www.w3.org/2001/XMLSchema#string",
        "VARCHAR(255),        http://www.w3.org/2001/XMLSchema#string",
        "INTEGER,             http://www.w3.org/2001/XMLSchema#integer",
        "BIGINT,              http://www.w3.org/2001/XMLSchema#long",
        "BOOLEAN,             http://www.w3.org/2001/XMLSchema#boolean",
        "DATE,                http://www.w3.org/2001/XMLSchema#date",
        "TIMESTAMP,           http://www.w3.org/2001/XMLSchema#dateTime",
        "TIMESTAMP_NTZ,       http://www.w3.org/2001/XMLSchema#dateTime",
        "TIMESTAMP_TZ,        http://www.w3.org/2001/XMLSchema#dateTimeStamp",
        "DECIMAL,             http://www.w3.org/2001/XMLSchema#decimal",
        "FLOAT,               http://www.w3.org/2001/XMLSchema#float",
        "NUMBER,              http://www.w3.org/2001/XMLSchema#decimal",
        "VARIANT,             http://www.w3.org/2001/XMLSchema#string",
        "UNKNOWN_TYPE,        http://www.w3.org/2001/XMLSchema#string"
    })
    void shouldMapSqlTypeToXsdUri(String sqlType, String expectedXsdUri) {
        assertThat(XsdTypeMapper.toXsdUri(sqlType.trim())).isEqualTo(expectedXsdUri.trim());
    }

    @Test
    void shouldReturnRdf4jIriInstance() {
        IRI result = XsdTypeMapper.toXsd("INTEGER");
        assertThat(result).isEqualTo(XSD.INTEGER);
    }

    @Test
    void shouldDefaultToStringForNullAndBlank() {
        assertThat(XsdTypeMapper.toXsd(null)).isEqualTo(XSD.STRING);
        assertThat(XsdTypeMapper.toXsd("")).isEqualTo(XSD.STRING);
        assertThat(XsdTypeMapper.toXsd("  ")).isEqualTo(XSD.STRING);
    }

    @Test
    void shouldStripPrecisionBeforeLookup() {
        assertThat(XsdTypeMapper.toXsd("NUMERIC(10,2)")).isEqualTo(XSD.DECIMAL);
        assertThat(XsdTypeMapper.toXsd("CHAR(1)")).isEqualTo(XSD.STRING);
    }

    // ─── IriUtils ─────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "toPascalCase({0}) = {1}")
    @CsvSource({
        "order_items, OrderItems",
        "CUSTOMER_ID, CustomerId",
        "product,     Product",
        "ORDER_DATE,  OrderDate"
    })
    void shouldConvertToPascalCase(String input, String expected) {
        assertThat(IriUtils.toPascalCase(input)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "toCamelCase({0}) = {1}")
    @CsvSource({
        "order_items, orderItems",
        "CUSTOMER_ID, customerId",
        "product,     product",
        "ORDER_DATE,  orderDate"
    })
    void shouldConvertToCamelCase(String input, String expected) {
        assertThat(IriUtils.toCamelCase(input)).isEqualTo(expected);
    }

    @Test
    void shouldBuildClassIri() {
        assertThat(IriUtils.classIri("http://example.org/schema#", "order_items"))
                .isEqualTo("http://example.org/schema#OrderItems");
    }

    @Test
    void shouldBuildDataPropertyIri() {
        assertThat(IriUtils.dataPropertyIri("http://example.org/schema#", "orders", "order_date"))
                .isEqualTo("http://example.org/schema#Orders_orderDate");
    }

    @Test
    void shouldBuildNodeShapeIri() {
        assertThat(IriUtils.nodeShapeIri("http://example.org/schema#", "customers"))
                .isEqualTo("http://example.org/schema#CustomersShape");
    }
}
