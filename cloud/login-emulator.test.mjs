import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { runUpgrade } from './upgrade-accounts.mjs';
import { CloudClient } from '../desktop/src/client.mjs';

test('Real Firebase Auth: 20 operators, initial change, verified email, password reset and private profiles on the free plan', { timeout: 120000 }, async () => {
  assert.equal(process.env.FIREBASE_AUTH_EMULATOR_HOST, '127.0.0.1:9099');
  assert.equal(process.env.FIREBASE_DATABASE_EMULATOR_HOST, '127.0.0.1:9000');
  const directory = await mkdtemp(join(tmpdir(), 'milkyway-login-'));
  const config = { projectId: 'demo-milkyway', apiKey: 'demo-key', databaseUrl: 'http://127.0.0.1:9000', plantId: 'default' };
  const options = { emulator: true, authBase: 'http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1', tokenBase: 'http://127.0.0.1:9099/securetoken.googleapis.com/v1' };
  const client = new CloudClient(config, options);
  const codes = async () => (await (await fetch('http://127.0.0.1:9099/emulator/v1/projects/demo-milkyway/oobCodes')).json()).oobCodes;
  try {
    await fetch('http://127.0.0.1:9099/emulator/v1/projects/demo-milkyway/accounts', { method: 'DELETE' });
    const file = join(directory, 'accounts.csv'); assert.equal((await runUpgrade('demo-milkyway', 'default', file)).filter(r => r.changed).length, 20);
    const rows = (await readFile(file, 'utf8')).trim().split('\n').slice(1).map(line => line.split(','));
    for (let i = 0; i < 20; i++) {
      const profile = await client.login({ account: String(i + 1).padStart(2, '0'), password: rows[i][2], name: 'Test' });
      assert.equal(profile.requiresPasswordChange, true);
      assert.equal(client.session.uid, `milkyway-operator-${String(i + 1).padStart(2, '0')}`);
    }
    await client.login({ account: '01', password: rows[0][2], name: 'Anna' });
    await assert.rejects(client.view('2026-10-11', '2026-10-11'), /Najpierw zmień/);
    await assert.rejects(client.changePassword({ current: 'Wrong888', password: 'Chosen88', email: 'anna@example.com' }));
    const changed = await client.changePassword({ current: rows[0][2], password: 'Chosen88', email: 'anna@example.com' });
    assert.equal(changed.email, 'anna@example.com'); assert.equal(client.profile(), null);
    const verify = (await codes()).find(code => code.requestType === 'VERIFY_AND_CHANGE_EMAIL' && code.newEmail === 'anna@example.com');
    assert(verify, 'Verification must be sent before moving Auth to the real email');
    // The original address still works while verification is pending.
    assert.equal((await client.login({ account: '01', password: 'Chosen88', email: 'anna@example.com', name: 'Anna' })).requiresPasswordChange, false);
    const verifyResult = await client.authRequest('update', { oobCode: verify.oobCode }); assert.equal(verifyResult.response.ok, true);
    client.logout(); await assert.rejects(client.login({ account: '01', password: rows[0][2], email: 'anna@example.com' }));
    assert.equal((await client.login({ account: '01', password: 'Chosen88', email: 'anna@example.com' })).requiresPasswordChange, false);
    assert.equal(client.session.uid, 'milkyway-operator-01');
    const profileURL = 'http://127.0.0.1:9000/accountProfiles/milkyway-operator-01.json?ns=demo-milkyway-default-rtdb';
    assert.equal((await fetch(profileURL)).status, 401);
    const other = new CloudClient(config, options); await other.login({ account: '20', password: rows[19][2] });
    assert.equal((await fetch(profileURL + '&auth=' + encodeURIComponent(other.session.token))).status, 401);
    const stored = await client.privateProfile('GET'); assert.equal(stored.recoveryEmail, 'anna@example.com'); assert(!JSON.stringify(stored).includes('Chosen88'));
    assert.equal((await client.resetPassword('anna@example.com')).message, (await client.resetPassword('unknown@example.com')).message);
    const reset = (await codes()).find(code => code.requestType === 'PASSWORD_RESET' && code.email === 'anna@example.com'); assert(reset);
    assert.equal((await client.authRequest('resetPassword', { oobCode: reset.oobCode, newPassword: 'Reset888' })).response.ok, true);
    client.logout(); await assert.rejects(client.login({ account: '01', password: 'Chosen88', email: 'anna@example.com' }));
    assert.equal((await client.login({ account: '01', password: 'Reset888', email: 'anna@example.com' })).requiresPasswordChange, false);
    // Entering another operator's verified email must not switch the selected account silently.
    await assert.rejects(other.login({ account: '20', password: 'Reset888', email: 'anna@example.com' }), /Nieprawidłowa sesja/);
    assert.equal((await runUpgrade('demo-milkyway', 'default', file)).filter(r => r.changed).length, 0);
    await client.login({ account: '01', password: 'Reset888', email: 'anna@example.com' });
  } finally { client.logout(); await rm(directory, { recursive: true, force: true }); }
});
