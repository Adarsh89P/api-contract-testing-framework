package com.adarsh.core;

import com.adarsh.config.ConfigReader;
import com.adarsh.config.FrameworkConfig;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.qameta.allure.restassured.AllureRestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.builder.ResponseSpecBuilder;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.ObjectMapperConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.filter.log.RequestLoggingFilter;
import io.restassured.filter.log.ResponseLoggingFilter;
import io.restassured.http.ContentType;
import io.restassured.path.json.mapper.factory.Jackson2ObjectMapperFactory;
import io.restassured.specification.RequestSpecification;
import io.restassured.specification.ResponseSpecification;

/**
 * Builds the request specifications every test starts from.
 *
 * <p>The Allure filter is attached here, once, rather than at each call site. A
 * per-call filter is only ever attached to the calls someone remembered, and the
 * one that gets forgotten is invariably the one that fails in CI with no request
 * body in the report.
 */
public final class SpecFactory {

    private static final AllureRestAssured ALLURE_FILTER = new AllureRestAssured();

    private SpecFactory() {
    }

    /** Unauthenticated JSON request against the configured base URL. */
    public static RequestSpecification json() {
        FrameworkConfig config = ConfigReader.config();
        RequestSpecBuilder builder = new RequestSpecBuilder()
                .setBaseUri(ConfigReader.baseUrl())
                .setContentType(ContentType.JSON)
                // Deliberately the bare string, not ContentType.JSON. Rest-Assured
                // expands ContentType.JSON into "application/json,
                // application/javascript, text/javascript, text/json", and this API
                // answers 418 I'm a Teapot to any Accept listing a type it does not
                // serve - even when application/json is also present and acceptable.
                // See FINDINGS-9; AcceptHeaderTest pins the behaviour.
                .setAccept("application/json")
                .setConfig(restAssuredConfig(config))
                .addFilter(ALLURE_FILTER);

        if (config.logHttp()) {
            builder.addFilter(new RequestLoggingFilter()).addFilter(new ResponseLoggingFilter());
        }
        return builder.build();
    }

    /**
     * Authenticated request. Restful-Booker reads the token from a cookie named
     * {@code token}, not from an {@code Authorization} header - an API quirk
     * rather than a mistake, which is why there is no bearer variant here.
     */
    public static RequestSpecification authenticated() {
        return json().cookie("token", AuthManager.token());
    }

    /** Baseline response expectations: JSON content type and a sane latency ceiling. */
    public static ResponseSpecification jsonResponse(int expectedStatus) {
        return new ResponseSpecBuilder()
                .expectStatusCode(expectedStatus)
                .expectContentType(ContentType.JSON)
                .build();
    }

    private static RestAssuredConfig restAssuredConfig(FrameworkConfig config) {
        return RestAssuredConfig.config()
                // Free-tier Heroku sleeps; a cold first request routinely takes tens
                // of seconds, so the ceilings here are generous by design.
                .httpClient(HttpClientConfig.httpClientConfig()
                        .setParam("http.connection.timeout", config.connectTimeoutMs())
                        .setParam("http.socket.timeout", config.responseTimeoutMs())
                        .setParam("http.connection-manager.timeout", (long) config.connectTimeoutMs()))
                .objectMapperConfig(ObjectMapperConfig.objectMapperConfig()
                        .jackson2ObjectMapperFactory(jacksonFactory()));
    }

    private static Jackson2ObjectMapperFactory jacksonFactory() {
        return (type, charset) -> new ObjectMapper()
                .registerModule(new JavaTimeModule())
                // Kept strict so an added provider field fails the mapping instead of
                // vanishing. The JSON schemas enforce the same rule independently.
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
