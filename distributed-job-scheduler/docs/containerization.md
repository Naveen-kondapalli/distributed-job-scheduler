# Full application containerization

This phase makes the scheduler runnable as a full Docker Compose stack:

```text
Client
  -> api-gateway:8085
  -> job-service:8080
  -> postgres:5432

watcher-service
  -> postgres:5432
  -> job-scheduler-kafka:29092

executor-service
  -> job-scheduler-kafka:29092
  -> postgres:5432
  -> redis:6379
  -> external HTTP target

api-gateway, job-service, watcher-service, executor-service
  -> /actuator/prometheus
  -> prometheus
  -> grafana
```

Docker Compose is the local/development deployment environment. It is not a replacement for production orchestration, deployment policy, or secret management.

## One-command startup

From the repository root:

```powershell
docker compose up -d --build
docker compose ps
```

The external API boundary is:

```text
http://localhost:8085
```

External clients should use the Gateway. Job Service is still published on `localhost:8080` for local debugging, but production networking should keep Job Service, Watcher, Executor, PostgreSQL, Kafka, and Redis private/internal.

## Services and ports

| Service | Docker service name | Internal port | Host port | Notes |
|---|---:|---:|---:|---|
| API Gateway | `api-gateway` | 8085 | 8085 | External API boundary |
| Job Service | `job-service` | 8080 | 8080 | Published for local debugging |
| Watcher Service | `watcher-service` | 8081 | 8081 | Published for local debugging/Actuator |
| Executor Service | `executor-service` | 8082 | not published | Internal by default so scaling works |
| PostgreSQL | `postgres` | 5432 | 5432 | Local development database |
| Kafka | `kafka` / `job-scheduler-kafka` | 29092 internal, 9092 host | 9092 | Internal and host listeners |
| Redis | `redis` | 6379 | 6379 | Rate limiting, cancellation, heartbeat |
| Prometheus | `prometheus` | 9090 | 9090 | Scrapes Docker DNS service names |
| Grafana | `grafana` | 3000 | 3000 | Datasource/dashboard provisioned |

## Docker networking

Inside a container, `localhost` is that container. Compose therefore overrides host-development defaults:

| Configuration | Host default | Docker Compose override |
|---|---|---|
| PostgreSQL | `localhost:5432` | `postgres:5432` |
| Redis | `localhost:6379` | `redis:6379` |
| Kafka | `localhost:9092` | `job-scheduler-kafka:29092` |
| Gateway upstream | `http://localhost:8080` | `http://job-service:8080` |
| Prometheus targets | host URLs for IntelliJ runs | Docker service names |

The Kafka container advertises:

- external host listener: `localhost:9092`
- internal Docker listener: `job-scheduler-kafka:29092`

Dockerized Watcher and Executor use the internal listener. Host/IntelliJ clients can continue using `localhost:9092`.

## Environment variables

Safe development defaults are present in Compose. Copy `.env.example` to `.env` only for local overrides:

```powershell
Copy-Item .env.example .env
```

Important variables:

- `JWT_SECRET`
- `POSTGRES_USER`
- `POSTGRES_PASSWORD`
- `GRAFANA_ADMIN_USER`
- `GRAFANA_ADMIN_PASSWORD`
- `EXECUTOR_HTTP_ALLOWED_HOSTS`
- `GATEWAY_MAX_REQUEST_SIZE`
- `GATEWAY_CONNECT_TIMEOUT_MS`
- `GATEWAY_RESPONSE_TIMEOUT`

Do not commit real `.env` files. Production deployments should use a secret manager.

## Health checks

Application containers use Actuator health:

- `api-gateway`: `/actuator/health`
- `job-service`: `/actuator/health`
- `watcher-service`: `/actuator/health`
- `executor-service`: `/actuator/health`

Infrastructure health checks use:

- PostgreSQL: `pg_isready`
- Redis: `redis-cli ping`
- Kafka: `kafka-topics --list`
- Prometheus: `/-/healthy`
- Grafana: `/api/health`

## Logs

Applications log to stdout/stderr. Use Docker logs:

```powershell
docker compose logs -f api-gateway
docker compose logs -f job-service
docker compose logs -f watcher-service
docker compose logs -f executor-service
```

Correlation fields remain in application logs. Logs are not centrally aggregated in this phase.

## Executor scaling

The Executor derives a unique ID from container hostname plus a random suffix when `EXECUTOR_INSTANCE_ID` is not supplied. Do not set a fixed `EXECUTOR_INSTANCE_ID` when scaling.

Scale Executors:

```powershell
docker compose up -d --scale executor-service=2
```

`executor-service` does not publish host port `8082` by default, so multiple replicas can start without port conflicts. Prometheus statically scrapes the default `executor-service:8082` service target in this local Compose setup.

## Monitoring

Prometheus:

```text
http://localhost:9090
```

Grafana:

```text
http://localhost:3000
```

Default development credentials come from Compose environment variables and default to `admin` / `admin`.

Prometheus scrapes:

- `api-gateway:8085`
- `job-service:8080`
- `watcher-service:8081`
- `executor-service:8082`

## Persistence

Named volumes preserve state across normal shutdown:

```powershell
docker compose down
docker compose up -d
```

This preserves PostgreSQL, Kafka, Redis, Prometheus, and Grafana data.

This deletes named volumes and data:

```powershell
docker compose down -v
```

Use `down -v` only when you intentionally want a clean environment.

## Troubleshooting

Check service status:

```powershell
docker compose ps
```

Validate Compose:

```powershell
docker compose config
```

Check Kafka topics:

```powershell
docker compose exec kafka kafka-topics --bootstrap-server job-scheduler-kafka:29092 --list
```

If Watcher or Executor repeatedly logs Kafka connection failures, verify it uses:

```text
KAFKA_BOOTSTRAP_SERVERS=job-scheduler-kafka:29092
```

If a container logs PostgreSQL connection failures, verify it uses:

```text
DB_URL=jdbc:postgresql://postgres:5432/job_scheduler
```

If Gateway returns upstream errors, verify:

```text
JOB_SERVICE_URL=http://job-service:8080
```

If Prometheus targets are down after changing `prometheus.yml`, recreate Prometheus:

```powershell
docker compose up -d --force-recreate prometheus
```

## Deferred

- CI/CD is intentionally deferred.
- Kubernetes/orchestration is intentionally deferred.
- Eureka remains unnecessary because Docker DNS provides service discovery in this deployment model.
