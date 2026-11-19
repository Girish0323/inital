# Hotel Billing SaaS

Foundation for a multi-tenant hotel billing platform. The repository contains the Maven modules, shared library, local infrastructure, initial database design/migrations, and CI scaffold.

## Quickstart

1. Copy `.env.example` to `.env` (optional; the example defaults are for local development only).
2. Start infrastructure:

   **Windows PowerShell**
   ```powershell
   Copy-Item .env.example .env
   docker compose up -d
   docker compose ps
   ```

   **Linux/macOS**
   ```sh
   cp .env.example .env
   docker compose up -d
   docker compose ps
   ```

3. Build and start the applications:

   ```sh
   ./mvnw clean verify
   docker compose --profile apps up --build
   ```

   In Windows PowerShell use `.\mvnw.cmd clean verify` before the Docker command.
4. Eureka is at http://localhost:8761, Gateway health at http://localhost:8080/actuator/health, RabbitMQ management at http://localhost:15672, MailHog at http://localhost:8025, and MinIO Console at http://localhost:9001.

Local-only credentials are documented in `.env.example`; replace them for any shared deployment.

Flyway baseline scripts live in `deploy/db/migration` and are exercised by the PostgreSQL 16 Testcontainers test during `clean verify`. The `docker compose up -d` quickstart intentionally starts infrastructure only; application services and later phases will own runtime migration startup.
