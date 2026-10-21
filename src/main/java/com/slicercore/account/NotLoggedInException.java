package com.slicercore.account;

/** No valid session cookie came with the request. */
public class NotLoggedInException extends RuntimeException {

    public NotLoggedInException() {
        super("Not logged in.");
    }
}
