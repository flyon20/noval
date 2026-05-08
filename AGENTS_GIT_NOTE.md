# Git Note

## Purpose

This file defines what must **never** be committed or pushed to GitHub from this repository.

The goal is to keep the public repository free of:

- secrets
- private environment configuration
- local deployment state
- machine-specific runtime files
- personal planning notes

## Never Commit

The following categories must never be committed:

- any `.env` file except checked-in examples
- any secret key, token, password, cookie, session value, or credential
- local Docker override files used for private environments
- Redis dump / appendonly files
- local log files
- local scratch files and debug outputs
- personal planning notes and private working documents
- docs directory content unless explicitly approved for public release
- generated runtime artifacts
- local service start/stop helper outputs

## Sensitive Files

Examples of files that must not be pushed:

- `.env`
- `.env.*`
- `*.key`
- `*.pem`
- `*.p12`
- `*.jks`
- `*.secret`
- `dump.rdb`
- `appendonly.aof`
- `docker-compose.override.yml`
- `docker/*.local.yml`
- `docker/*.private.yml`
- `findings.md`
- `progress.md`
- `task_plan.md`
- `docs/`
- `docs/superpowers/plans/*.md`
- `docs/superpowers/specs/*.md`

## Allowed Exceptions

These are allowed:

- `README.md`
- `.env.example`
- `frontend/.env.example`
- public product or rollout docs only when explicitly approved before staging

## Rule For Markdown Files

Markdown files are **not** automatically safe.

Do not commit markdown files if they contain:

- server addresses
- internal IPs
- private operational commands
- credentials
- customer data
- temporary planning content
- internal-only architecture notes that should not be public

Default rule:

- keep only public-facing docs and durable repo documentation
- keep private planning notes out of GitHub

## Before Any Push

Always check:

```bash
git status --short
git diff --cached --name-only
```

If any suspicious file appears, stop and remove it from staging first.
