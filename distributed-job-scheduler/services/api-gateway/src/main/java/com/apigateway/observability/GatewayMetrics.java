package com.apigateway.observability;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class GatewayMetrics {

    private final MeterRegistry meterRegistry;

    public GatewayMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void request(String route, String result, long durationNanos) {
        meterRegistry.counter("scheduler.gateway.requests", "route", route, "result", result).increment();
        meterRegistry.timer("scheduler.gateway.request.duration", "route", route, "result", result)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public void rateLimited(String route) {
        meterRegistry.counter("scheduler.gateway.rate_limited", "route", route).increment();
    }
}
