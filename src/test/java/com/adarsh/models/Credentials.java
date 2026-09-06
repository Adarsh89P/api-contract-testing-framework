package com.adarsh.models;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Request body for {@code POST /auth}. */
public record Credentials(
        @JsonProperty("username") String username,
        @JsonProperty("password") String password) {

    /** Keeps the password out of logs, Allure attachments and assertion messages. */
    @Override
    public String toString() {
        return "Credentials[username=" + username + ", password=***]";
    }
}
