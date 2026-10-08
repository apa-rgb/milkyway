import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { initializeApp, deleteApp } from 'firebase/app';
import { getAuth, connectAuthEmulator, signInWithEmailAndPassword, signOut } from 'firebase/auth';
import { runProvisioning } from './provision-accounts.mjs';

test('Firebase Auth provisions all ten operators, accepts each password, rejects a wrong one and keeps credentials on rerun', async () => {
  const host = process.env.FIREBASE_AUTH_EMULATOR_HOST;
  assert(host && /^(127\.0\.0\.1|localhost):\d+$/.test(host), 'Run through npm run test:auth; never use real Firebase');
  await fetch(`http://${host}/emulator/v1/projects/demo-milkyway/accounts`, { method: 'DELETE' });
  const dir = await mkdtemp(join(tmpdir(), 'milkyway-auth-'));
  const app = initializeApp({ projectId: 'demo-milkyway', apiKey: 'demo-key' }, 'auth-smoke');
  const auth = getAuth(app); connectAuthEmulator(auth, `http://${host}`, { disableWarnings: true });
  try {
    const output = join(dir, 'accounts.csv');
    assert.equal((await runProvisioning('demo-milkyway', 'default', output)).filter(x => x.created).length, 10);
    assert.equal((await stat(output)).mode & 0o777, 0o600);
    const rows = (await readFile(output, 'utf8')).trim().split('\n').slice(1).map(x => x.split(','));
    assert.equal(rows.length, 10);
    for (const [number, email, password] of rows) {
      const signed = await signInWithEmailAndPassword(auth, email, password);
      const token = await signed.user.getIdTokenResult();
      assert.equal(token.claims.account, number); assert.equal(token.claims.plant, 'default'); assert.equal(token.claims.role, 'operator');
      await signOut(auth);
    }
    await assert.rejects(signInWithEmailAndPassword(auth, rows[0][1], 'incorrect-password'));
    await assert.rejects(runProvisioning('demo-milkyway', 'default', output), /EEXIST/);
    assert.equal((await runProvisioning('demo-milkyway', 'default', join(dir, 'rerun.csv'))).filter(x => x.created).length, 0);
    await signInWithEmailAndPassword(auth, rows[0][1], rows[0][2]); await signOut(auth);
  } finally { await deleteApp(app); await rm(dir, { recursive: true, force: true }); }
});
