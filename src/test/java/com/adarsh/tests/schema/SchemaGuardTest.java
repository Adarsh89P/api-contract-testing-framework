package com.adarsh.tests.schema;

import com.adarsh.utils.SchemaValidator;
import io.qameta.allure.Description;
import io.qameta.allure.Feature;
import org.hamcrest.Matcher;
import org.testng.annotations.Test;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * Tests of the schemas themselves, run offline against hand-written documents.
 *
 * <p>A schema that accepts everything is worse than no schema: it produces a
 * green tick that means nothing. These cases prove each deliberate strictness -
 * {@code additionalProperties:false}, {@code integer} over {@code number},
 * {@code boolean} over {@code string}, and the {@code oneOf} on the auth body -
 * actually rejects the document it is meant to reject.
 */
@Feature("Schemas")
public class SchemaGuardTest {

    private static boolean valid(String schema, String json) {
        Matcher<?> matcher = SchemaValidator.matches(schema);
        return matcher.matches(json);
    }

    @Test(description = "a well-formed booking passes")
    public void validBookingPasses() {
        assertTrue(valid(SchemaValidator.BOOKING, """
                {"firstname":"Ada","lastname":"Lovelace","totalprice":120,"depositpaid":true,
                 "bookingdates":{"checkin":"2030-01-01","checkout":"2030-01-05"},
                 "additionalneeds":"Breakfast"}"""));
    }

    @Test(description = "additionalneeds is genuinely optional")
    public void bookingWithoutOptionalFieldPasses() {
        assertTrue(valid(SchemaValidator.BOOKING, """
                {"firstname":"Ada","lastname":"Lovelace","totalprice":120,"depositpaid":true,
                 "bookingdates":{"checkin":"2030-01-01","checkout":"2030-01-05"}}"""));
    }

    @Test(description = "a field the provider added is a visible break, not a silent one")
    @Description("This is what additionalProperties:false buys. Without it a new "
            + "response field slips through unnoticed until something downstream "
            + "depends on a field nobody documented.")
    public void unexpectedFieldFailsTheBookingSchema() {
        assertFalse(valid(SchemaValidator.BOOKING, """
                {"firstname":"Ada","lastname":"Lovelace","totalprice":120,"depositpaid":true,
                 "bookingdates":{"checkin":"2030-01-01","checkout":"2030-01-05"},
                 "loyaltytier":"gold"}"""));
    }

    @Test(description = "totalprice is integer, so 120.5 and \"120\" both fail")
    @Description("Declared as 'number' this schema would accept 120.5, and with no "
            + "type at all it would accept the string too. Currency arriving as a "
            + "float is a real defect class, not a stylistic preference.")
    public void totalPriceMustBeAnInteger() {
        String template = """
                {"firstname":"Ada","lastname":"Lovelace","totalprice":%s,"depositpaid":true,
                 "bookingdates":{"checkin":"2030-01-01","checkout":"2030-01-05"}}""";
        assertFalse(valid(SchemaValidator.BOOKING, template.formatted("120.5")), "120.5 must fail");
        assertFalse(valid(SchemaValidator.BOOKING, template.formatted("\"120\"")), "\"120\" must fail");
        assertTrue(valid(SchemaValidator.BOOKING, template.formatted("120")));
    }

    @Test(description = "depositpaid is boolean, so \"true\" fails")
    public void depositPaidMustBeABoolean() {
        assertFalse(valid(SchemaValidator.BOOKING, """
                {"firstname":"Ada","lastname":"Lovelace","totalprice":120,"depositpaid":"true",
                 "bookingdates":{"checkin":"2030-01-01","checkout":"2030-01-05"}}"""));
    }

    @Test(description = "a missing required field fails")
    public void missingRequiredFieldFails() {
        assertFalse(valid(SchemaValidator.BOOKING, """
                {"firstname":"Ada","totalprice":120,"depositpaid":true,
                 "bookingdates":{"checkin":"2030-01-01","checkout":"2030-01-05"}}"""));
    }

    @Test(description = "the auth schema accepts exactly one of token or reason")
    @Description("The reason POST /auth cannot be tested with a status assertion, "
            + "expressed as a schema. Both arms are 200 OK; only the body differs, and "
            + "a body carrying both or neither is a contract break nothing else catches.")
    public void authSchemaAcceptsEitherArmButNotBoth() {
        assertTrue(valid(SchemaValidator.AUTH_TOKEN, "{\"token\":\"abc123def456\"}"),
                "the success arm must pass");
        assertTrue(valid(SchemaValidator.AUTH_TOKEN, "{\"reason\":\"Bad credentials\"}"),
                "the failure arm must pass");
        assertFalse(valid(SchemaValidator.AUTH_TOKEN,
                        "{\"token\":\"abc123def456\",\"reason\":\"Bad credentials\"}"),
                "both arms at once satisfies two subschemas, and oneOf must reject that");
        assertFalse(valid(SchemaValidator.AUTH_TOKEN, "{}"),
                "neither arm satisfies either subschema");
        assertFalse(valid(SchemaValidator.AUTH_TOKEN, "{\"token\":\"\"}"),
                "an empty token is not a usable token");
    }

    @Test(description = "an empty id list is valid; a filter matching nothing is not an error")
    public void bookingIdsSchemaAllowsAnEmptyArray() {
        assertTrue(valid(SchemaValidator.BOOKING_IDS, "[]"));
        assertTrue(valid(SchemaValidator.BOOKING_IDS, "[{\"bookingid\":1},{\"bookingid\":2}]"));
        assertFalse(valid(SchemaValidator.BOOKING_IDS, "[{\"bookingid\":\"1\"}]"));
        assertFalse(valid(SchemaValidator.BOOKING_IDS, "[{\"id\":1}]"));
    }

    @Test(description = "the create-response schema requires both the id and the echo")
    public void bookingCreatedSchemaRequiresIdAndEcho() {
        assertTrue(valid(SchemaValidator.BOOKING_CREATED, """
                {"bookingid":42,"booking":{"firstname":"Ada","lastname":"Lovelace",
                 "totalprice":120,"depositpaid":true,
                 "bookingdates":{"checkin":"2030-01-01","checkout":"2030-01-05"}}}"""));
        assertFalse(valid(SchemaValidator.BOOKING_CREATED, "{\"bookingid\":42}"),
                "the echoed booking is part of the contract, not a nicety");
    }
}
