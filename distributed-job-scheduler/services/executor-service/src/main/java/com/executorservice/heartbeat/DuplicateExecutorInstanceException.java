package com.executorservice.heartbeat;

public class DuplicateExecutorInstanceException extends RuntimeException {

    public DuplicateExecutorInstanceException(String message) {
        super(message);
    }
}
