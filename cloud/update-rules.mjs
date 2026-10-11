import { readFile, writeFile } from 'node:fs/promises';
import { parseArgs } from 'node:util';
import { GoogleAuth } from 'google-auth-library';
async function main() {
const { values } = parseArgs({ options: { target: { type: 'string' } } });
if (!values.target) throw new Error('Użyj: node update-rules.mjs --target database-target.json');
const target = JSON.parse(await readFile(values.target, 'utf8'));
const database = new URL(target.databaseUrl);
if (target.projectId !== 'milyway-41e9e' || database.protocol !== 'https:' || !database.hostname.startsWith('milyway-41e9e') || !database.hostname.endsWith('.firebasedatabase.app') || database.username || database.search || database.hash) throw new Error('Nieprawidłowy projekt lub adres bazy.');
const google = new GoogleAuth({ scopes: ['https://www.googleapis.com/auth/firebase.database', 'https://www.googleapis.com/auth/userinfo.email'] });
const client = await google.getClient();
const url = `${database.origin}/.settings/rules.json`;
const old = await client.request({ url, method: 'GET' });
const backup = `database-rules-backup-${Date.now()}.json`;
await writeFile(backup, JSON.stringify(old.data, null, 2) + '\n', { mode: 0o600 });
const rules = JSON.parse(await readFile(new URL('./database.rules.json', import.meta.url), 'utf8'));
await client.request({ url, method: 'PUT', data: rules });
console.log(`Reguły produkcji zaktualizowane do schematu 3. Kopia poprzednich reguł: ${backup}. Dane i konta nie zostały zmienione.`);

}
main().catch(error => {
  const status = error?.response?.status;
  console.error(`Nie udało się zaktualizować reguł${Number.isInteger(status) ? ` (HTTP ${status})` : ''}. Sprawdź plik celu, logowanie ADC i uprawnienia do Realtime Database. Dane i konta nie były zmieniane przez ten skrypt.`);
  process.exitCode = 1;
});
