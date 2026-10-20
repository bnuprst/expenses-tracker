// Pure mapping from Actual's API entities to expenses-tracker's ImportRequest. Kept free of I/O so it
// can be tested without an Actual server.

export const TRANSFERS = 'Transfers';

/** Actual stores amounts as integer cents; format without going through floating point. */
export function centsToDecimal(cents) {
  const sign = cents < 0 ? '-' : '';
  const abs = Math.abs(cents);
  return `${sign}${Math.floor(abs / 100)}.${String(abs % 100).padStart(2, '0')}`;
}

/** Leaf rows only: a split parent's amount is the sum of its children, which carry the categories. */
export function flatten(transactions) {
  const out = [];
  for (const t of transactions) {
    if (t.tombstone) continue;
    if (t.is_parent) {
      // getTransactions returns children nested; tolerate a flat list (children listed separately) too.
      for (const c of t.subtransactions ?? []) if (!c.tombstone) out.push(c);
    } else {
      out.push(t);
    }
  }
  // De-duplicate in case children appear both nested and flat.
  return [...new Map(out.map((t) => [t.id, t])).values()];
}

export function buildImport({ accounts, payees, categories, categoryGroups, transactionsByAccount }) {
  const payeeById = new Map(payees.map((p) => [p.id, p]));
  const groupById = new Map(categoryGroups.map((g) => [g.id, g]));
  const categoryById = new Map(categories.map((c) => [c.id, c]));
  const accountById = new Map(accounts.map((a) => [a.id, a]));

  const isIncome = (c) => Boolean(c.is_income ?? groupById.get(c.group_id)?.is_income);
  const importCategories = categories.map((c) => ({ name: c.name, kind: isIncome(c) ? 'INCOME' : 'EXPENSE' }));
  importCategories.push({ name: TRANSFERS, kind: 'TRANSFER' });

  const stats = { skippedStartingBalance: 0 };
  const rows = [];
  for (const [accountId, transactions] of Object.entries(transactionsByAccount)) {
    for (const t of flatten(transactions)) {
      if (t.starting_balance_flag) {
        stats.skippedStartingBalance++;
        continue;
      }
      const payee = t.payee ? payeeById.get(t.payee) : undefined;
      const isTransfer = Boolean(t.transfer_id || payee?.transfer_acct);
      const category = isTransfer ? TRANSFERS : t.category ? categoryById.get(t.category)?.name ?? null : null;
      rows.push({
        externalId: t.id,
        date: t.date,
        amount: centsToDecimal(t.amount),
        payee: (isTransfer ? null : payee?.name) ?? t.imported_payee ?? null,
        notes: t.notes || null,
        category,
        account: accountById.get(t.account ?? accountId)?.name ?? null,
      });
    }
  }
  rows.sort((a, b) => a.date.localeCompare(b.date) || a.externalId.localeCompare(b.externalId));
  return { categories: importCategories, transactions: rows, stats };
}

export function* batches(items, size) {
  for (let i = 0; i < items.length; i += size) yield items.slice(i, i + size);
}
