package com.jobservice.exception;

public record FieldValidationError(String field, String message) {
}
