# 2026-07-31 Auth and Security Implementation Log

## Project inspection

- Detected Spring Boot service location: `services/job-service`
- Detected base Java package: `com.jobservice`
- Detected build tool: Maven with wrapper scripts
- Detected Java version: `17`
- Detected Spring Boot version: `4.1.0`
- Detected existing persistence model:
  - `User`
  - `BaseEntity`
  - `Job`
  - `JobRun`
- Preserved existing entity mappings and datasource/JPA settings.

## Implemented

- User registration endpoint: `POST /api/v1/auth/register`
- User login endpoint: `POST /api/v1/auth/login`
- BCrypt password hashing
- Email normalization with `Locale.ROOT`
- JWT Bearer token generation and validation
- Stateless Spring Security configuration
- JSON 401 and 403 handlers for security-layer failures
- Reusable global exception response model and handler
- Focused service, JWT, controller, and security tests

## Dependencies added

- `spring-boot-starter-jackson`
- `jjwt-api`
- `jjwt-impl` with runtime scope
- `jjwt-jackson` with runtime scope

All JJWT modules use the shared Maven property `jjwt.version=0.12.6`.

## Verification

- `.\mvnw.cmd test` passed: 17 tests, 0 failures, 0 errors.
- `.\mvnw.cmd package` passed and produced the repackaged Spring Boot jar.
- The existing `JobServiceApplicationTests.contextLoads` started the Spring application context successfully against the configured PostgreSQL datasource.

## Notes

- `JWT_SECRET` is configured with a local-development fallback only and must be supplied through an environment variable outside local development.
- No Job APIs, Kafka, Redis, Docker, notification, roles-table, frontend, or unrelated infrastructure files were created.
