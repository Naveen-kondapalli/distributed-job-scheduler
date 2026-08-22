package com.apigateway.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;

@Configuration
public class RouteConfig {

    @Bean
    RouteLocator jobServiceRoutes(RouteLocatorBuilder routes, GatewayProperties properties) {
        String jobServiceUrl = properties.getServices().getJobServiceUrl();
        return routes.routes()
                .route("auth", r -> r.path("/api/v1/auth/**")
                        .filters(f -> f
                                .setRequestSize(properties.getMaxRequestSize())
                                .removeRequestHeader("X-User-Id")
                                .removeRequestHeader("X-User-Email")
                                .removeRequestHeader("X-Internal-User")
                                .removeRequestHeader(HttpHeaders.CONNECTION))
                        .uri(jobServiceUrl))
                .route("jobs", r -> r.path("/api/v1/jobs/**")
                        .filters(f -> f
                                .setRequestSize(properties.getMaxRequestSize())
                                .removeRequestHeader("X-User-Id")
                                .removeRequestHeader("X-User-Email")
                                .removeRequestHeader("X-Internal-User")
                                .removeRequestHeader(HttpHeaders.CONNECTION))
                        .uri(jobServiceUrl))
                .build();
    }
}
