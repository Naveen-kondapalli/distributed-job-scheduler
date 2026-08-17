package com.executorservice.dto;

import java.util.Map;

public record HttpJobPayload(
        String method,
        String url,
        Map<String, String> headers,
        Object body
) {
}
