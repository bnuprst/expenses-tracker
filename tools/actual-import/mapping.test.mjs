import { test } from 'node:test';
import assert from 'node:assert/strict';
import { batches, buildImport, centsToDecimal, TRANSFERS } from './mapping.mjs';

const fixture = {
  accounts: [{ id: 'acc1', name: 'Pekao' }, { id: 'acc2', name: 'Savings' }],
  payees: [
    { id: 'p1', name: 'Biedronka' },
    { id: 'p2', name: 'Employer' },
    { id: 'pt', name: '', transfer_acct: 'acc2' },
  ],
  categoryGroups: [{ id: 'g1', name: 'Spending', is_income: false }, { id: 'g2', name: 'Income', is_income: true }],
  categories: [
    { id: 'c1', name: 'Food', group_id: 'g1' },
    { id: 'c2', name: 'Household', group_id: 'g1' },
    { id: 'c3', name: 'Salary', group_id: 'g2' },
  ],
  transactionsByAccount: {
    acc1: [
      { id: 'sb', account: 'acc1', date: '2024-01-01', amount: 100000, starting_balance_flag: true },
      { id: 't1', account: 'acc1', date: '2024-01-05', amount: -2599, payee: 'p1', category: 'c1', notes: 'weekly shop' },
      {
        id: 'split', account: 'acc1', date: '2024-01-06', amount: -5000, payee: 'p1', is_parent: true,
        subtransactions: [
          { id: 's1', account: 'acc1', date: '2024-01-06', amount: -3000, payee: 'p1', category: 'c1', is_child: true },
          { id: 's2', account: 'acc1', date: '2024-01-06', amount: -2000, payee: 'p1', category: 'c2', is_child: true },
        ],
      },
      { id: 't2', account: 'acc1', date: '2024-01-10', amount: 750000, payee: 'p2', category: 'c3' },
      { id: 'tr', account: 'acc1', date: '2024-01-11', amount: -10000, payee: 'pt', transfer_id: 'tr2' },
      { id: 'u1', account: 'acc1', date: '2024-01-12', amount: -5, imported_payee: 'SKLEP 123' },
      { id: 'gone', account: 'acc1', date: '2024-01-13', amount: -1, tombstone: true },
    ],
    acc2: [],
  },
};

test('centsToDecimal avoids floating point', () => {
  assert.equal(centsToDecimal(-2599), '-25.99');
  assert.equal(centsToDecimal(5), '0.05');
  assert.equal(centsToDecimal(-5), '-0.05');
  assert.equal(centsToDecimal(750000), '7500.00');
});

test('maps transactions, splits, transfers and income categories', () => {
  const { categories, transactions, stats } = buildImport(fixture);
  assert.equal(stats.skippedStartingBalance, 1);
  assert.deepEqual(transactions.map((t) => t.externalId), ['t1', 's1', 's2', 't2', 'tr', 'u1']);

  const byId = Object.fromEntries(transactions.map((t) => [t.externalId, t]));
  assert.deepEqual(byId.t1, { externalId: 't1', date: '2024-01-05', amount: '-25.99', payee: 'Biedronka', notes: 'weekly shop', category: 'Food', account: 'Pekao' });
  assert.equal(byId.s2.category, 'Household');
  assert.equal(byId.tr.category, TRANSFERS);
  assert.equal(byId.tr.payee, null);
  assert.equal(byId.u1.category, null);
  assert.equal(byId.u1.payee, 'SKLEP 123');

  assert.deepEqual(categories.find((c) => c.name === 'Salary'), { name: 'Salary', kind: 'INCOME' });
  assert.deepEqual(categories.at(-1), { name: TRANSFERS, kind: 'TRANSFER' });
});

test('batches', () => {
  assert.deepEqual([...batches([1, 2, 3, 4, 5], 2)], [[1, 2], [3, 4], [5]]);
});
