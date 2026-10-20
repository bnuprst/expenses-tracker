// Copies transaction history from an Actual Budget server into an expenses-tracker wallet.
// Idempotent: re-running inserts nothing new (rows are keyed on Actual's transaction id).
//
// Usage (on the VPS, over the shared `edge` network - see vps-infra apps/expenses-tracker/README.md):
//   node import.mjs [--dry-run]
// Environment:
//   ACTUAL_URL           default http://budgeting-app:5006
//   ACTUAL_PASSWORD      Actual server password
//   ACTUAL_SYNC_ID       Settings -> Advanced -> Sync ID of the budget to import
//   ACTUAL_E2E_PASSWORD  only if end-to-end encryption is on
//   EXPENSES_URL         default http://expenses-tracker:8080
//   EXPENSES_EMAIL / EXPENSES_PASSWORD   an owner of the target wallet
//   WALLET_ID            target wallet id
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import api from '@actual-app/api';
import { batches, buildImport } from './mapping.mjs';

const BATCH = 500;
const dryRun = process.argv.includes('--dry-run');
const env = (name, fallback) => {
  const v = process.env[name] ?? fallback;
  if (v === undefined || v === '') throw new Error(`Missing ${name}`);
  return v;
};

async function readActual() {
  const dataDir = mkdtempSync(join(tmpdir(), 'actual-'));
  try {
    await api.init({ dataDir, serverURL: env('ACTUAL_URL', 'http://budgeting-app:5006'), password: env('ACTUAL_PASSWORD') });
    const e2e = process.env.ACTUAL_E2E_PASSWORD;
    await api.downloadBudget(env('ACTUAL_SYNC_ID'), e2e ? { password: e2e } : undefined);
    const accounts = await api.getAccounts();
    const transactionsByAccount = {};
    for (const a of accounts) {
      transactionsByAccount[a.id] = await api.getTransactions(a.id, '1970-01-01', '2999-12-31');
    }
    return {
      accounts,
      payees: await api.getPayees(),
      categories: await api.getCategories(),
      categoryGroups: await api.getCategoryGroups(),
      transactionsByAccount,
    };
  } finally {
    await api.shutdown().catch(() => {});
    rmSync(dataDir, { recursive: true, force: true });
  }
}

async function post(url, body, token) {
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) },
    body: JSON.stringify(body),
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${url} -> HTTP ${res.status}: ${text}`);
  return JSON.parse(text);
}

const actual = await readActual();
const { categories, transactions, stats } = buildImport(actual);
const dates = transactions.map((t) => t.date);
console.log(
  `Actual: ${actual.accounts.length} accounts, ${categories.length} categories, ${transactions.length} transactions` +
    (dates.length ? ` (${dates[0]} .. ${dates.at(-1)})` : '') +
    `, ${transactions.filter((t) => !t.category).length} uncategorized, ${stats.skippedStartingBalance} starting balances skipped`,
);
if (dryRun) {
  console.log('Dry run: nothing sent.');
  process.exit(0);
}

const base = env('EXPENSES_URL', 'http://expenses-tracker:8080').replace(/\/$/, '');
const walletId = env('WALLET_ID');
const { token } = await post(`${base}/api/v1/auth/login`, { email: env('EXPENSES_EMAIL'), password: env('EXPENSES_PASSWORD') });
const total = { received: 0, inserted: 0, categoriesCreated: 0, merchantsLearned: 0 };
let first = true;
for (const chunk of batches(transactions, BATCH)) {
  // Categories ride on the first batch; later batches create any they still reference on the fly.
  const r = await post(`${base}/api/v1/wallets/${walletId}/import`, { source: 'actual', categories: first ? categories : [], transactions: chunk }, token);
  first = false;
  for (const k of Object.keys(total)) total[k] += r[k];
  console.log(`  sent ${total.received}/${transactions.length}, inserted ${total.inserted}`);
}
console.log(`Done: ${JSON.stringify(total)}`);
