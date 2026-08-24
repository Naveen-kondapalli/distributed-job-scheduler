# GitLab CI pipeline

This phase implements continuous integration only. It verifies code, tests, Docker Compose configuration, Docker image builds, and a small set of security checks before merge. It does not deploy to any environment.

## Architecture

```text
Push / Merge Request
        |
     validate
        |
 +------+------+------+------+
 |      |      |      |      |
API    Job    Watcher Executor
tests  tests  tests   tests
 |      |      |      |
 +------+------+------+
        |
      build
        |
 +------+------+------+------+
 |      |      |      |      |
API    Job    Watcher Executor
image  image  image   image
        |
     security
```

The pipeline lives in `.gitlab-ci.yml`.

## Triggers

The workflow runs for:

- merge request pipelines;
- pushes to the default branch;
- feature branch pushes when there is no open merge request for the same branch.

The workflow avoids duplicate branch pipelines when an MR pipeline already exists for the branch.

## Stages

- `validate` — parse CI YAML and run `docker compose config`.
- `test` — run all four Maven test suites in parallel.
- `build` — package all four services after their test jobs pass.
- `container` — build all four application Docker images.
- `security` — run secret detection and an advisory Maven dependency audit.

## Test infrastructure strategy

Maven test jobs use GitLab service containers instead of depending on a developer machine:

| Job | Infrastructure |
| --- | --- |
| `test:api-gateway` | Redis |
| `test:job-service` | PostgreSQL, Redis |
| `test:watcher-service` | PostgreSQL, Redis, Kafka |
| `test:executor-service` | PostgreSQL, Redis, Kafka |

The services keep their normal local defaults in `application.yml`. CI overrides those defaults using environment variables such as `DB_URL`, `REDIS_HOST`, and `KAFKA_BOOTSTRAP_SERVERS`.

Watcher and Executor use `ddl-auto=validate` at runtime. Their CI test jobs use a test-only `SPRING_JPA_HIBERNATE_DDL_AUTO=update` override so each isolated CI database can bootstrap schema without reusing another job's database.

## Maven cache

The pipeline uses a project-local Maven repository:

```text
.m2/repository
```

The cache key is based on all four service `pom.xml` files. `target/` directories are not cached as dependencies.

## Artifacts

Each test job publishes Surefire XML reports:

```text
services/<service>/target/surefire-reports/TEST-*.xml
```

Build jobs retain packaged JARs for one week. Test reports are retained for one week and are uploaded even on failure.

## Docker validation and builds

Docker jobs use Docker-in-Docker and require a GitLab Runner that supports privileged Docker execution.

The CI pipeline verifies:

- `docker compose config`;
- `services/api-gateway/Dockerfile`;
- `services/job-service/Dockerfile`;
- `services/watcher-service/Dockerfile`;
- `services/executor-service/Dockerfile`.

Images are built for verification only. The pipeline does not push images to a registry in this phase.

## Security checks

Blocking:

- `security:secret-detection` runs Gitleaks and fails the pipeline on high-confidence committed secrets.

Advisory:

- `security:dependency-audit` runs OWASP Dependency-Check for Maven services and publishes reports. It is currently non-blocking because NVD feed availability, API limits, and false positives can make hard blocking unreliable without dedicated organization-level tuning.

Production secrets must not be committed to the repository or placed directly in `.gitlab-ci.yml`. Future deployment phases should use protected GitLab CI variables or a secret manager.

## Required GitLab Runner capabilities

Maven test/build jobs need:

- Linux shell execution through the configured job image;
- network access to Maven Central and required dependency repositories;
- service-container networking for PostgreSQL, Redis, and Kafka.

Docker jobs need:

- Docker-in-Docker support;
- privileged runner mode or equivalent Docker builder capability;
- network access to pull base images.

## CI variables

The CI file uses safe test defaults for:

- `POSTGRES_DB`
- `POSTGRES_USER`
- `POSTGRES_PASSWORD`
- `DB_URL`
- `DB_USERNAME`
- `DB_PASSWORD`
- `REDIS_HOST`
- `REDIS_PORT`
- `KAFKA_BOOTSTRAP_SERVERS`
- `CI_TEST_JWT_SECRET`
- `JWT_SECRET`

These are test-only values. Production credentials must be managed outside repository code.

## Local equivalents

Run service tests:

```powershell
cd services/api-gateway
.\mvnw.cmd --batch-mode test

cd ..\job-service
.\mvnw.cmd --batch-mode test

cd ..\watcher-service
.\mvnw.cmd --batch-mode test

cd ..\executor-service
.\mvnw.cmd --batch-mode test
```

Run service builds:

```powershell
.\mvnw.cmd --batch-mode -DskipTests package
```

Validate Compose:

```powershell
docker compose config
```

Build Docker images:

```powershell
docker compose build
```

## Recommended GitLab project settings

Configure these in GitLab project settings:

- protect `main`;
- require merge requests for changes to `main`;
- require successful pipeline before merge;
- restrict protected CI/CD variables to protected branches/tags when deployment is added later.

## Future CD boundary

Future delivery/deployment work may add:

```text
main merge
   |
   CI
   |
build versioned Docker images
   |
push GitLab Container Registry
   |
deploy to an environment
```

That is intentionally deferred. This phase does not implement production deployment, Kubernetes, Helm, registry publishing, semantic release, or automatic version bumps.
