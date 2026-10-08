import { execFileSync } from 'node:child_process';
import { readFile, writeFile } from 'node:fs/promises';
import { parseArgs } from 'node:util';
import { GoogleAuth } from 'google-auth-library';
import { configureAndroid } from './configure-android.mjs';
import { runProvisioning } from './provision-accounts.mjs';

const { values } = parseArgs({ options: { project: { type: 'string' }, plant: { type: 'string', default: 'default' } } });
const project = values.project;
if (!project || !/^[a-z][a-z0-9-]{4,28}[a-z0-9]$/.test(project)) throw new Error('Pass --project a unique Google Cloud project ID (6–30 characters)');
if (!/^[A-Za-z0-9_-]{1,64}$/.test(values.plant)) throw new Error('Invalid plant ID');
const google = new GoogleAuth({ scopes: ['https://www.googleapis.com/auth/cloud-platform', 'https://www.googleapis.com/auth/firebase.database', 'https://www.googleapis.com/auth/userinfo.email'] });
// Verify configured Google credentials before creating any resource. Never launch an interactive login here.
await google.getAccessToken();
const firebase = args => {
  const text = execFileSync(process.execPath, ['./node_modules/firebase-tools/lib/bin/firebase.js', ...args,
    '--project', project, '--non-interactive', '--json'], { encoding: 'utf8', env: { ...process.env, CI: 'true' } });
  const response = JSON.parse(text);
  if (response.status !== 'success') throw new Error(`Firebase operation failed: ${args[0]}`);
  return response.result;
};
firebase(['projects:create', project, '--display-name', 'Milkyway']);
firebase(['apps:create', 'ANDROID', 'Milkyway', '--package-name', 'pl.apargb.milkyway']);
const apps = firebase(['apps:list', 'ANDROID']);
const android = (Array.isArray(apps) ? apps : apps.apps).find(x => x.packageName === 'pl.apargb.milkyway');
if (!android) throw new Error('Created Android app not found');
const sdk = firebase(['apps:sdkconfig', 'ANDROID', android.appId]);
const sdkText = typeof sdk === 'string' ? sdk : sdk.fileContents;
JSON.parse(sdkText); // Never write malformed configuration.
await writeFile('../app/google-services.json', sdkText, { flag: 'wx' });
const instance = `${project}-default-rtdb`;
const client = await google.getClient();
const createdDatabase = await client.request({ method: 'POST',
  url: `https://firebasedatabase.googleapis.com/v1beta/projects/${project}/locations/europe-west1/instances`,
  params: { databaseId: instance }, data: { type: 'DEFAULT_DATABASE' } });
const databaseUrl = createdDatabase.data.databaseUrl;
if (!databaseUrl) throw new Error('Database creation did not return a URL');
await client.request({ method: 'PATCH', url: `https://identitytoolkit.googleapis.com/admin/v2/projects/${project}/config`,
  params: { updateMask: 'signIn.email.enabled,signIn.email.passwordRequired' }, data: { signIn: { email: { enabled: true, passwordRequired: true } } } });
// Install access rules before distributing any operator account.
const rules = JSON.parse(await readFile('database.rules.json', 'utf8'));
await client.request({ method: 'PUT', url: `${databaseUrl}/.settings/rules.json`, data: rules });
await configureAndroid('../app/google-services.json', databaseUrl, values.plant);
const output = `accounts-${Date.now()}.csv`;
await runProvisioning(project, values.plant, output);
console.log(`Project ${project}, shared database and 10 operator accounts created. Passwords are in ${output}; rebuild Android APK.`);
