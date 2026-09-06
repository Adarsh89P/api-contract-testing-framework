package com.adarsh.core;

/**
 * Every path the framework knows about, in one place. Templates use Rest-Assured
 * path parameters ({@code {id}}) so ids are never concatenated into a URL.
 */
public final class Endpoints {

    public static final String PING = "/ping";
    public static final String AUTH = "/auth";
    public static final String BOOKING = "/booking";
    public static final String BOOKING_BY_ID = "/booking/{id}";

    private Endpoints() {
    }
}
