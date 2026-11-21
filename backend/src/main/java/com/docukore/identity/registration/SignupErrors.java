package com.docukore.identity.registration;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = SignupController.class)
public class SignupErrors
{
    @ExceptionHandler(SignupService.Conflict.class)
    ResponseEntity<ApiError> conflict()
    {
        return error(HttpStatus.CONFLICT, "REGISTRATION_CONFLICT", "Registration could not be accepted.");
    }

    @ExceptionHandler(SignupService.InvalidProof.class)
    ResponseEntity<ApiError> invalidProof()
    {
        return error(HttpStatus.BAD_REQUEST, "INVALID_VERIFICATION", "Verification is invalid or expired.");
    }

    @ExceptionHandler(SignupService.RetryDenied.class)
    ResponseEntity<ApiError> retryDenied()
    {
        return error(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Request denied.");
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ApiError> invalidInput()
    {
        return error(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "Check the entered information.");
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiError> persistenceFailure()
    {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "UNAVAILABLE", "Please try again later.");
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String code, String message)
    {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(new ApiError(code, message));
    }

    record ApiError(String code, String message)
    {
    }
}
