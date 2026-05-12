# Security Notes

This repository is intended to be safe for public source control.

## Secrets

- Do not commit `.env` or environment-specific configuration files.
- Keep real database passwords, MinIO credentials, API keys, private keys, and certificates outside the repository.
- `.env.example` contains local-development placeholders only.
- Rotate any credential immediately if it is accidentally committed.

## Local Development Defaults

The Docker Compose credentials are for local development only. Replace them with environment-specific secrets before running outside a local workstation.

## Reporting

For a public fork, use the repository issue tracker or your preferred private disclosure process for security reports.
