package com.adarsh.models;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A booking as the API models it.
 *
 * <p>Unknown properties are <em>not</em> ignored. Jackson's default is to fail on
 * them, and that default is kept deliberately: a field the provider adds should
 * surface as a break here and in the JSON schema, not be quietly dropped.
 */
public record Booking(
        @JsonProperty("firstname") String firstname,
        @JsonProperty("lastname") String lastname,
        @JsonProperty("totalprice") int totalprice,
        @JsonProperty("depositpaid") boolean depositpaid,
        @JsonProperty("bookingdates") BookingDates bookingdates,
        @JsonProperty("additionalneeds") @JsonInclude(JsonInclude.Include.NON_NULL)
        String additionalneeds) {

    /** Same booking with a different name; for update tests. */
    public Booking withName(String firstname, String lastname) {
        return new Booking(firstname, lastname, totalprice, depositpaid, bookingdates, additionalneeds);
    }

    public Booking withTotalPrice(int totalprice) {
        return new Booking(firstname, lastname, totalprice, depositpaid, bookingdates, additionalneeds);
    }
}
