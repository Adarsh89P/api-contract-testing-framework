package com.adarsh.core;

/** Raised when {@code POST /auth} does not yield a usable token. */
public class AuthenticationException extends RuntimeException {

    public AuthenticationException(String message) {
        super(message);
    }
}
