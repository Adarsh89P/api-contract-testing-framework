package com.adarsh.tests.booking;

import com.adarsh.core.BaseApiTest;
import com.adarsh.core.Endpoints;
import com.adarsh.core.SpecFactory;
import io.qameta.allure.Description;
import io.qameta.allure.Feature;
import io.qameta.allure.Issue;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.testng.annotations.Test;

import java.time.LocalDate;

import static io.restassured.RestAssured.given;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Content negotiation, and the defect that cost the most time to find.
 *
 * <p>Rest-Assured's {@code ContentType.JSON} does not send {@code
 * Accept: application/json}. It sends four media types, and this API answers
 * {@code 418 I'm a Teapot} to any {@code Accept} that lists one it does not
 * serve - even when {@code application/json} is present and perfectly
 * acceptable. Every write in this framework failed with an unparseable
 * {@code text/plain} body until {@link SpecFactory} was pinned to the bare
 * string. These tests exist so that fix can never be quietly undone.
 */
@Feature("Content negotiation")
public class ContentNegotiationTest extends BaseApiTest {

    private static final String PAYLOAD = """
            {"firstname":"Teapot","lastname":"Probe","totalprice":100,"depositpaid":true,
             "bookingdates":{"checkin":"%s","checkout":"%s"}}"""
            .formatted(LocalDate.now().plusDays(20), LocalDate.now().plusDays(25));

    private Response postWithAccept(String accept) {
        Response response = given()
                .baseUri(com.adarsh.config.ConfigReader.baseUrl())
                .contentType(ContentType.JSON)
                .accept(accept)
                .body(PAYLOAD)
                .when().post(Endpoints.BOOKING);

        Integer id = response.statusCode() == 200 ? response.jsonPath().get("bookingid") : null;
        if (id != null) {
            registerForCleanup(id);
        }
        return response;
    }

    @Test(description = "an Accept header listing an unsupported type answers 418, not 406")
    @Issue("FINDINGS-9")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Two problems at once. The status is wrong - unacceptable content "
            + "negotiation is 406 Not Acceptable, and 418 is a joke status from an "
            + "April Fools RFC. And the negotiation itself is wrong: Accept is a "
            + "preference list, so a server that can produce application/json must "
            + "serve it rather than rejecting the whole request over a sibling entry.")
    public void unsupportedAcceptTypeAnswersTeapot() {
        Response response = postWithAccept("application/json, text/json");

        assertEquals(response.statusCode(), 418,
                "known defect FINDINGS-9 may be fixed; got " + response.statusCode()
                        + ". Update this test, the SpecFactory comment and FINDINGS.md.");
        assertTrue(response.asString().contains("Teapot"),
                "expected the teapot body, got: " + response.asString());
    }

    @Test(description = "Rest-Assured's own ContentType.JSON triggers the teapot")
    @Issue("FINDINGS-9")
    @Description("The reason this matters in practice rather than in theory. The "
            + "default any Rest-Assured user reaches for is exactly the header this API "
            + "rejects, so the framework's very first write fails with a 418 and a "
            + "text/plain body that no JSON assertion can explain.")
    public void restAssuredDefaultJsonAcceptTriggersTheTeapot() {
        Response response = postWithAccept(ContentType.JSON.getAcceptHeader());

        assertTrue(ContentType.JSON.getAcceptHeader().contains(","),
                "this test assumes ContentType.JSON expands to a list; it now reads "
                        + ContentType.JSON.getAcceptHeader());
        assertEquals(response.statusCode(), 418);
    }

    @Test(description = "the bare application/json this framework sends is accepted")
    @Severity(SeverityLevel.BLOCKER)
    public void bareApplicationJsonIsAccepted() {
        assertEquals(postWithAccept("application/json").statusCode(), 200);
    }

    @Test(description = "a wildcard Accept is accepted")
    @Description("Confirms the rejection is driven by the presence of an unsupported "
            + "type rather than by anything more subtle.")
    public void wildcardAcceptIsAccepted() {
        assertEquals(postWithAccept("*/*").statusCode(), 200);
    }

    @Test(description = "every spec this framework builds sends the safe Accept header")
    @Description("Guards the fix at its source. If someone swaps the bare string back "
            + "to ContentType.JSON, this fails immediately with an explanation instead "
            + "of scattering 418s across the suite.")
    public void specFactorySendsBareApplicationJson() {
        Response response = given().spec(SpecFactory.json()).body(PAYLOAD)
                .when().post(Endpoints.BOOKING);

        Integer id = response.jsonPath().get("bookingid");
        if (id != null) {
            registerForCleanup(id);
        }
        assertEquals(response.statusCode(), 200,
                "SpecFactory is sending an Accept header this API rejects; it must be the "
                        + "bare string \"application/json\", not ContentType.JSON");
    }
}
