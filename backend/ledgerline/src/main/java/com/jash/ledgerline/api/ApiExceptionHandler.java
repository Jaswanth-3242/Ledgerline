package com.jash.ledgerline.api;

import com.jash.ledgerline.ledger.ConflictException;
import com.jash.ledgerline.ledger.LedgerService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Turns exceptions into clean JSON errors. There is deliberately NO catch-all handler:
 * Spring Security's 401/403 must keep working.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    record ApiError(String error, String message) {}

    private ResponseEntity<ApiError> reply(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new ApiError(status.getReasonPhrase(), message));
    }

    @ExceptionHandler(LedgerService.NotFoundException.class)
    ResponseEntity<ApiError> notFound(LedgerService.NotFoundException e) {
        return reply(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(LedgerService.InsufficientFundsException.class)
    ResponseEntity<ApiError> insufficient(LedgerService.InsufficientFundsException e) {
        return reply(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler({LedgerService.IdempotencyConflictException.class, ConflictException.class})
    ResponseEntity<ApiError> conflict(RuntimeException e) {
        return reply(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(LedgerService.InvalidRequestException.class)
    ResponseEntity<ApiError> invalid(LedgerService.InvalidRequestException e) {
        return reply(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst().map(f -> f.getField() + " " + f.getDefaultMessage()).orElse("Invalid request");
        return reply(HttpStatus.BAD_REQUEST, msg);
    }

    @ExceptionHandler({MissingRequestHeaderException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> badInput(Exception e) {
        return reply(HttpStatus.BAD_REQUEST, "Malformed request or missing required header");
    }

    /** A database rule (e.g. balance >= 0) stopped something the app code missed. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> dbRule(DataIntegrityViolationException e) {
        return reply(HttpStatus.CONFLICT, "Operation rejected by a database integrity rule");
    }
}
