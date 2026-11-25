package com.docukore.cabinet.patient;

import com.docukore.cabinet.access.AccessDeniedException;
import jakarta.persistence.PersistenceException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

/** Patient-controller-only error mapping without exposing rejected values or persistence details. */
@RestControllerAdvice(assignableTypes = PatientController.class)
public class PatientErrors
{
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ValidationError> validationFailure(MethodArgumentNotValidException exception)
    {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (var error : exception.getBindingResult().getFieldErrors())
        {
            addFieldError(fieldErrors, error.getField());
        }
        return invalidInput(fieldErrors);
    }

    @ExceptionHandler(PatientCreationService.InvalidInput.class)
    ResponseEntity<ValidationError> invalidInput(PatientCreationService.InvalidInput exception)
    {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        addFieldError(fieldErrors, exception.field());
        return invalidInput(fieldErrors);
    }

    @ExceptionHandler({PatientListingService.InvalidQuery.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> invalidQuery()
    {
        return error(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "Check the entered information.");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> malformedRequest()
    {
        return error(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "Check the entered information.");
    }

    @ExceptionHandler({AccessDeniedException.class, org.springframework.security.access.AccessDeniedException.class})
    ResponseEntity<ApiError> accessDenied()
    {
        return error(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Request denied.");
    }

    @ExceptionHandler(PatientCreationService.CabinetSelectionRequired.class)
    ResponseEntity<ApiError> cabinetSelectionRequired()
    {
        return error(HttpStatus.CONFLICT, "CABINET_SELECTION_REQUIRED", "Select a cabinet before accessing patients.");
    }

    @ExceptionHandler(PatientCreationService.PhoneConflict.class)
    ResponseEntity<ApiError> phoneConflict()
    {
        return error(HttpStatus.CONFLICT, "PHONE_CONFLICT", "This phone number is already in use.");
    }

    @ExceptionHandler(PatientCreationService.NumberConflict.class)
    ResponseEntity<ApiError> numberConflict()
    {
        return error(HttpStatus.CONFLICT, "PATIENT_NUMBER_CONFLICT", "A patient number could not be allocated.");
    }

    @ExceptionHandler({DataAccessException.class, PersistenceException.class})
    ResponseEntity<ApiError> persistenceFailure()
    {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "UNAVAILABLE", "Please try again later.");
    }

    private static void addFieldError(Map<String, String> fieldErrors, String field)
    {
        if (field != null)
        {
            switch (field)
            {
                case "firstName", "familyName" ->
                    fieldErrors.put(field, "Enter a required name of at most 255 characters.");
                case "phone" ->
                    fieldErrors.put(field, "Enter a supported local or international phone of at most 255 characters.");
                case "dateOfBirth" ->
                    fieldErrors.put(field, "Enter a valid, nonfuture date in yyyy-MM-dd format.");
                case "initialNote" ->
                    fieldErrors.put(field, "Enter a note of at most 10000 characters.");
                default ->
                {
                    // Never expose an unrecognized field name or a rejected value.
                }
            }
        }
    }

    private static ResponseEntity<ValidationError> invalidInput(Map<String, String> fieldErrors)
    {
        return ResponseEntity.badRequest().header("Cache-Control", "no-store")
            .body(new ValidationError("INVALID_INPUT", "Check the entered information.", Map.copyOf(fieldErrors)));
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String code, String message)
    {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(new ApiError(code, message));
    }

    /**
     * Safe non-field API failure.
     *
     * @param code the stable failure code
     * @param message the fixed public explanation
     */
    record ApiError(String code, String message)
    {
    }

    /**
     * Safe validation failure with allowlisted field names and fixed messages.
     *
     * @param code the stable failure code
     * @param message the fixed public explanation
     * @param fieldErrors the invalid API fields, without rejected values
     */
    record ValidationError(String code, String message, Map<String, String> fieldErrors)
    {
    }
}
