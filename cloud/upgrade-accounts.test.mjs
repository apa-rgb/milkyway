import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm, readFile, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { credentialsFile, upgradeAccounts } from './upgrade-accounts.mjs';
import { updatePasswordPolicy } from './configure-password-policy.mjs';

test('password policy backs up only the policy before changing Firebase; failed backup cannot modify project settings', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'milkyway-policy-'));
  try {
    const file = join(directory, 'policy.json'); let changed = 0;
    const manager = {
      getProjectConfig: async () => ({ passwordPolicyConfig: { enforcementState: 'OFF' }, unrelated: 'must-not-be-copied' }),
      updateProjectConfig: async value => {
        changed++; assert.deepEqual(Object.keys(value), ['passwordPolicyConfig']);
        assert.equal(JSON.parse(await readFile(file, 'utf8')).passwordPolicyConfig.enforcementState, 'OFF');
        assert.equal(value.passwordPolicyConfig.constraints.minLength, 8); assert.equal(value.passwordPolicyConfig.constraints.maxLength, 8);
        return value;
      }
    };
    await updatePasswordPolicy(manager, 'demo-milkyway', file);
    assert.equal((await stat(file)).mode & 0o777, 0o600);
    assert(!(await readFile(file, 'utf8')).includes('unrelated'));
    await assert.rejects(updatePasswordPolicy(manager, 'demo-milkyway', file), /EEXIST/); assert.equal(changed, 1);
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test('20 six-character logins and eight-character passwords are journalled privately; migration preserves UIDs and reruns do not reset chosen passwords', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'milkyway-upgrade-'));
  try {
    const file = join(directory, 'passwords.csv'); const rows = await credentialsFile('demo-milkyway', file);
    assert.equal((await stat(file)).mode & 0o777, 0o600);
    assert.equal(new Set(rows.map(r => r.password)).size, 20);
    assert(rows.every(r => r.login.length === 6 && r.password.length === 8));
    const users = new Map(rows.slice(0, 10).map(row => [`milkyway-operator-${row.number}`, { uid: `milkyway-operator-${row.number}`, email: row.email, password: 'old-password', customClaims: { account: row.number, plant: 'default', role: 'operator' } }]));
    let mutations = 0;
    const auth = {
      getUser: async uid => { if (!users.has(uid)) throw Object.assign(new Error(), { code: 'auth/user-not-found' }); return structuredClone(users.get(uid)); },
      createUser: async user => { mutations++; users.set(user.uid, structuredClone(user)); },
      updateUser: async (uid, data) => { mutations++; Object.assign(users.get(uid), data); },
      revokeRefreshTokens: async () => {},
      setCustomUserClaims: async (uid, claims) => { users.get(uid).customClaims = claims; }
    };
    const result = await upgradeAccounts(auth, 'default', rows);
    assert.equal(result.filter(r => r.changed).length, 20); assert.equal(users.size, 20);
    for (const row of rows) { const user = users.get(`milkyway-operator-${row.number}`); assert.equal(user.password, row.password); assert.equal(user.customClaims.account, row.number); assert.equal(user.customClaims.role, 'operator'); }
    users.get('milkyway-operator-01').password = 'Chosen88'; users.get('milkyway-operator-01').email = 'anna@example.com';
    const before = mutations; assert.equal((await upgradeAccounts(auth, 'default', await credentialsFile('demo-milkyway', file))).filter(r => r.changed).length, 0);
    assert.equal(mutations, before); assert.equal(users.get('milkyway-operator-01').password, 'Chosen88');
    assert((await readFile(file, 'utf8')).startsWith('login,email,haslo\noper01,'));
    await assert.rejects(credentialsFile('another-project', file));
    users.get('milkyway-operator-20').customClaims.role = 'laboratory';
    await assert.rejects(upgradeAccounts(auth, 'default', rows), /inne uprawnienia/); assert.equal(mutations, before);
  } finally { await rm(directory, { recursive: true, force: true }); }
});
