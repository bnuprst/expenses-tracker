# Expenses Tracker

A self-hosted family expense tracker. It pulls bank transactions through [Enable Banking](https://enablebanking.com) and categorizes them automatically. Users share wallets with family members and use the native Android client.

```
shared/    Kotlin DTOs used by the backend and the Android app
backend/   Ktor + Postgres API, categorization pipeline, bank sync
android/   Jetpack Compose client
deploy/    Docker Compose, Caddy snippet, env template
```

## How categorization works

Each transaction goes through these steps in order. The first step that answers wins:

1. **Normalize**: the counterparty name (or the description) is reduced to a stable merchant key. Card numbers, dates, references and legal suffixes are removed, so `K-MARKET KAMPPI 12.10 *1234` becomes `k market kamppi`.
2. **Merchant memory**: an exact match on the merchant key, learned from your corrections within that wallet.
3. **Rules**: ordered rules on description, merchant, counterparty or amount (`CONTAINS`, `EQUALS`, `REGEX`, `GT`, `LT`).
4. **LLM fallback**: an OpenAI-compatible model picks a category and reports a confidence.
   - Below `LLM_CONFIDENCE_THRESHOLD`, the transaction goes to the **review queue** with the model's suggestion attached.
   - Within one sync run, each unknown merchant is sent to the model only once.

Correcting or confirming a category (`PATCH /api/v1/transactions/{id}`) does two things:
- It writes the merchant into memory.
- It re-categorizes that merchant's other transactions that are still in review or uncategorized.

As a result, LLM calls drop over time.

## Bank sync

- **Authentication**: an application JWT (RS256, `kid` = application id) signed with your Enable Banking private key.
- **Connect flow**: `POST /wallets/{id}/bank-connections` returns the bank's authorization URL. The bank redirects to `/api/v1/banking/callback`. The backend then creates the session, stores the accounts, starts a first sync and redirects the browser to the app (`expenses://bank-connected`).
- **Scheduled pull**: runs every `SYNC_INTERVAL` (default 6h). The first sync fetches `EB_INITIAL_HISTORY_DAYS`. After that, each sync starts 7 days before the last booking date, because banks book late.
- **Dedup**: a transaction is unique on (account, `entry_reference`). When a bank sends no reference, a content hash with an occurrence index is used instead. Pending transactions are skipped until they are booked.
- **Consent**: requested for `min(EB_CONSENT_DAYS, the bank's maximum)`.
  - The connection shows `renewSoon` 7 days before expiry and becomes `EXPIRED` afterwards.
  - `POST /bank-connections/{id}/reauthorize` renews it and keeps the accounts and their history.

## Running the backend

### Local development
```bash
# Postgres with database/user/password "expenses"
export JWT_SECRET=dev
./gradlew :backend:run
```

### Tests
```bash
# Uses TEST_DB_URL when set, otherwise Testcontainers (needs Docker)
TEST_DB_URL=jdbc:postgresql://localhost:5432/expenses_test ./gradlew :backend:test
```

### Production (behind your existing Caddy)
```bash
cd deploy
cp .env.example .env                 # fill in secrets
mkdir -p secrets && cp /path/to/enablebanking-private.pem secrets/enablebanking.pem
docker compose up -d --build
```
Then add `Caddyfile.snippet` to your Caddyfile. Make sure `expenses-api` and Caddy share the external `caddy` network; rename it in `docker-compose.yml` if yours differs.

In the Enable Banking control panel, register `EB_REDIRECT_URL` (`https://<your host>/api/v1/banking/callback`) as an allowed redirect URL for the application.

### LLM providers
Any OpenAI-compatible `/chat/completions` endpoint works:

| Provider   | `LLM_BASE_URL`                   | `LLM_API_KEY` |
|------------|----------------------------------|---------------|
| OpenAI     | `https://api.openai.com/v1`      | required      |
| Ollama     | `http://ollama:11434/v1`         | not needed    |
| OpenRouter | `https://openrouter.ai/api/v1`   | required      |

Set `LLM_MODEL` to a model your provider serves. With no key and no base URL set, the LLM step is skipped, and unknown merchants stay uncategorized until you categorize them.

## Android app

```bash
cd android
../gradlew assembleDebug     # needs the Android SDK (set sdk.dir in android/local.properties)
```
- **Server URL**: you enter it on the login screen. Cleartext HTTP is allowed so a LAN server works during development.
- **Bank flow**: the bank authorization opens in a Custom Tab, and the app receives the `expenses://bank-connected` deep link when it finishes.

## API overview (`/api/v1`, Bearer JWT)

| Method | Path | |
|---|---|---|
| POST | `/auth/register`, `/auth/login` | returns `{token, user}` |
| GET/POST | `/wallets` | list / create (default categories are seeded) |
| GET/POST | `/wallets/{id}/members` | owners add existing users by email |
| GET | `/wallets/{id}/categories` | |
| GET/POST | `/wallets/{id}/rules` | |
| GET/POST | `/wallets/{id}/transactions` | filters: `from`, `to`, `status`, `category`, `limit`, `offset` |
| GET | `/wallets/{id}/review` | review queue |
| PATCH | `/transactions/{id}` | `{categoryId}`, learns merchant memory |
| GET | `/banking/aspsps?country=FI` | available banks |
| POST | `/wallets/{id}/bank-connections` | `{aspspName, country}` → `{connectionId, authUrl}` |
| GET | `/bank-connections?wallet=` | status, consent expiry, accounts, last sync |
| POST | `/bank-connections/{id}/sync`, `/bank-connections/{id}/reauthorize` | |

## Not yet built
Budgets and reports, push notifications (for example, consent renewal reminders), offline mode in the app, a rule editor UI, multi-currency conversion, refresh tokens.
