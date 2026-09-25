# Contributing to Chitthi (चिठ्ठी)

First off, thank you for considering contributing to Chitthi! Projects like this rely on community contributions to preserve and digitize historic handwritten documents.

## Code of Conduct

This project adheres to the [Contributor Covenant](CODE_OF_CONDUCT.md). By participating, you are expected to uphold this code.

## Development Workflow

### 1. Fork and Clone
```bash
git clone https://github.com/<your-username>/chitthi-path-2.git
cd chitthi-path-2
git checkout -b feature/my-new-feature
```

### 2. Local Environment Setup
* **JDK 21+**
* **Docker & Docker Compose**
* Start the backing services:
  ```bash
  docker compose up -d
  ```

### 3. Code Conventions
* Follow standard Java code formatting and naming conventions.
* Prefer Java 21 records and immutability for DTOs and configuration.
* Keep controllers lightweight; delegate business logic to pipeline workers and services.
* Use the **Transactional Outbox Pattern** when triggering asynchronous queue events.
* Ensure all external API calls are routed through `SarvamClient` with proper idempotency keys and error handling.

### 4. Testing Requirements
* All external API interactions with Sarvam AI must be accompanied by WireMock tests to avoid consuming live API credits.
* Database changes must be accompanied by Flyway migration scripts in `src/main/resources/db/migration/` and verified with Testcontainers.
* Run the test suite before submitting PRs:
  ```bash
  mvn clean test
  ```

### 5. Git Commit Guidelines
We follow [Conventional Commits](https://www.conventionalcommits.org/):
* `feat:` New features
* `fix:` Bug fixes
* `docs:` Documentation updates
* `infra:` Docker, docker-compose, CI/CD changes
* `refactor:` Code refactoring without behavioral changes
* `test:` Adding or updating tests

## Pull Request Checklist

- [ ] Code compiles and tests pass (`mvn test`).
- [ ] New database changes include Flyway migrations.
- [ ] No hardcoded secrets or API keys in configuration.
- [ ] Commit messages follow the conventional commit format.
- [ ] Architecture or API changes are reflected in `README.md`.
