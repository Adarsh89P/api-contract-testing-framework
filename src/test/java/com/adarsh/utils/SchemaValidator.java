package com.adarsh.utils;

import io.qameta.allure.Allure;
import io.restassured.module.jsv.JsonSchemaValidator;
import io.restassured.response.Response;
import org.hamcrest.Matcher;

/**
 * Thin wrapper over Rest-Assured's classpath schema matcher.
 *
 * <p>Exists so schema names are written once and so a failure attaches the body
 * that broke to the Allure report - a schema violation is unreadable without the
 * document that caused it.
 */
public final class SchemaValidator {

    private static final String SCHEMA_DIR = "schemas/";

    public static final String BOOKING = "booking.json";
    public static final String BOOKING_CREATED = "booking-created.json";
    public static final String BOOKING_IDS = "booking-ids.json";
    public static final String AUTH_TOKEN = "auth-token.json";

    private SchemaValidator() {
    }

    /** For use inside a {@code .then().body(...)} chain. */
    public static Matcher<?> matches(String schemaName) {
        return JsonSchemaValidator.matchesJsonSchemaInClasspath(SCHEMA_DIR + schemaName);
    }

    /**
     * Asserts a response against a schema, attaching the body to Allure first so
     * the report shows what was actually received either way.
     */
    public static void assertMatches(Response response, String schemaName) {
        Allure.addAttachment("Response validated against " + schemaName,
                "application/json", response.asString(), ".json");
        response.then().body(matches(schemaName));
    }
}
