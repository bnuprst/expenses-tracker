# Expenses Tracker

A self-hosted family expense tracker. It pulls bank transactions through [Enable Banking](https://enablebanking.com) and categorizes them automatically. Users share wallets with family members and use the native Android client.

```
shared/    Kotlin DTOs used by the backend and the Android app
backend/   Ktor + Postgres API, categorization pipeline, bank sync
android/   Jetpack Compose client
deploy/    deploy/backup/restore scripts, systemd timer (prod compose file at the root)
tools/     actual-import: one-off import of Actual Budget history
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
- **Scheduled pull**: runs every `SYNC_INTERVAL` (default 8h). The first sync fetches `EB_INITIAL_HISTORY_DAYS`. After that, each sync starts 7 days before the last booking date, because banks book late.
- **Bank rate limits**: under PSD2, banks allow only about 4 background requests per account per day.
  - Syncs the user starts (*Sync now*, the first sync after connecting) forward the user's IP and User-Agent as `Psu-Ip-Address` / `Psu-User-Agent`, so the bank doesn't count them against that limit.
  - The default 8h interval leaves headroom under the limit; don't set `SYNC_INTERVAL` below 6h. A `.env` copied from an older `.env.example` still has `SYNC_INTERVAL=PT6H`: change it to `PT8H`.
  - When the bank still answers 429 to a scheduled sync, scheduled syncs of that connection pause until the next day (UTC).
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

### Production
On the netcup VPS the stack runs behind the shared edge Caddy from
[bnuprst/vps-infra](https://github.com/bnuprst/vps-infra). The step-by-step cutover is in `apps/expenses-tracker/README.md` there (DNS, deploy key, edge override, importing Actual, retiring Actual).

| File | What |
|---|---|
| `docker-compose.prod.yml` | `app` (built on the box) + `db` (Postgres 16). Publishes no ports. |
| `.env.example` | Copy to `.env` (chmod 600). |
| `.secrets/enablebanking.pem` | The Enable Banking private key, mounted as a Docker secret. |
| `deploy/deploy.sh` | Builds, starts and waits for healthy. Adds `-f docker-compose.edge.yml` when vps-infra's `bin/attach-app` has installed it. |
| `deploy/backup.sh` / `restore.sh` | Nightly `pg_dump` to `/var/backups/expenses-tracker` with 14-day retention; restore asks for confirmation. |
| `deploy/systemd/` | Backup timer (~03:40). |

```bash
cd /opt/expenses-tracker && git pull --ff-only && deploy/deploy.sh
```

In the Enable Banking control panel, register `EB_REDIRECT_URL` (`https://expenses.bookkeebara.com/api/v1/banking/callback`) as a redirect URL of the application.

### Importing history from Actual Budget
`tools/actual-import` reads a budget through Actual's official API and posts it to `POST /api/v1/wallets/{id}/import`:
- **Idempotent:** rows are keyed on Actual's transaction id, so re-running inserts nothing new.
- **Categories:** created by name. Split transactions are imported as their parts; transfers go to `Transfers`.
- **Your decisions carry over:** categories chosen in Actual are stored as manual and seed merchant memory, using the majority category per merchant. The LLM is never called during an import.
- **No double-counting:** a bank account's first sync starts the day after the imported history ends, so nothing is counted twice.

Run it on the box as described in vps-infra's runbook, with `--dry-run` first.

### LLM providers
Any OpenAI-compatible `/chat/completions` endpoint works:

| Provider   | `LLM_BASE_URL`                   | `LLM_API_KEY` |
|------------|----------------------------------|---------------|
| OpenAI     | `https://api.openai.com/v1`      | required      |
| Ollama     | `http://ollama:11434/v1`         | not needed    |
| OpenRouter | `https://openrouter.ai/api/v1`   | required      |

The default is OpenAI `gpt-6-luna`, with `LLM_REASONING_EFFORT=low` in `.env.example`. `temperature` and `reasoning_effort` are only sent when `LLM_TEMPERATURE` / `LLM_REASONING_EFFORT` are set. GPT-5/6 models reject non-default temperatures, and other providers may not accept `reasoning_effort`. When using a non-OpenAI provider, set `LLM_MODEL` to a model that provider serves. With no key and no base URL set, the LLM step is skipped, and unknown merchants stay uncategorized until you categorize them.

## Android app

```bash
cd android
../gradlew assembleDebug     # needs the Android SDK (set sdk.dir in android/local.properties)
```
- **Server URL**: you enter it on the login screen. Cleartext HTTP is allowed so a LAN server works during development.
- **Bank flow**: the bank authorization opens in a Custom Tab, and the app receives the `expenses://bank-connected` deep link when it finishes.
- **Languages**: English (default and fallback), Ukrainian and Polish. The app follows the system language and can
  be overridden in-app (wallet list → ⋮ → Language, or the Language button on the login screen). On Android 13+ the
  in-app switcher writes the framework per-app locale, so Settings → App languages and the in-app picker are the same
  setting; below 13 the choice is stored in the `session` preferences and applied in `attachBaseContext`. Changing the
  language refetches server-rendered text, so an error already on screen is re-read in the new language. Translations
  live in `android/app/src/main/res/values{,-uk,-pl}/strings.xml`. The backend translates its API error messages per
  request from `Accept-Language` (`backend/src/main/kotlin/com/bnuprst/expenses/i18n/Messages.kt`), and the app sends
  that header on every request. Seeded category names carry a stable `slug` and are shown in the UI language;
  categories you create yourself are shown exactly as typed. Bank-provided sync error text is passed through
  untranslated; backend-generated sync errors are stored as a key and rendered in each reader's language.
  `StringResourceParityTest` enforces key parity, placeholder parity and per-language plural quantities across
  `values{,-uk,-pl}`; adding a language means updating `res/xml/locales_config.xml` and `resourceConfigurations`
  in `android/app/build.gradle.kts` together, and `./gradlew :app:lintDebug` fails on
  `MissingTranslation`/`ExtraTranslation`/`StringFormat*`.

## API overview (`/api/v1`, Bearer JWT)

| Method | Path | |
|---|---|---|
| POST | `/auth/register`, `/auth/login` | returns `{token, user}` |
| GET/POST | `/wallets` | list / create (default categories are seeded) |
| GET/POST | `/wallets/{id}/members` | owners add existing users by email |
| GET | `/wallets/{id}/categories` | |
| GET/POST | `/wallets/{id}/rules` | |
| GET/POST | `/wallets/{id}/transactions` | filters: `from`, `to`, `status`, `category` (id or `none`), `q` (description/counterparty search), `sort` (`DATE_DESC`, `DATE_ASC`, `AMOUNT_ASC`, `AMOUNT_DESC`), `limit`, `offset`; rows carry `baseAmount`/`baseCurrency`/`baseRateDate` (null when FX is off or no rate is known) |
| GET | `/wallets/{id}/review` | review queue |
| GET | `/wallets/{id}/stats?from=&to=` | per-category totals in the wallet currency plus a 6-month trend (defaults to the current month); transfers excluded by category kind or by a detected own-account pair, and not counted in `otherCurrencyCount` |
| PATCH | `/transactions/{id}` | `{categoryId}`, learns merchant memory |
| GET | `/banking/aspsps?country=FI` | available banks |
| POST | `/wallets/{id}/import` | owner only; bulk history import (see above) |
| POST | `/wallets/{id}/bank-connections` | `{aspspName, country}` → `{connectionId, authUrl}` |
| GET | `/bank-connections?wallet=` | status, consent expiry, accounts, last sync |
| POST | `/bank-connections/{id}/sync`, `/bank-connections/{id}/reauthorize` | |

## Currencies and internal transfers

**Main currency** is the wallet's currency (`Wallets.currency`). There is no separate per-user currency yet.

**Rates.** Daily reference rates come from `FX_API_BASE` (ECB via Frankfurter by default) and are stored in
`fx_rates`; `fx_coverage` records which calendar days have already been asked about. The provider publishes on
business days only, so a booking on a weekend or holiday uses the latest published rate up to `FX_MAX_STALE_DAYS`
and reports the day actually used as `baseRateDate`. Recent days with no publication yet are provisional and
retried on the refresh interval until they publish (`FX_PROVISIONAL_DAYS` bounds how long that retry applies). A
currency the provider does not carry (for example UAH) simply has no rate: those rows stay unconverted rather than
being counted 1:1, and the app shows them as their own labelled pair. To pin a rate by hand, `INSERT` into
`fx_rates` and add an `fx_coverage` row with `published = true`.

**Detected transfers.** A movement between two of the user's own accounts is left out of the daily totals and out
of `/stats`, and is never counted in `otherCurrencyCount`; both legs share a `transfer_group`. Pairing is
evidence-based: both legs must be in
identified accounts in the same wallet, at most 3 days apart, with an exact equal amount in one currency (or within
2% after conversion across currencies), **plus** one of: matching IBAN digit suffixes, a transfer/exchange keyword,
or the shape of a same-day, same-account two-currency exchange. Rows whose account is unknown (manually added
transactions) never pair, and unrelated look-alikes that merely share an amount and date stay visible. Explicitly
assigning a non-transfer category to either leg (`PATCH /transactions/{id}`) releases the pair and a later scan
does not re-flag it; a category supplied by a CSV import does **not** suppress detection. Categorizing a row with a
`TRANSFER` category excludes it by hand.

When a newly fetched rate completes a pair, every wallet those dates can serve is rescanned in the background, so
a pair can appear without a new import. Only the app's **daily** totals are FX-converted: `/stats` totals remain
wallet-currency-only and are not converted.

## Not yet built
Budgets, push notifications (for example, consent renewal reminders), offline mode in the app, a rule editor UI, FX-converted statistics totals (daily totals are converted; `/stats` remains wallet-currency-only), refresh tokens.
