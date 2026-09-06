package com.adarsh.tests.health;

import com.adarsh.core.Endpoints;
import com.adarsh.core.SpecFactory;
import io.qameta.allure.Description;
import io.qameta.allure.Feature;
import io.qameta.allure.Issue;
import io.restassured.response.Response;
import org.testng.annotations.Test;

import static io.restassured.RestAssured.given;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Connectivity gate. If this fails, nothing downstream is worth reading - the
 * dyno is asleep, the base URL is wrong, or the network is blocked.
 */
@Feature("Health")
public class PingTest {

    @Test(description = "the service answers /ping")
    @Description("Asserted as 'not a server error' rather than as 200, because "
            + "/ping answers 201 Created. See FINDINGS.md #1.")
    public void serviceIsReachable() {
        Response response = given().spec(SpecFactory.json()).when().get(Endpoints.PING).then()
                .extract().response();

        assertTrue(response.statusCode() < 400,
                "service unreachable, status " + response.statusCode());
        assertTrue(response.getTime() < 60_000,
                "ping took " + response.getTime() + "ms; the dyno may still be waking");
    }

    @Test(description = "/ping answers in plain text, so there is no schema to apply")
    @Description("Recorded rather than asserted away: every other endpoint in this "
            + "framework is schema-checked, and /ping is the documented exception "
            + "because its body is the bare word Created, not JSON.")
    public void pingBodyIsPlainTextNotJson() {
        Response response = given().spec(SpecFactory.json()).when().get(Endpoints.PING)
                .then().extract().response();

        assertTrue(response.getContentType() == null
                        || !response.getContentType().contains("json"),
                "/ping started returning JSON (" + response.getContentType()
                        + "); it now needs a schema like everything else");
    }

    @Test(description = "GET /ping answers 201 Created, which is wrong for a health probe")
    @Issue("FINDINGS-1")
    @Description("Pinned as a characterisation test. A health check creates nothing, "
            + "so 200 OK is the only correct status. This asserts the defect as it "
            + "stands today so that fixing it upstream shows up as a deliberate change "
            + "here rather than as a mystery failure somewhere else.")
    public void pingReturnsCreatedInsteadOfOk() {
        int status = given().spec(SpecFactory.json()).when().get(Endpoints.PING)
                .then().extract().statusCode();

        assertEquals(status, 201,
                "known defect FINDINGS-1 appears to be fixed; /ping now returns " + status
                        + ". Update this test and FINDINGS.md.");
    }
}
