# GitHub Repository Metadata & Configuration

This guide provides the official GitHub repository description, topics, settings, and CLI commands for **Chitthi (चिठ्ठी)**.

---

## 📋 Repository Details

### 1. Repository Name
```text
chitthi-path-2
```
*(Or `chitthi` / `chitthi-ai`)*

---

### 2. Repository Description (About section)
> Digitize, search, and listen to handwritten Indian letters & heritage documents using Sarvam AI (Vision 1.5, Translate, Bulbul TTS), Spring Boot 3, RabbitMQ, and PostgreSQL.

*Character count: 184 (well within GitHub's 350-character limit).*

---

### 3. Website / Homepage URL
```text
https://github.com/prashant-singh-2001/chitthi-path-2
```

---

### 4. Repository Topics (Tags)
GitHub allows up to 20 repository topics. The following 20 topics are tailored for discoverability across AI, backend engineering, and Indic heritage communities:

| # | Topic | Category | Purpose |
| :-: | :--- | :--- | :--- |
| 1 | `spring-boot` | Backend Framework | Identifies modern Spring Boot 3.4.x backend |
| 2 | `java-21` | Runtime | Identifies Java 21 LTS & Virtual Threads |
| 3 | `sarvam-ai` | AI / LLM Vendor | Highlights Sarvam AI Indic models |
| 4 | `document-ai` | AI Domain | Sarvam Document AI Vision 1.5 pipeline |
| 5 | `ocr` | Computer Vision | Optical character recognition for handwritten text |
| 6 | `indic-nlp` | NLP Domain | Indic language natural language processing |
| 7 | `tts` | Audio AI | Text-to-speech voice readouts |
| 8 | `bulbul-v3` | Audio Model | Sarvam Bulbul v3 Indian voice synthesis |
| 9 | `rabbitmq` | Messaging Broker | Asynchronous multi-stage event processing |
| 10 | `postgresql` | Database | Relational database with transactional outbox |
| 11 | `pg-trgm` | Search Engine | PostgreSQL trigram GIN indexing for Indic scripts |
| 12 | `minio` | Storage | S3-compatible private object storage |
| 13 | `resilience4j` | Resilience | Circuit breakers and rate limiters |
| 14 | `wiremock` | Testing | Zero-credit contract testing |
| 15 | `testcontainers` | Testing | Ephemeral PostgreSQL container test harness |
| 16 | `handwriting-recognition` | Domain | Specialized recognition of cursive/faded Indian letters |
| 17 | `digital-archiving` | Domain | Preserving family records, historical letters, and diaries |
| 18 | `indic-languages` | Domain | Support for Hindi, Gujarati, Bengali, etc. |
| 19 | `devanagari` | Script | Support for Devanagari script processing |
| 20 | `event-driven` | Architecture | Decoupled event-driven microservices architecture |

---

## ⚡ One-Click GitHub CLI Command

To immediately apply the repository description and all 20 topics using the [GitHub CLI (`gh`)](https://cli.github.com/):

```bash
gh repo edit prashant-singh-2001/chitthi-path-2 \
  --description "Digitize, search, and listen to handwritten Indian letters & heritage documents using Sarvam AI (Vision 1.5, Translate, Bulbul TTS), Spring Boot 3, RabbitMQ, and PostgreSQL." \
  --homepage "https://github.com/prashant-singh-2001/chitthi-path-2" \
  --enable-issues=true \
  --enable-wiki=false \
  --enable-discussions=true \
  --add-topic "spring-boot" \
  --add-topic "java-21" \
  --add-topic "sarvam-ai" \
  --add-topic "document-ai" \
  --add-topic "ocr" \
  --add-topic "indic-nlp" \
  --add-topic "tts" \
  --add-topic "bulbul-v3" \
  --add-topic "rabbitmq" \
  --add-topic "postgresql" \
  --add-topic "pg-trgm" \
  --add-topic "minio" \
  --add-topic "resilience4j" \
  --add-topic "wiremock" \
  --add-topic "testcontainers" \
  --add-topic "handwriting-recognition" \
  --add-topic "digital-archiving" \
  --add-topic "indic-languages" \
  --add-topic "devanagari" \
  --add-topic "event-driven"
```

---

## ⚙️ Recommended GitHub Repository Settings

1. **Features:**
   - [x] **Issues:** Enabled (using `.github/ISSUE_TEMPLATE`)
   - [x] **Discussions:** Enabled (categories: Q&A, General, Ideas, Show and tell)
   - [ ] **Wiki:** Disabled (all documentation version-controlled in repository)
   - [x] **Projects:** Optional

2. **Pull Requests:**
   - [x] Allow merge commits
   - [x] Allow squash merging (recommended for linear history)
   - [x] Automatically delete head branches upon merge

3. **Vulnerability Alerts:**
   - [x] Dependabot alerts enabled (configured via `.github/dependabot.yml`)
   - [x] Dependabot security updates enabled
   - [x] Secret scanning enabled
