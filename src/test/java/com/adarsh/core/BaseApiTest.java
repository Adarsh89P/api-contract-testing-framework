package com.adarsh.core;

import io.qameta.allure.Allure;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.testng.annotations.AfterClass;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static io.restassured.RestAssured.given;

/**
 * Base for every test that writes to the API.
 *
 * <p>Cleanup lives here rather than in each test for one reason: Restful-Booker
 * is a shared public instance, so every booking a run creates is litter that the
 * next person's {@code GET /booking} has to wade through. Leaving it to
 * individual tests means it is done in the happy path and forgotten in the four
 * negative tests that also happened to create something.
 *
 * <p>Teardown logs deletion failures and never throws. A failed cleanup is an
 * operational nuisance; letting it fail the build turns a green product into a
 * red pipeline and buries the result that actually mattered.
 */
public abstract class BaseApiTest {

    private static final Logger LOG = LogManager.getLogger(BaseApiTest.class);

    private final Set<Integer> createdBookingIds =
            Collections.synchronizedSet(new LinkedHashSet<>());

    /** Register an id at creation time, not at assertion time. */
    protected void registerForCleanup(int bookingId) {
        createdBookingIds.add(bookingId);
        LOG.debug("Registered booking {} for cleanup", bookingId);
    }

    protected Set<Integer> registeredIds() {
        return Set.copyOf(createdBookingIds);
    }

    @AfterClass(alwaysRun = true)
    public void deleteCreatedBookings() {
        if (createdBookingIds.isEmpty()) {
            return;
        }
        Set<Integer> ids = Set.copyOf(createdBookingIds);
        StringBuilder report = new StringBuilder();
        int deleted = 0;

        for (Integer id : ids) {
            try {
                int status = given()
                        .spec(SpecFactory.authenticated())
                        .pathParam("id", id)
                        .when()
                        .delete(Endpoints.BOOKING_BY_ID)
                        .statusCode();

                // 201 is this API's success code for DELETE (FINDINGS-4); 404 and
                // 405 both mean it is already gone, which is the desired end state.
                if (status == 200 || status == 201 || status == 404 || status == 405) {
                    deleted++;
                    report.append("deleted ").append(id).append(" (HTTP ").append(status).append(")\n");
                } else {
                    LOG.warn("Cleanup: booking {} not deleted, HTTP {}", id, status);
                    report.append("LEFT BEHIND ").append(id).append(" (HTTP ").append(status).append(")\n");
                }
            } catch (RuntimeException e) {
                // Never rethrow: a broken teardown must not fail an otherwise green run.
                LOG.warn("Cleanup: booking {} could not be deleted: {}", id, e.toString());
                report.append("LEFT BEHIND ").append(id).append(" (").append(e).append(")\n");
            }
        }
        createdBookingIds.clear();
        LOG.info("Cleanup: removed {} of {} bookings created by {}",
                deleted, ids.size(), getClass().getSimpleName());
        Allure.addAttachment("Cleanup - " + getClass().getSimpleName(), "text/plain",
                report.toString(), ".txt");
    }
}
