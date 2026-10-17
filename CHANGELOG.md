# Changelog

All notable changes to **Chitthi (चिठ्ठी)** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Added
- **Automated Docker Image Build & Trivy Vulnerability Scan CI (`.github/workflows/ci.yml`):**
  - Added `docker` job in GitHub Actions executing after the Java test suite (`needs: build`).
  - Automated `docker compose config` validation to prevent syntax or service schema drifts.
  - Multi-platform Docker Buildx setup (`docker/setup-buildx-action@v3`) with GitHub Actions cache backend (`type=gha,mode=max`).
  - Container build via `docker/build-push-action@v6` with `--load` into local runner daemon.
  - Comprehensive container vulnerability scanning with official `aquasecurity/trivy-action@v0.36.0` scanning OS packages and application dependencies for `CRITICAL` and `HIGH` vulnerabilities.
  - Dual output reporting: human-readable console summary table in CI job logs, plus SARIF report upload to GitHub Security Code Scanning tab (`github/codeql-action/upload-sarif@v3`).
- **Production Multi-Stage Dockerfile & Full-Stack Compose Deployment:**
  - Multi-stage hardened `Dockerfile` with Spring Boot 3.4.3 layered extraction:
    - Stage 1 (`builder`): `eclipse-temurin:21-jdk-alpine`, Maven offline dependency pre-caching, and `java -Djarmode=tools -jar target/*.jar extract --layers --destination extracted`.
    - Stage 2 (`runtime`): Minimal `eclipse-temurin:21-jre-alpine` running as unprivileged non-root user `chitthi:chitthi` (UID/GID 1001) for security hardening.
    - Layer-cached assembly: `dependencies/`, `spring-boot-loader/`, `snapshot-dependencies/`, `application/` copied in order of change frequency.
    - Low-latency JVM configuration: Java 21 Generational ZGC (`-XX:+UseZGC -XX:+ZGenerational`), 75% container memory targeting, and virtual thread concurrency.
    - Built-in container healthcheck via `/actuator/health`.
  - Lean `.dockerignore` filtering git history, target build dirs, IDE configs, agent state, and docs to keep Docker build context under 5MB.
  - Production `docker-compose.yml` orchestrating the full 4-service platform:
    - Containerized `chitthi-app` service mapped to port 8080.
    - Ordered startup dependencies: `postgres` (service healthy), `rabbitmq` (service healthy), and `createbuckets` (service completed successfully).
    - Bridge network communication using internal DNS names (`postgres:5432`, `rabbitmq:5672`, `minio:9000`), preserving host port mappings for local developer hybrid workflow.
  - Configuration template `.env.example` documenting all ports, credentials, and `SARVAM_API_KEY`.
- **Handwriting Image Pre-processing Pipeline (`FR12`):**
  - Pure Java `ImagePreprocessingService` implementing projection profile variance maximization for dominant angle skew detection ($-15^\circ$ to $+15^\circ$) and bilinear affine rotation.
  - Luminance histogram percentile clipping (1st/99th percentiles) for auto-contrast stretching, deepening faint ink strokes while brightening aged/yellowed paper backgrounds.
  - Unsharp mask stroke convolution to crispen cursive ligatures and Devanagari vowel diacritics for Sarvam Document AI Vision 1.5.
  - PDFBox splitting pipeline compiles enhanced page images into batch sub-PDFs for Sarvam OCR, maximizing handwriting transcription fidelity.
  - Dual storage: stores both enhanced image (`page_{n}.png`) and raw scan (`page_{n}_raw.png`) in MinIO with Flyway migration `V2__add_preprocessing_columns.sql`.
  - Web Reader Scan Switcher: Interactive `[✨ Enhanced]` vs `[Raw Scan]` toggle button group with live crossfade, plus ingestion form enhancement checkbox.
- **Owner-Scoped Cascading Hard Delete (`FR14`, `DELETE /api/documents/{id}`):** Permanent deletion of documents and child entities (`page`, `ocr_batch`, `stage_task`) with authorization checking (`403 Forbidden` for non-owners, `404 Not Found` for nonexistent). Historical spend ledger (`api_call`) is retained via PostgreSQL `ON DELETE SET NULL`.
- **MinIO Object Prefix Cleanup:** Complete object storage purge of `documents/{documentId}/*` (scanned PDF/images, rendered page PNGs, per-page audio WAVs, stitched audio WAVs, OCR zip outputs), while preserving deduplicated generic TTS audio cache (`cache/tts/{textHash}.wav`).
- **Immediate Event Stream Termination:** Active Server-Sent Event (SSE) emitters for the deleted document are instantly terminated (`closeEmitters`).
- **Per-IP Token Bucket Rate Limiting (`Privacy & Cost NFRs`):** Servlet filter enforcing client-IP rate limits with standard HTTP 429 and `Retry-After: 60` headers:
  - Document ingestion: `POST /api/documents` (10 requests/minute per IP)
  - Archive search: `GET /api/documents/search` (60 requests/minute per IP)
  - Memory-efficient sliding token bucket with automated hourly eviction of stale IP records.
- **Shareable Read-and-Listen Links (`FR11`, `GET /api/documents/{id}/share`):** Ephemeral access token generation with configurable expiration (default 24h, max 168h / 7 days) and shareable web link format (`/index.html?docId=...`).
- **Modern Web UI Modals & Deep Linking:**
  - Glassmorphic Delete Confirmation Modal with permanent deletion warning and instant reader pane reset.
  - Reader toolbar and archive card delete triggers.
  - Share Modal with one-click copy to clipboard.
  - Auto-loader support via URL query parameter `?docId=...` for instant document retrieval.
- **Usage Ledger & Spend Tracking (`FR10`, `GET /api/usage`):** Added `UsageLedgerService`, `UsageController`, and DTOs to track and audit Sarvam AI spend in INR (₹), unit consumption (pages, characters), call counts, and latency statistics aggregated per owner and document.
- **Strict 7,000-Word Daily Ceiling (`FR15`):** Dual enforcement of daily word quota: pre-ingestion check at `DocumentIngestionService` (rejects with HTTP 429 `DailyWordCapExceededException`) and post-OCR check at `OcrBatchStatusPoller` (marks page `CAPPED`, document `PARTIAL`, pauses downstream translation/TTS dispatch, and emits SSE warning).
- **Production Observability via Prometheus (`/actuator/prometheus`):** Micrometer counters and distribution summaries for `chitthi.api.spend.total`, `chitthi.words.processed.total`, `chitthi.tts.cache.hits`, `chitthi.tts.cache.misses`, and `chitthi.stage.latency`.
- **Interactive Usage & Quota Drawer UI:** Slide-out drawer in the web dashboard displaying dynamic daily word cap progress bar, spend stat cards, per-endpoint usage breakdown table, and live Prometheus metrics link.
- **Archivist Edit Flow (`FR8`, `US3`, `US7`):** Added `PUT /api/documents/{id}/pages/{n}/text` endpoint allowing archivists to correct transcription mistakes directly from the web reader or REST API.
- **Partial Invalidation & Selective Re-Assembly:** Editing page $N$ invalidates only page $N$ (`status = OCR_DONE`, `translated_text = null`), re-queueing only translation and TTS while untouched pages remain intact. Upon TTS completion, `AudioAssemblerWorker` re-stitches the full letter audio using cached page WAVs.
- **TTS Output Cache by Text Hash (`FR13`):** Caches synthesized Bulbul WAV audio in MinIO under `cache/tts/{sha256(text:lang)}.wav`. Cache hits reuse audio with 0 paid external API calls and ₹0 ledger cost.
- **Interactive Web Reader In-Browser Editor:** Added an inline editor toggle on the Indic reader pane with instant preview, partial regeneration banner, and dynamic status updates.
- **Reliability & Resilience:** Transactional outbox publisher (`OutboxPublisherService`), deterministic idempotency key enforcement across all workers, Resilience4j rate limiting (10 req/min for Vision) and circuit breaking, RabbitMQ exponential backoff retry with `RepublishMessageRecoverer`, Dead Letter Queue (`DeadLetterWorker`), and manual retry API (`POST /api/documents/{id}/retry`).

---

## [0.1.0] - 2026-09-25

### Added
- **Core Architecture:** Initialized Spring Boot 3.4.3 project with Java 21 virtual thread concurrency.
- **Database Schema:** Flyway migration `V1__initial_schema.sql` defining 6 tables: `document`, `page`, `ocr_batch`, `stage_task`, `api_call`, and `outbox`.
- **Search Capabilities:** GIN trigram index on Indic script text (`pg_trgm`) and generated `tsvector` column for English full-text search with owner-level isolation in `PageRepository`.
- **AI Integrations:** Typed `SarvamClient` for Sarvam AI Document AI (Vision 1.5 async digitise), Translate, and Bulbul v3 TTS (`/text-to-speech` with speaker `shubh`), with socket timeouts and externalized properties.
- **Resilient Messaging:** RabbitMQ configuration featuring dedicated queues (`ocr.queue`, `translate.queue`, `tts.queue`, `assemble.queue`), retry exchange routing, and dead-letter queue (`chitthi.dlx`).
- **Object Storage:** `ObjectStorageService` using AWS SDK v2 for S3 / MinIO private bucket storage with time-limited presigned URLs.
- **Docker Infrastructure:** Multi-container `docker-compose.yml` orchestrating PostgreSQL 16 (port 5433), RabbitMQ 3.13 (ports 5673/15673), and pinned MinIO storage (ports 9100/9101) with automated bucket creation and anonymous access disabled.
- **Contract & Integration Tests:** 
  - Dynamic port WireMock contract test suite covering Sarvam API endpoints with zero credit usage.
  - Testcontainers PostgreSQL container integration test verifying Flyway migrations and extension setup.
- **GitHub Community Standards:** CI workflow (`.github/workflows/ci.yml`), issue templates, pull request template, Apache 2.0 license, contributing guide, and security policy.
