package com.slicercore.account;

/**
 * A password login failed: unknown address, no password on the account, wrong password or
 * too many attempts. One message for all of them, so nothing can be learned about an address.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("The e-mail address or password is wrong.");
    }
}
