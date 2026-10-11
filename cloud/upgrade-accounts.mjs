import { initializeApp, applicationDefault, deleteApp } from 'firebase-admin/app';
import { getAuth } from 'firebase-admin/auth';
import { randomInt } from 'node:crypto';
import { open, readFile, stat } from 'node:fs/promises';
import { parseArgs } from 'node:util';
import { pathToFileURL } from 'node:url';

export const loginNumbers = Array.from({ length: 20 }, (_, i) => String(i + 1).padStart(2, '0'));
export function initialPassword() {
  const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789';
  let value;
  do { value = Array.from({ length: 8 }, () => alphabet[randomInt(alphabet.length)]).join(''); }
  while (!/[A-Za-z]/.test(value) || !/[0-9]/.test(value));
  return value;
}
export async function credentialsFile(project, output) {
  if (!/^[a-z][a-z0-9-]{4,62}$/.test(project)) throw new Error('Nieprawidłowy projekt.');
  let file;
  try { file = await open(output, 'wx', 0o600); }
  catch (error) { if (error.code !== 'EEXIST') throw error; }
  if (file) {
    try {
      // Journal all passwords BEFORE changing Auth, allowing safe recovery after interruption.
      await file.write('login,email,haslo\n' + loginNumbers.map(n => `oper${n},konto${n}@${project}.accounts.invalid,${initialPassword()}\n`).join(''));
      await file.sync();
    } finally { await file.close(); }
  }
  if ((await stat(output)).mode & 0o077) throw new Error('Plik haseł musi mieć uprawnienia 600 (chmod 600).');
  const lines = (await readFile(output, 'utf8')).trim().split('\n');
  if (lines.shift() !== 'login,email,haslo' || lines.length !== 20) throw new Error('Nieprawidłowy plik haseł; nie zmieniono kont.');
  return lines.map((line, i) => {
    const [login, email, password, extra] = line.split(','); const number = loginNumbers[i];
    if (login !== `oper${number}` || email !== `konto${number}@${project}.accounts.invalid` || !/^[A-Za-z0-9]{8}$/.test(password) || extra !== undefined) throw new Error('Plik haseł dotyczy innego projektu lub jest niepełny.');
    return { number, login, email, password };
  });
}
export async function upgradeAccounts(auth, plant, rows) {
  if (!/^[A-Za-z0-9_-]{1,64}$/.test(plant) || rows.length !== 20 || rows.some((r, i) => r.number !== loginNumbers[i])) throw new Error('Nieprawidłowe konta lub zakład.');
  // Check all existing identities before changing any of them.
  const existing = [];
  for (const row of rows) {
    let user;
    try { user = await auth.getUser(`milkyway-operator-${row.number}`); }
    catch (error) { if (error.code !== 'auth/user-not-found') throw error; }
    const c = user?.customClaims;
    const interruptedCreation = user?.email === row.email && user?.displayName === `Operator ${row.login}` && !c?.account && !c?.plant && !c?.role;
    if (user && !interruptedCreation && (c?.plant !== plant || c?.account !== row.number || c?.role !== 'operator' || (c.credentialVersion !== 1 && user.email !== row.email))) throw new Error(`Konto ${row.login} ma inne uprawnienia. Nie zmieniono kont.`);
    existing.push(user);
  }
  const result = [];
  for (const [i, row] of rows.entries()) {
    const uid = `milkyway-operator-${row.number}`, user = existing[i];
    if (user?.customClaims?.credentialVersion === 1) { result.push({ login: row.login, changed: false }); continue; }
    if (user) {
      await auth.updateUser(uid, { password: row.password });
      await auth.revokeRefreshTokens(uid);
    } else await auth.createUser({ uid, email: row.email, password: row.password, emailVerified: false, displayName: `Operator ${row.login}` });
    await auth.setCustomUserClaims(uid, { ...user?.customClaims, account: row.number, plant, role: 'operator', credentialVersion: 1 });
    result.push({ login: row.login, changed: true });
  }
  return result;
}
export async function runUpgrade(project, plant, output) {
  const rows = await credentialsFile(project, output);
  const app = initializeApp({ credential: applicationDefault(), projectId: project }, `upgrade-${Date.now()}`);
  try { return await upgradeAccounts(getAuth(app), plant, rows); }
  finally { await deleteApp(app); }
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const { values } = parseArgs({ options: { project: { type: 'string' }, plant: { type: 'string', default: 'default' }, output: { type: 'string', default: 'hasla-20-kont.csv' } } });
    if (!values.project) throw new Error('Podaj --project.');
    const result = await runUpgrade(values.project, values.plant, values.output);
    console.log(`Przygotowano ${result.filter(r => r.changed).length} kont. Pozostałe konta były już przygotowane; ich haseł nie zmieniono.`);
    console.log(`Początkowe hasła: ${values.output}. Zachowaj plik prywatnie. Po zmianie hasła przez użytkownika wpis w pliku nie jest aktualizowany.`);
  } catch { console.error('Nie udało się przygotować kont. Sprawdź projekt, logowanie administracyjne i plik haseł. Zachowaj plik, aby bezpiecznie ponowić tę samą operację.'); process.exitCode = 1; }
}
