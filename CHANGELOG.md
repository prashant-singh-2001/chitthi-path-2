# Changelog

All notable changes to **Chitthi (चिठ्ठी)** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Added
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
