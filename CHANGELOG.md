# Changelog

All notable changes to **Chitthi (चिठ्ठी)** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Planned
- Multi-page PDF ingestion controller and Apache PDFBox splitting worker.
- Scheduled status poller for Sarvam Document AI digitise ZIP downloads.
- Sentence-boundary chunking translation and TTS workers with Bulbul v3.
- Audio assembler and FFmpeg stitcher for full-letter voice playback.
- Real-time Server-Sent Events (SSE) pipeline event stream.

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
