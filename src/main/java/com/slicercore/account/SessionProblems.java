package com.slicercore.account;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The one {@code not-logged-in} problem (401) for every endpoint that needs a session: the
 * account, the cart and the order endpoints all throw {@link NotLoggedInException}.
 */
@RestControllerAdvice
public class SessionProblems {

    @ExceptionHandler(NotLoggedInException.class)
    public ProblemDetail handleNotLoggedIn(NotLoggedInException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
        problem.setProperty("code", "not-logged-in");
        return problem;
    }
}
