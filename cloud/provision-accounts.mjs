import { initializeApp, applicationDefault, deleteApp } from 'firebase-admin/app';
import { getAuth } from 'firebase-admin/auth';
import { randomBytes } from 'node:crypto';
import { open } from 'node:fs/promises';
import { parseArgs } from 'node:util';
import { pathToFileURL } from 'node:url';

export const accountNumbers = Array.from({ length: 10 }, (_, i) => String(i + 1).padStart(2, '0'));
export function accountEmail(number, project) {
  if (!accountNumbers.includes(number) || !/^[a-z][a-z0-9-]{4,62}$/.test(project)) throw new Error('Invalid account or project');
  return `konto${number}@${project}.accounts.invalid`;
}

/** Idempotent creation. An existing account is never given a new password or role silently. */
export async function provisionAccounts(auth, project, plant, onCreated) {
  if (!/^[A-Za-z0-9_-]{1,64}$/.test(plant)) throw new Error('Invalid plant ID');
  const result = [];
  for (const number of accountNumbers) {
    const uid = `milkyway-operator-${number}`;
    const email = accountEmail(number, project);
    let existing;
    try { existing = await auth.getUser(uid); }
    catch (error) { if (error.code !== 'auth/user-not-found') throw error; }
    if (existing) {
      if (existing.email !== email || existing.customClaims?.plant !== plant || existing.customClaims?.account !== number || existing.customClaims?.role !== 'operator') {
        throw new Error(`Account ${number} already exists with different settings. No password or permissions were changed.`);
      }
      result.push({ number, created: false });
      continue;
    }
    const password = randomBytes(18).toString('base64url');
    await auth.createUser({ uid, email, password, displayName: `Konto ${number}`, emailVerified: true });
    // Save each created password immediately; a later failure must not lose earlier credentials.
    await onCreated({ number, email, password });
    await auth.setCustomUserClaims(uid, { account: number, plant, role: 'operator' });
    result.push({ number, created: true });
  }
  return result;
}

export async function runProvisioning(project, plant, output) {
  const app = initializeApp({ credential: applicationDefault(), projectId: project }, `accounts-${Date.now()}`);
  const file = await open(output, 'wx', 0o600); // Never overwrite the administrator's existing password list.
  try {
    await file.write('konto,email,haslo\n'); await file.sync();
    return await provisionAccounts(getAuth(app), project, plant, async ({ number, email, password }) => {
      await file.write(`${number},${email},${password}\n`); await file.sync();
    });
  } finally { await file.close(); await deleteApp(app); }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const { values } = parseArgs({ options: { project: { type: 'string' }, plant: { type: 'string', default: 'default' },
    output: { type: 'string', default: `accounts-${Date.now()}.csv` } } });
  if (!values.project) throw new Error('Pass --project YOUR_FIREBASE_PROJECT');
  const result = await runProvisioning(values.project, values.plant, values.output);
  console.log(`Created ${result.filter(x => x.created).length} accounts; ${result.filter(x => !x.created).length} already existed.`);
  console.log(`New passwords saved locally to ${values.output} (permissions 0600). Do not commit or post this file.`);
}
