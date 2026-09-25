# Security Policy

## Supported Versions

We actively support and provide security patches for the following versions of Chitthi:

| Version | Supported          |
| ------- | ------------------ |
| 0.1.x   | :white_check_mark: |
| < 0.1.0 | :x:                |

---

## Reporting a Vulnerability

The Chitthi team takes the security of family heritage documents, personal letters, and system infrastructure seriously.

If you believe you have discovered a security vulnerability in Chitthi (including data leakage, unauthorized access to presigned URLs, API key exposure, or injection vulnerabilities):

1. **Do NOT open a public GitHub issue.**
2. Send an email to the security maintainers at **security@chitthi.internal** (or submit via [GitHub Private Vulnerability Reporting](https://github.com/prashant-singh-2001/chitthi-path-2/security/advisories/new)).
3. Please include:
   - A description of the vulnerability and potential impact.
   - Step-by-step instructions or proof-of-concept (PoC) to reproduce the issue.
   - Affected components (e.g., API Gateway, MinIO storage policies, Search endpoints, Worker queues).
   - Any proposed mitigations or patches if available.

### Response Timeline
- **Initial Acknowledgment:** Within 48 hours of receipt.
- **Triage & Assessment:** Within 5 business days.
- **Fix Release & Disclosure:** We will coordinate with the reporter on a timeline before public disclosure (typically 30–90 days depending on severity).

---

## Security Best Practices for Deployments

1. **API Keys & Credentials:**
   - Never commit `SARVAM_API_KEY`, database passwords, or MinIO secret keys to source control.
   - Use environment variables or a secret management service (e.g., HashiCorp Vault, AWS Secrets Manager).

2. **Storage Privacy:**
   - Chitthi deliberately disables anonymous downloads on MinIO (`chitthi-documents` bucket).
   - Never enable public read access on document buckets storing scanned personal letters.
   - All client downloads must use time-limited presigned URLs (default expiry: 15 minutes).

3. **Multi-Tenant Scoping:**
   - Search queries in `PageRepository` must always be scoped by `owner_id` to prevent cross-tenant data exposure.

4. **Rate Limiting & Cost Guardrails:**
   - Ensure Resilience4j rate limiters and daily word caps are enabled in production to prevent denial-of-wallet attacks against paid AI APIs.
