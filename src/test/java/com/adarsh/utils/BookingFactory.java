package com.adarsh.utils;

import com.adarsh.models.Booking;
import com.adarsh.models.BookingDates;
import net.datafaker.Faker;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Builds booking payloads.
 *
 * <p>Two rules, both forced by the target being a shared public API:
 *
 * <ul>
 *   <li><b>No fixed names.</b> {@code GET /booking?firstname=John} filters across
 *       everyone's bookings, so a hardcoded name makes any count or
 *       "my booking is in the list" assertion depend on strangers.
 *   <li><b>No hardcoded dates.</b> Everything is relative to
 *       {@link java.time.LocalDate#now()}, so a suite written today does not
 *       quietly start booking stays in the past next year.
 * </ul>
 */
public final class BookingFactory {

    private static final Faker FAKER = new Faker();

    private static final List<String> NEEDS =
            List.of("Breakfast", "Late checkout", "Extra pillows", "Airport transfer");

    private BookingFactory() {
    }

    /** A plausible booking with a unique name and a future stay. */
    public static Booking random() {
        return new Booking(
                uniqueFirstName(),
                FAKER.name().lastName().replaceAll("[^A-Za-z]", ""),
                ThreadLocalRandom.current().nextInt(50, 5_000),
                ThreadLocalRandom.current().nextBoolean(),
                BookingDates.startingIn(
                        ThreadLocalRandom.current().nextInt(1, 180),
                        ThreadLocalRandom.current().nextInt(1, 14)),
                NEEDS.get(ThreadLocalRandom.current().nextInt(NEEDS.size())));
    }

    /** A booking with no {@code additionalneeds}, to exercise the optional field. */
    public static Booking withoutAdditionalNeeds() {
        Booking base = random();
        return new Booking(base.firstname(), base.lastname(), base.totalprice(),
                base.depositpaid(), base.bookingdates(), null);
    }

    /**
     * A first name unique enough to survive a filter query on a shared database.
     * A bare Datafaker first name is not: the corpus is small and other people are
     * using the same one.
     */
    public static String uniqueFirstName() {
        return FAKER.name().firstName().replaceAll("[^A-Za-z]", "")
                + FAKER.number().digits(8);
    }
}
