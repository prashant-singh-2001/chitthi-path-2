## Description

<!-- Provide a brief summary of the changes introduced by this PR. -->

## Type of Change

- [ ] 🐛 Bug fix (non-breaking change fixing an issue)
- [ ] ✨ New feature (non-breaking change adding functionality)
- [ ] ⚡ Performance improvement
- [ ] ♻️ Code refactoring
- [ ] 📝 Documentation update
- [ ] 🧪 Test addition / modification
- [ ] 🏗️ Infrastructure / Docker update

## Architectural Checklist

- [ ] Idempotency key verified for any new pipeline stages.
- [ ] Transactional Outbox pattern preserved for message dispatch.
- [ ] Rate limits and circuit breakers respected for external APIs.
- [ ] Search query changes preserve owner-scoping (`owner_id`).
- [ ] WireMock contracts included for any new Sarvam endpoints.
- [ ] Flyway migration script added if schema was modified.

## How Has This Been Tested?

<!-- Describe tests executed (WireMock, unit, integration). -->
- [ ] `mvn clean test` passed locally.
- [ ] Verified against Docker Compose backing services.
