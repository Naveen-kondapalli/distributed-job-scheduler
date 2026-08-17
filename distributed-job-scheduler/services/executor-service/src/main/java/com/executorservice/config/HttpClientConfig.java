package com.executorservice.config;

import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HttpClientConfig {

    @Bean
    HttpClient httpClient(ExecutorProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.getHttp().connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }
}
