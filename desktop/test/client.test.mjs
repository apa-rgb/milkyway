import test from 'node:test';
import assert from 'node:assert/strict';
import { CloudClient } from '../src/client.mjs';
import { sharedKey } from '../src/orders.mjs';
const now = Date.parse('2026-10-11T10:00:00Z');
const config = { projectId: 'milyway-41e9e', apiKey: 'test-fixture-key', plantId: 'default', databaseUrl: 'https://test-fixture.firebasedatabase.app' };
const input = n => ({ id: `windows-order:00000000-0000-0000-0000-${String(n).padStart(12, '0')}`, line: 'POWDER', title: 'Proszek', description: '', quantity: '100', note: '' });
const token = account => `fixture.${Buffer.from(JSON.stringify({ plant: 'default', account, role: 'operator' })).toString('base64url')}.fixture`;
function server({ loss = false, delay = false } = {}) {
  let state = null, revision = 0, refreshes = 0, lost = false, writes = 0;
  const fetch = async (url, options) => {
    const parsed = new URL(url);
    if (parsed.pathname.endsWith('accounts:signInWithPassword')) {
      const account = JSON.parse(options.body).email.slice(5, 7); return Response.json({ idToken: token(account), localId: `user${account}`, refreshToken: 'fixture-refresh', expiresIn: '3600' });
    }
    if (parsed.pathname.endsWith('/token')) { refreshes++; return Response.json({ id_token: token('01'), user_id: 'user01', refresh_token: 'fixture-refresh', expires_in: '3600' }); }
    if (options.method === 'GET') return Response.json(state, { headers: { ETag: `"${revision}"` } });
    if (delay) await new Promise(resolve => setTimeout(resolve, 5));
    if (options.headers['if-match'] !== `"${revision}"`) return Response.json({ error: 'conflict' }, { status: 412 });
    state = JSON.parse(options.body); revision++; writes++;
    if (loss && !lost) { lost = true; throw new Error('Connection lost after server commit'); }
    return Response.json(state);
  };
  return { fetch, state: () => state, writes: () => writes, refreshes: () => refreshes };
}
async function client(mock, account = '01', clock = () => now) { const c = new CloudClient(config, { fetchImpl: mock.fetch, now: clock }); await c.login({ account, password: 'fixture-password', name: 'Anna' }); return c; }
test('ETag retry gives simultaneous operators distinct sequential numbers and preserves both orders', async () => {
  const mock = server({ delay: true }), a = await client(mock), b = await client(mock, '02');
  const results = await Promise.all([a.submit(input(1)), b.submit(input(2))]);
  assert.deepEqual(results.map(r => r.orderNumber).sort(), [1, 2]); assert.equal(Object.keys(mock.state().tables.production_queue).length, 2); assert.equal(mock.writes(), 2);
});
test('Ambiguous write result is safe to retry with same request ID', async () => {
  const mock = server({ loss: true }), c = await client(mock);
  await assert.rejects(c.submit(input(1)), /ponów/i); const saved = await c.submit(input(1));
  assert.equal(saved.existing, true); assert.equal(saved.orderNumber, 1); assert.equal(mock.writes(), 1);
});
test('Missing ETag or stale session never writes', async () => {
  const mock = server(), c = await client(mock); const original = mock.fetch;
  c.fetch = async (url, options) => options.method === 'GET' ? Response.json(null) : original(url, options);
  await assert.rejects(c.submit(input(1)), /wersji bazy/); assert.equal(mock.writes(), 0);
  c.logout(); await assert.rejects(c.submit(input(1)), /Zaloguj/);
});
test('Single refresh task is shared by concurrent reads and session tokens remain private', async () => {
  let clock = now; const mock = server(), c = await client(mock, '01', () => clock); clock += 3600_000;
  await Promise.all([c.view('2026-10-11'), c.view('2026-10-11')]); assert.equal(mock.refreshes(), 1);
  assert.deepEqual(c.profile(), { account: '01', name: 'Anna', requiresPasswordChange: false, recoveryEmail: '' });
});
test('Order number survives Android progress updates and appears in desktop warehouse', async () => {
  const mock = server(), c = await client(mock); await c.submit(input(1));
  const state = mock.state(), row = state.tables.production_queue[sharedKey(input(1).id)]; row.pending_order = 0; row.produced_amount = '100';
  state.tables.production_completions = { [sharedKey('receipt')]: { id: 'receipt', entry_id: row.id, _rowid: 1, amount: '100', produced_on: '2026-10-11' } };
  const view = await c.view('2026-10-11'); assert.equal(view.orders[0].status, 'COMPLETED'); assert.equal(view.warehouse.products[0].orderNumber, 1);
});

test('Loss of the password-change acknowledgement reports uncertainty and never saves a plaintext password', async () => {
  const mock = server(), c = await client(mock); const original = c.fetch;
  c.fetch = async (url, options) => {
    if (String(url).includes('accounts:update')) throw new Error('Lost acknowledgement after changing password');
    return original(url, options);
  };
  await assert.rejects(c.changePassword({ current: 'fixture-password', password: 'Chosen88', email: 'anna@example.com' }), /potwierdzić zmiany hasła/);
  assert(!JSON.stringify(c.profile()).includes('Chosen88'));
});
