package com.slicercore.account;

/** The login token is unknown, already used or expired; the customer needs a new link. */
public class InvalidLoginTokenException extends RuntimeException {

    public InvalidLoginTokenException() {
        super("This login link is invalid, was already used or has expired. Request a new one.");
    }
}
