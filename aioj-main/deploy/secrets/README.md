# Secrets

Place real production secrets here only on deployment hosts. Files in this
directory are ignored by git.

Recommended files:

- `jwt-private.pem`
- `jwt-public.pem`
- `ai-api-key`
- `sandbox-token`

For local development, `.env` values are enough. Rotate any key that has ever
been committed or pasted into chat.
