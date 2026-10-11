import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { runProvisioning } from '../../cloud/provision-accounts.mjs';
import { CloudClient } from '../src/client.mjs';
import { sharedKey } from '../src/orders.mjs';

test('Real Firebase rules accept Windows REST transactions and reject lost numbers, reset counters and old Android schema', { timeout: 60000 }, async () => {
  assert.equal(process.env.FIREBASE_AUTH_EMULATOR_HOST, '127.0.0.1:9099');
  assert.equal(process.env.FIREBASE_DATABASE_EMULATOR_HOST, '127.0.0.1:9000');
  const temp = await mkdtemp(path.join(os.tmpdir(), 'milkyway-desktop-emulator-'));
  const config = { projectId: 'demo-milkyway', plantId: 'default', apiKey: 'demo-key', databaseUrl: 'http://127.0.0.1:9000' };
  const options = { emulator: true, authBase: 'http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1', tokenBase: 'http://127.0.0.1:9099/securetoken.googleapis.com/v1' };
  const clients = [new CloudClient(config, options), new CloudClient(config, options)];
  try {
    const csv = path.join(temp, 'accounts.csv'); await runProvisioning('demo-milkyway', 'default', csv);
    const rows = (await readFile(csv, 'utf8')).trim().split('\n').slice(1).map(row => row.split(','));
    await Promise.all(clients.map((client, index) => client.login({ account: rows[index][0], password: rows[index][2], name: index ? 'Jan' : 'Anna' })));
    const input = n => ({ id: `windows-order:00000000-0000-0000-0000-${String(n).padStart(12, '0')}`, line: n === 1 ? 'BUTTER' : 'POWDER', butterKind: 'MIX', title: 'Test', description: '', quantity: '1000,5', note: 'Dostawa', important: true });
    const saved = await Promise.all(clients.map((client, i) => client.submit(input(i + 1))));
    assert.deepEqual(saved.map(r => r.orderNumber).sort(), [1, 2]);
    assert.equal((await clients[0].submit(input(1))).orderNumber, saved[0].orderNumber);
    const snapshot = (await clients[0].database()).data; assert.equal(snapshot.orderSequence, 2);
    assert.equal(Object.keys(snapshot.tables.production_queue).length, 2); assert.equal(Object.keys(snapshot.tables.production_product_notes).length, 2);
    async function denied(change) {
      const { data, response } = await clients[0].database(); change(data); data.actor = { uid: clients[0].session.uid, account: '01' };
      await assert.rejects(clients[0].database('PUT', data, response.headers.get('etag')), /Brak dostępu/);
    }
    await denied(data => { data.schema = 2; });
    await denied(data => { delete data.orderSequence; });
    await denied(data => { data.orderSequence = 0; });
    await denied(data => { delete data.tables.production_queue[sharedKey(input(1).id)].order_number; });
    await denied(data => { data.orderSequence = 3; data.tables.production_queue[sharedKey(input(1).id)].order_number = 3; });
    const { data, response } = await clients[0].database();
    delete data.tables.production_queue[sharedKey(input(2).id)]; data.actor = { uid: clients[0].session.uid, account: '01' };
    await clients[0].database('PUT', data, response.headers.get('etag'));
    const third = await clients[1].submit(input(3)); assert.equal(third.orderNumber, 3);
    const view = await clients[0].view('2026-10-11', '2026-10-11'); assert.equal(view.orders.length, 2);
    assert.equal(view.orders.find(o => o.id === input(3).id).status, 'PENDING');
    assert.equal((await fetch('http://127.0.0.1:9000/plants/default/domains/production.json?ns=demo-milkyway-default-rtdb')).status, 401);
  } finally { clients.forEach(c => c.logout()); await rm(temp, { recursive: true, force: true }); }
});
