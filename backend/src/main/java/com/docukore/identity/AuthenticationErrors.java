package com.docukore.identity;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;

@RestControllerAdvice(assignableTypes = AuthenticationController.class)
public class AuthenticationErrors
{

    @ExceptionHandler(LocalSetupService.SetupUnavailable.class)
    ResponseEntity<ApiError> unavailable(LocalSetupService.SetupUnavailable error)
    {
        return ResponseEntity.status(error.initialized() ? HttpStatus.CONFLICT : HttpStatus.NOT_FOUND)
                .body(new ApiError("SETUP_UNAVAILABLE", "Setup is unavailable."));
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ApiError> invalidInput()
    {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_INPUT", "Check the entered information."));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiError> persistenceFailure()
    {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError("UNAVAILABLE", "Please try again later."));
    }

    static void unauthenticated(HttpServletResponse response) throws IOException
    {
        write(response, 401, "AUTHENTICATION_FAILED", "Authentication failed.");
    }

    static void forbidden(HttpServletResponse response) throws IOException
    {
        write(response, 403, "ACCESS_DENIED", "Request denied.");
    }

    private static void write(HttpServletResponse response, int status, String code, String message)
            throws IOException
    {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }

    record ApiError(String code, String message)
    {
    }
}