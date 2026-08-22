package com.apigateway.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    private Services services = new Services();
    private RateLimit rateLimit = new RateLimit();
    private DataSize maxRequestSize = DataSize.ofMegabytes(1);
    private Duration responseTimeout = Duration.ofSeconds(10);

    public Services getServices() {
        return services;
    }

    public void setServices(Services services) {
        this.services = services;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }

    public DataSize getMaxRequestSize() {
        return maxRequestSize;
    }

    public void setMaxRequestSize(DataSize maxRequestSize) {
        this.maxRequestSize = maxRequestSize;
    }

    public Duration getResponseTimeout() {
        return responseTimeout;
    }

    public void setResponseTimeout(Duration responseTimeout) {
        this.responseTimeout = responseTimeout;
    }

    public static class Services {
        private String jobServiceUrl = "http://localhost:8080";

        public String getJobServiceUrl() {
            return jobServiceUrl;
        }

        public void setJobServiceUrl(String jobServiceUrl) {
            this.jobServiceUrl = jobServiceUrl;
        }
    }

    public static class RateLimit {
        private Policy auth = new Policy(5, 10);
        private Policy authenticated = new Policy(20, 40);

        public Policy getAuth() {
            return auth;
        }

        public void setAuth(Policy auth) {
            this.auth = auth;
        }

        public Policy getAuthenticated() {
            return authenticated;
        }

        public void setAuthenticated(Policy authenticated) {
            this.authenticated = authenticated;
        }
    }

    public static class Policy {
        private int replenishRate;
        private int burstCapacity;

        public Policy() {
        }

        public Policy(int replenishRate, int burstCapacity) {
            this.replenishRate = replenishRate;
            this.burstCapacity = burstCapacity;
        }

        public int getReplenishRate() {
            return replenishRate;
        }

        public void setReplenishRate(int replenishRate) {
            this.replenishRate = replenishRate;
        }

        public int getBurstCapacity() {
            return burstCapacity;
        }

        public void setBurstCapacity(int burstCapacity) {
            this.burstCapacity = burstCapacity;
        }
    }
}
