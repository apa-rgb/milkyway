import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { accountEmail, accountNumbers, provisionAccounts } from './provision-accounts.mjs';
import { configureAndroid } from './configure-android.mjs';

function fakeAuth() {
  const users = new Map();
  return { users,
    async getUser(uid) { if (!users.has(uid)) throw Object.assign(new Error('Not found'), { code: 'auth/user-not-found' }); return users.get(uid); },
    async createUser(user) { assert(!users.has(user.uid)); users.set(user.uid, { ...user }); return user; },
    async setCustomUserClaims(uid, claims) { users.get(uid).customClaims = claims; }
  };
}

test('creates exactly ten numbered operator identities with distinct passwords, and keeps credentials on rerun', async () => {
  const auth = fakeAuth(); const created = [];
  const first = await provisionAccounts(auth, 'demo-milkyway', 'default', async row => created.push(row));
  assert.equal(first.filter(x => x.created).length, 10);
  assert.deepEqual(created.map(x => x.number), accountNumbers);
  assert.equal(new Set(created.map(x => x.password)).size, 10);
  for (const [uid, user] of auth.users) {
    assert(user.password.length >= 24);
    assert.equal(user.email, accountEmail(user.customClaims.account, 'demo-milkyway'));
    assert.equal(user.customClaims.role, 'operator'); assert.equal(user.customClaims.plant, 'default');
    assert.equal(uid, `milkyway-operator-${user.customClaims.account}`);
  }
  const before = JSON.stringify([...auth.users]);
  const second = await provisionAccounts(auth, 'demo-milkyway', 'default', () => assert.fail('Must not regenerate passwords'));
  assert.equal(second.filter(x => x.created).length, 0);
  assert.equal(JSON.stringify([...auth.users]), before);
});

test('refuses to overwrite an existing account for another plant or role', async () => {
  const auth = fakeAuth();
  const user = { uid: 'milkyway-operator-01', email: accountEmail('01', 'demo-milkyway'), customClaims: { account: '01', plant: 'other', role: 'laboratory' } };
  auth.users.set(user.uid, user);
  await assert.rejects(provisionAccounts(auth, 'demo-milkyway', 'default', () => assert.fail()), /different settings/);
  assert.deepEqual(auth.users.get(user.uid), user); assert.equal(auth.users.size, 1);
});

test('rejects extra account numbers and invalid project or plant identifiers before creation', async () => {
  for (const value of ['00', '11', '1', 'admin']) assert.throws(() => accountEmail(value, 'demo-milkyway'));
  assert.throws(() => accountEmail('01', '../project'));
  const auth = fakeAuth();
  await assert.rejects(provisionAccounts(auth, 'demo-milkyway', '../plant', () => assert.fail()));
  assert.equal(auth.users.size, 0);
});

test('Android configuration selects the correct package and refuses wrong hosts, packages or overwriting files', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'milkyway-config-'));
  try {
    const config = { project_info: { project_id: 'demo-milkyway' }, client: [
      { client_info: { android_client_info: { package_name: 'other.app' } } },
      { client_info: { android_client_info: { package_name: 'pl.apargb.milkyway' }, mobilesdk_app_id: '1:123:android:abc' }, api_key: [{ current_key: 'public-test-key' }] }
    ] };
    const json = join(dir, 'google-services.json'), output = join(dir, 'firebase.properties');
    await writeFile(json, JSON.stringify(config));
    await configureAndroid(json, 'https://demo-milkyway-default-rtdb.europe-west1.firebasedatabase.app', 'default', output);
    assert.match(await readFile(output, 'utf8'), /projectId=demo-milkyway\napiKey=public-test-key/);
    await assert.rejects(configureAndroid(json, 'https://demo-milkyway-default-rtdb.europe-west1.firebasedatabase.app', 'default', output), /EEXIST/);
    for (const url of ['http://demo-milkyway.firebaseio.com', 'https://firebaseio.com.evil.example', 'https://example.com']) {
      await assert.rejects(configureAndroid(json, url, 'default', join(dir, 'bad.properties')));
    }
    config.client = config.client.slice(0, 1); await writeFile(json, JSON.stringify(config));
    await assert.rejects(configureAndroid(json, 'https://demo-milkyway.firebaseio.com', 'default', join(dir, 'bad.properties')), /package/);
  } finally { await rm(dir, { recursive: true, force: true }); }
});
