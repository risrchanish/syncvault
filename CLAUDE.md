## Session Management Rule

After every major task completion, automatically write a file called
`session-status.md` in the project root with this format:

### Completed:
- [list what is fully done with checkmark]

### In Progress:
- [what was being worked on when paused]

### Next Step (resume prompt):
[exact instruction to paste in a new Claude Code session to continue 
from this exact point — include file names, method names, what was 
working and what was not]

### Files Modified This Session:
- [list every file created or changed]

Write to session-status.md after each of these milestones:
1. Project scaffolded (pom.xml + folder structure done)
2. Entities + Flyway migration done
3. Service layer done
4. Controllers done
5. Kafka producer done
6. Tests passing
7. JaCoCo 80% met

If context feels heavy or responses slow down, stop and update 
session-status.md before continuing.

---

## SyncVault — Final Project Status (Phase 2 Complete)

### Services and Ports

| Service              | Port | DB Port | Notes                                      |
|----------------------|------|---------|--------------------------------------------|
| api-gateway          | 8080 | —       | Spring Cloud Gateway, JWT filter, rate limiting |
| user-service         | 8081 | 5432    | Auth, registration, storage quota tracking |
| file-service         | 8082 | 5433    | Upload/versioning/conflict, AI, search     |
| notification-service | 8083 | 5434    | Kafka consumer, email via Gmail SMTP       |

### Running the Full Stack

```bash
# Prerequisites: Docker Desktop running, set your OpenAI key in the shell
export OPENAI_API_KEY=sk-...

# Start everything
docker compose up -d

# Wait for all services healthy (~90s on first run)
docker compose ps
```

### End-to-End Smoke Test

```bash
# 1. Register a user
curl -s -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password":"Test1234!","name":"Test User"}' | jq .

# 2. Login — copy the accessToken
curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password":"Test1234!"}' | jq .

TOKEN="<paste accessToken here>"

# 3. Upload a file through the Gateway
curl -s -X POST http://localhost:8080/files/upload \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@/path/to/your.pdf" | jq .

FILE_ID="<paste fileId here>"

# 4. Wait 2-3 seconds for async AI processing (virtual threads)
sleep 3

# 5. Check AI summary was populated
curl -s http://localhost:8080/files/$FILE_ID/summary \
  -H "Authorization: Bearer $TOKEN" | jq .
# Expected: aiSummary, aiDescription, aiTags all non-null

# 6. Semantic search
curl -s "http://localhost:8080/files/search?q=your+search+query&limit=5" \
  -H "Authorization: Bearer $TOKEN" | jq .
# Expected: array of results with relevanceScore

# 7. Re-trigger summarization on demand
curl -s -X POST http://localhost:8080/files/$FILE_ID/summarize \
  -H "Authorization: Bearer $TOKEN" | jq .
```

### Key Technical Decisions

**Database**
- `postgres-users` / `postgres-notifications` use `postgres:15` (no vector needed)
- `postgres-files` uses `pgvector/pgvector:pg15` — required for `vector(1536)` type in Flyway V3

**AI / Embedding**
- `ai-sdk` module: `com.syncvault:ai-sdk:1.0.0-SNAPSHOT` — OpenAI + Claude, ResilientLLMClient (CB outer, Retry inner)
- Tika: **both** `tika-core` AND `tika-parsers-standard-package` 2.9.2 required as explicit deps
- Virtual threads: two independent threads per upload — `ai-summary-{id}` and `ai-embed-{id}`
- Embedding stored via JdbcTemplate with `::vector` cast; queried with `<=>` cosine operator

**Security**
- `OPENAI_API_KEY` flows: host env → docker-compose `${OPENAI_API_KEY:-}` → Spring `${OPENAI_API_KEY:not-configured}`
- Key is only ever placed in HTTP `Authorization` / `x-api-key` headers — never printed to logs
- Spring Boot actuator auto-redacts properties containing "key" in `/actuator/env` and `/actuator/configprops`

**Testing**
- Testcontainers postgres image: `pgvector/pgvector:pg15` in **both** `FileServiceIntegrationTest` and `FileServiceApplicationTests`
- Mockito: use `doReturn`/`doThrow` for default interface methods, not `when`/`thenReturn`
- ai-sdk: 80 tests | file-service: 63 tests | api-gateway: 34 tests

### Environment Variables Reference

| Variable           | Required | Service              | Notes                              |
|--------------------|----------|----------------------|------------------------------------|
| OPENAI_API_KEY     | Yes (AI) | file-service         | Falls back to `not-configured`; AI features disabled without it |
| GMAIL_USERNAME     | Optional | notification-service | Email sending disabled if absent   |
| GMAIL_APP_PASSWORD | Optional | notification-service | Gmail App Password, not login password |
