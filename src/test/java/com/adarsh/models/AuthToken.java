package com.adarsh.models;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Body of {@code POST /auth}.
 *
 * <p>Both fields are nullable because the endpoint returns HTTP 200 either way:
 * a success carries {@code token} and a failure carries {@code reason}. The
 * status code tells you nothing, so the body is the only signal - which is why
 * this type models both arms rather than assuming a token is present.
 */
public record AuthToken(
        @JsonProperty("token") String token,
        @JsonProperty("reason") String reason) {

    public boolean isSuccess() {
        return token != null && !token.isBlank();
    }

    @Override
    public String toString() {
        return isSuccess() ? "AuthToken[token=***]" : "AuthToken[reason=" + reason + "]";
    }
}
