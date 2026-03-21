# Crawler Internal API Security Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Harden the internal Python crawler APIs so they are only reachable on the internal network and only callable by the Java backend with a shared internal service key.

**Architecture:** Keep the existing Spring Boot to FastAPI integration, but add one deployment-layer control and one application-layer control. Use fail-fast configuration validation, a shared request header, and small focused tests to prevent accidental public exposure.

**Tech Stack:** Spring Boot 3.2, Java 17, FastAPI, Python 3.11, JUnit 5, unittest, Docker Compose

---

### Task 1: Add failing Python API security tests

**Files:**
- Create: `D:\Git\agent\noval\crawler\tests\test_internal_api_security.py`

- [ ] Step 1: Write tests for missing token, wrong token, correct token, and open health endpoint.
- [ ] Step 2: Run `python -m unittest tests.test_internal_api_security -v` from `D:\Git\agent\noval\crawler` and confirm the new auth expectations fail before implementation.

### Task 2: Add failing Java crawler client/config tests

**Files:**
- Create: `D:\Git\agent\noval\backend\src\test\java\com\novelanalyzer\config\CrawlerConfigValidatorTest.java`
- Create: `D:\Git\agent\noval\backend\src\test\java\com\novelanalyzer\modules\crawler\client\PythonCrawlerClientTest.java`

- [ ] Step 1: Write a config validation test that expects startup validation failure when crawler internal key is blank.
- [ ] Step 2: Write a client test that expects `X-Internal-Service-Token` to be sent on crawler requests.
- [ ] Step 3: Run targeted backend tests and confirm the new expectations fail before implementation.

### Task 3: Implement crawler internal API authentication

**Files:**
- Modify: `D:\Git\agent\noval\crawler\app\config.py`
- Modify: `D:\Git\agent\noval\crawler\app\main.py`
- Modify: `D:\Git\agent\noval\crawler\app\api\book.py`
- Modify: `D:\Git\agent\noval\crawler\app\api\chapter.py`
- Modify: `D:\Git\agent\noval\crawler\app\api\rank.py`

- [ ] Step 1: Add internal API key configuration and startup validation.
- [ ] Step 2: Add reusable request authentication for `/internal/*`.
- [ ] Step 3: Run the Python API security tests and confirm they pass.

### Task 4: Implement Java crawler client authentication and validation

**Files:**
- Modify: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\config\CrawlerProperties.java`
- Create: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\config\CrawlerConfigValidator.java`
- Modify: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\crawler\client\PythonCrawlerClient.java`
- Modify: `D:\Git\agent\noval\backend\src\main\resources\application.yml`
- Modify: `D:\Git\agent\noval\backend\src\test\resources\application.yml`

- [ ] Step 1: Add crawler internal key configuration and startup validation.
- [ ] Step 2: Send the internal auth header on all crawler client requests.
- [ ] Step 3: Run the targeted backend tests and confirm they pass.

### Task 5: Tighten deployment defaults and verify end-to-end

**Files:**
- Modify: `D:\Git\agent\noval\docker-compose.yml`
- Modify: `D:\Git\agent\noval\progress.md`

- [ ] Step 1: Remove crawler host port exposure and inject `CRAWLER_INTERNAL_API_KEY` into backend and crawler.
- [ ] Step 2: Run `python -m unittest discover -s tests -v` from `D:\Git\agent\noval\crawler`.
- [ ] Step 3: Run `mvn test` from `D:\Git\agent\noval\backend`.
- [ ] Step 4: Record the verification evidence and any remaining risk in `D:\Git\agent\noval\progress.md`.
