import { test } from 'node:test';
import assert from 'node:assert/strict';
import { initializeApp, deleteApp } from 'firebase/app';
import { getAuth, connectAuthEmulator, signInWithCustomToken } from 'firebase/auth';
import { getDatabase, connectDatabaseEmulator, ref, set, get, onValue, runTransaction, goOffline } from 'firebase/database';
import { initializeApp as adminApp, deleteApp as deleteAdminApp } from 'firebase-admin/app';
import { getAuth as adminAuth } from 'firebase-admin/auth';

test('two authenticated operators see live changes, concurrent execution retries retain both amounts, and rules reject outsiders and laboratory impersonation', async () => {
  assert(process.env.FIREBASE_AUTH_EMULATOR_HOST && process.env.FIREBASE_DATABASE_EMULATOR_HOST, 'Run only through npm run test:emulators');
  const admin = adminApp({ projectId: 'demo-milkyway' }, 'database-rules-smoke');
  const apps = [], databases = [];
  const [host, port] = process.env.FIREBASE_DATABASE_EMULATOR_HOST.split(':');
  assert(['127.0.0.1', 'localhost'].includes(host));
  async function client(uid, account, plant = 'default', role = 'operator') {
    const app = initializeApp({ projectId: 'demo-milkyway', apiKey: 'demo-key', databaseURL: 'https://demo-milkyway-default-rtdb.firebaseio.com' }, uid);
    apps.push(app);
    const auth = getAuth(app);
    connectAuthEmulator(auth, `http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}`, { disableWarnings: true });
    const token = await adminAuth(admin).createCustomToken(uid, { account, plant, role });
    await signInWithCustomToken(auth, token);
    const database = getDatabase(app); databases.push(database); connectDatabaseEmulator(database, host, Number(port));
    return { uid, account, database };
  }
  const actor = user => ({ uid: user.uid, account: user.account });
  const domain = (user, name = 'production') => ref(user.database, `plants/default/domains/${name}`);
  const orderKey = Buffer.from('order').toString('base64url');
  try {
    const first = await client('first', '01'), second = await client('second', '02');
    await set(domain(first), { schema: 1, revision: 1, updatedAt: Date.now(), actor: actor(first), tables: {
      production_queue: { [orderKey]: { id: 'order', produced_amount: '0', planned_amount: '1000' } }
    } });
    const seen = new Promise((resolve, reject) => {
      const timeout = setTimeout(() => { unsubscribe(); reject(new Error('Live update did not arrive')); }, 10000);
      const unsubscribe = onValue(domain(second), snapshot => {
        if (snapshot.child(`tables/production_queue/${orderKey}/produced_amount`).val() === '750') { clearTimeout(timeout); unsubscribe(); resolve(); }
      }, reject);
    });
    const produce = (user, id, amount) => runTransaction(domain(user), value => {
      if (!value) return; // Wait for initial shared snapshot before using an existing order.
      value.tables.production_completions ??= {};
      const key = Buffer.from(id).toString('base64url');
      if (!value.tables.production_completions[key]) {
        const order = value.tables.production_queue[orderKey];
        order.produced_amount = String(Number(order.produced_amount) + amount);
        value.tables.production_completions[key] = { id, entry_id: 'order', amount: String(amount) };
      }
      value.actor = actor(user); value.updatedAt = Date.now(); value.revision++;
      return value;
    }, { applyLocally: false });
    await Promise.all([produce(first, 'receipt-first', 400), produce(second, 'receipt-second', 350)]);
    await produce(first, 'receipt-first', 400);
    await seen;
    const final = (await get(domain(second))).val();
    assert.equal(final.tables.production_queue[orderKey].produced_amount, '750');
    assert.equal(Object.keys(final.tables.production_completions).length, 2);
    const outsider = await client('outsider', '03', 'other');
    await assert.rejects(get(domain(outsider)));
    const tankKey = Buffer.from('LBT 1').toString('base64url');
    const forged = { schema: 1, revision: 1, updatedAt: Date.now(), actor: actor(first), tables: {
      tank_states: { [tankKey]: { tank_id: 'LBT 1', laboratory_at: 100, brix: '12' } }
    } };
    await assert.rejects(set(domain(first, 'inventory'), forged));
    const lab = await client('lab', '10', 'default', 'laboratory');
    await set(domain(lab, 'inventory'), { ...forged, actor: actor(lab) });
    await assert.rejects(set(domain(first, 'inventory'), { ...forged, actor: actor(first), tables: {
      tank_states: { [tankKey]: { tank_id: 'LBT 1', laboratory_at: 100, brix: '13' } }
    } }));
    await set(domain(first, 'inventory'), { ...forged, actor: actor(first) }); // Preserving an existing measurement is permitted.
  } finally {
    databases.forEach(goOffline); await Promise.all(apps.map(deleteApp)); await deleteAdminApp(admin);
  }
});
