import test from 'node:test';
import assert from 'node:assert/strict';
import { addOrder, quantity, sharedKey, orderList, warehouseList, warehouseDay, warsawDay, productionTimeline } from '../src/orders.mjs';
export const actor = { uid: 'operator01', account: '01' };
export const now = Date.parse('2026-10-11T10:00:00Z');
export const input = (n = 1, overrides = {}) => ({ id: `windows-order:00000000-0000-0000-0000-${String(n).padStart(12, '0')}`, line: 'BUTTER', butterKind: 'MIX', title: 'Produkt', description: 'Kostka 200 g', quantity: '1250,5', note: 'Pilna dostawa jutro', important: true, ...overrides });
test('Polish decimal stays exact beyond floating-point precision', () => {
  assert.equal(quantity(' 0001250,500 '), '1250.5'); assert.equal(quantity('999999999999999999999.123'), '999999999999999999999.123');
  for (const invalid of ['0', '-1', '1e3', 'NaN', '1.0001', '9'.repeat(25)]) assert.throws(() => quantity(invalid));
});
test('All lines create pending orders and Android-compatible annotation', () => {
  let state = null;
  for (const [n, line] of ['BUTTER', 'POWDER', 'UHT'].entries()) state = addOrder(state, input(n + 1, { line }), actor, 'Anna', now).document;
  assert.equal(state.schema, 3); assert.equal(state.orderSequence, 3);
  const rows = Object.values(state.tables.production_queue); assert.deepEqual(rows.map(r => r.order_number), [1, 2, 3]);
  assert(rows.every(r => r.pending_order === 1 && r.produced_amount === '0' && !Object.hasOwn(r, 'planned_time')));
  assert.deepEqual(rows.map(r => r.unit), ['KG', 'KG', 'LITRES']); assert.equal(rows[0].description, 'Rodzaj produktu: Mix\nKostka 200 g');
  assert.equal(Object.values(state.tables.production_product_notes)[0].note, '[Ważne]\n[Autor: Anna]\nPilna dostawa jutro');
});
test('Mutation retains unrelated rows, receipts, audit and unknown fields', () => {
  const old = addOrder(null, input(), actor, 'Anna', now).document; old.future = 'keep';
  old.tables.production_completions = { [sharedKey('receipt')]: { id: 'receipt', entry_id: input().id, amount: '20', produced_on: '2026-10-11', occurred_at: now, _rowid: 1, _actor: actor } };
  old.tables.production_queue[sharedKey(input().id)].futureColumn = 'keep'; const before = structuredClone(old);
  const next = addOrder(old, input(2), { uid: 'operator02', account: '02' }, 'Jan', now).document;
  assert.deepEqual(old, before); assert.deepEqual(next.tables.production_completions, old.tables.production_completions);
  assert.deepEqual(next.tables.production_queue[sharedKey(input().id)], old.tables.production_queue[sharedKey(input().id)]); assert.equal(next.future, 'keep');
  assert.equal(next.tables.production_queue[sharedKey(input(2).id)].position, 1);
});
test('Retry after acknowledgement loss or Android scheduling never allocates another number', () => {
  const state = addOrder(null, input(), actor, 'Anna', now).document;
  state.tables.production_queue[sharedKey(input().id)].pending_order = 0;
  const retry = addOrder(state, input(), actor, 'Anna', now + 100000);
  assert.equal(retry.existing, true); assert.strictEqual(retry.document, state); assert.equal(state.orderSequence, 1);
  assert.throws(() => addOrder(state, input(1, { quantity: '2000' }), actor, 'Anna', now));
});
test('Sequence survives removal of latest order and crosses departments and year', () => {
  let state = addOrder(null, input(), actor, 'Anna', now).document; delete state.tables.production_queue[sharedKey(input().id)];
  state = addOrder(state, input(2, { line: 'UHT' }), actor, 'Anna', Date.parse('2027-01-01T10:00:00Z')).document;
  assert.equal(state.orderSequence, 2); assert.equal(state.tables.production_queue[sharedKey(input(2).id)].order_number, 2);
});
test('Optional note and kind validation; unsupported snapshots never mutate', () => {
  const state = addOrder(null, input(1, { note: '', important: false }), actor, '', now).document;
  assert.equal(Object.keys(state.tables.production_product_notes).length, 0);
  assert.throws(() => addOrder(null, input(1, { butterKind: '' }), actor, '', now));
  for (const snapshot of [{ schema: 99, revision: 1 }, { schema: 3, revision: 1, tables: { unexpected: {} } }]) assert.throws(() => addOrder(snapshot, input(), actor, '', now));
});
test('Warehouse displays original order number, partial/excess and excludes removed receipts', () => {
  let state = null; for (const n of [1, 2, 3]) state = addOrder(state, input(n, { quantity: '100' }), actor, '', now).document;
  const queue = state.tables.production_queue; queue[sharedKey(input().id)].produced_amount = '40'; queue[sharedKey(input(2).id)].produced_amount = '110'; queue[sharedKey(input(3).id)].produced_amount = '100';
  state.tables.production_completions = Object.fromEntries([1, 2, 3].map(n => [sharedKey(`r${n}`), { id: `r${n}`, _rowid: n, entry_id: input(n).id, amount: n === 1 ? '40' : n === 2 ? '110' : '100', produced_on: '2026-10-11' }]));
  state.tables.production_completions[sharedKey('r3')].warehouse_removed_at = now;
  const rows = warehouseList(state, '2026-10-11').products;
  assert.deepEqual(rows.map(r => [r.orderNumber, r.status]), [[1, 'PARTIAL'], [2, 'EXCESS']]); assert.equal(rows[0].remaining, '60'); assert.equal(rows[1].excess, '10');
  assert.deepEqual(orderList(state).map(r => r.status), ['COMPLETED', 'COMPLETED', 'PENDING']);
});
test('09:00 warehouse boundary, Warsaw receipt date and year boundary', () => {
  assert.equal(warehouseDay(new Date('2026-10-11T06:59:59Z')), '2026-10-10'); assert.equal(warehouseDay(new Date('2026-10-11T07:00:00Z')), '2026-10-11');
  assert.equal(warsawDay(new Date('2026-10-11T22:30:00Z')), '2026-10-12'); assert.equal(warehouseDay(new Date('2027-01-01T07:00:00Z')), '2026-12-31');
});

test('Timeline includes Android entries and scheduled partial orders in start order, excluding pending, complete and other days', () => {
  let state = null;
  for (let n = 1; n <= 6; n++) state = addOrder(state, input(n, { line: n === 2 ? 'POWDER' : 'BUTTER' }), actor, 'Anna', now).document;
  const rows = Object.values(state.tables.production_queue);
  Object.assign(rows[0], { pending_order: 0, planned_time: '12:30', produced_amount: '250' });
  Object.assign(rows[1], { pending_order: 0, planned_time: '09:15' });
  Object.assign(rows[2], { pending_order: 0, planned_time: '11:00', plan_date: '2026-10-12' });
  Object.assign(rows[3], { pending_order: 0, planned_time: '08:00', produced_amount: '1250.5' });
  Object.assign(rows[4], { pending_order: 0 });
  const native = { ...rows[0], id: 'android-order', _rowid: 7, title: 'Dodane na telefonie', planned_time: '23:45' }; delete native.order_number;
  state.tables.production_queue[sharedKey(native.id)] = native;
  const entries = productionTimeline(state, '2026-10-11').entries;
  assert.deepEqual(entries.map(r => r.time), ['09:15', '12:30', '23:45', null]);
  assert.equal(entries[1].remaining, '1000.5'); assert.equal(entries[1].orderNumber, 1); assert.equal(entries[2].orderNumber, null);
  assert.equal(entries[1].note, 'Pilna dostawa jutro'); assert.equal(entries[1].important, true);
  for (const invalid of ['2026-02-30', '2026-99-01', '']) { assert.throws(() => productionTimeline(state, invalid)); assert.throws(() => warehouseList(state, invalid)); }
});

test('Timeline retains a legacy Android plan whose quantity has not yet been entered', () => {
  const state = addOrder(null, input(), actor, 'Anna', now).document;
  Object.assign(Object.values(state.tables.production_queue)[0], { pending_order: 0, planned_time: '10:00', planned_amount: null });
  const entry = productionTimeline(state, '2026-10-11').entries[0];
  assert.equal(entry.remaining, null); assert.equal(entry.quantity, null);
});
