# Support & Troubleshooting

Thank you for using **Chitthi (चिठ्ठी)**! Whether you are digitizing your first family letters or integrating Chitthi into a heritage archive, here is how you can get help.

---

## 🧭 Where to Go for Help

| Need | Channel | Link |
| :--- | :--- | :--- |
| **Questions & Ideas** | GitHub Discussions | [Chitthi Discussions](https://github.com/prashant-singh-2001/chitthi-path-2/discussions) |
| **Bug Reports** | GitHub Issues | [Open a Bug Report](https://github.com/prashant-singh-2001/chitthi-path-2/issues/new?template=bug_report.md) |
| **Feature Requests** | GitHub Issues | [Request a Feature](https://github.com/prashant-singh-2001/chitthi-path-2/issues/new?template=feature_request.md) |
| **Security Concerns** | Security Reporting | See [SECURITY.md](SECURITY.md) |

---

## 🛠️ Common Troubleshooting Scenarios

### 1. Port Collisions on Host Machine
Chitthi's [`docker-compose.yml`](docker-compose.yml) maps services to non-conflicting host ports by default:
- **PostgreSQL:** Port `5433` (instead of standard `5432`)
- **RabbitMQ AMQP:** Port `5673` (instead of standard `5672`)
- **RabbitMQ Management UI:** Port `15673` (instead of `15672`)
- **MinIO S3 API:** Port `9100` (instead of `9000`)
- **MinIO Console UI:** Port `9101` (instead of `9001`)

If you encounter `port is already allocated` or `bind: address already in use`:
- Ensure no native PostgreSQL service is running on Windows/Linux (`netstat -ano | findstr 5433`).
- Modify the host port mappings in `docker-compose.yml` and match them in `src/main/resources/application.yml`.

### 2. Missing Sarvam API Key
If you see errors related to missing API keys or unauthorized requests:
- Set your environment variable:
  ```bash
  # Windows PowerShell
  $env:SARVAM_API_KEY="your-sarvam-subscription-key"

  # Linux / macOS
  export SARVAM_API_KEY="your-sarvam-subscription-key"
  ```
- Automated tests (`mvn test`) run using dynamic-port WireMock stubs and do not require a live API key.

### 3. Docker Daemon / Testcontainers on Windows
When running integration tests with Testcontainers:
- Ensure Docker Desktop is started and the engine is responsive (`docker ps`).
- If Testcontainers cannot locate the Docker pipe, ensure `desktop-linux` context is selected:
  ```bash
  docker context use desktop-linux
  ```

---

## 💡 Reporting Effective Issues

When submitting an issue, please include:
1. Complete OS and environment details (Java version `java -version`, Docker version `docker version`).
2. Exact steps to reproduce and pipeline stage affected (Upload, OCR, Translation, TTS, Assembler, Search).
3. Relevant log excerpts from Spring Boot, RabbitMQ, or PostgreSQL.
