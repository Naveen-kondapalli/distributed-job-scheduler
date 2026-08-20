package com.jobservice.cancellation;

public class CancellationSignalException extends RuntimeException {

    public CancellationSignalException(String message, Throwable cause) {
        super(message, cause);
    }
}
