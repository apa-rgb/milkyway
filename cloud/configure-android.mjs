import { readFile, writeFile } from 'node:fs/promises';
import { parseArgs } from 'node:util';
import { pathToFileURL } from 'node:url';

export async function configureAndroid(jsonPath, databaseUrl, plant = 'default', output = '../firebase.properties') {
  const json = JSON.parse(await readFile(jsonPath, 'utf8'));
  const client = json.client?.find(x => x.client_info?.android_client_info?.package_name === 'pl.apargb.milkyway');
  if (!client) throw new Error('Configuration must belong to Android package pl.apargb.milkyway');
  const project = json.project_info?.project_id;
  const apiKey = client.api_key?.[0]?.current_key;
  const appId = client.client_info?.mobilesdk_app_id;
  const url = new URL(databaseUrl ?? json.project_info?.firebase_url);
  if (!project || !apiKey || !appId || url.protocol !== 'https:' || !/\.(firebaseio\.com|firebasedatabase\.app)$/.test(url.hostname) || !/^[A-Za-z0-9_-]{1,64}$/.test(plant)) {
    throw new Error('Provide project, Android configuration, Firebase Realtime Database HTTPS URL and valid plant ID');
  }
  if ([project, apiKey, appId].some(x => /[\r\n\\]/.test(x))) throw new Error('Invalid Firebase configuration');
  await writeFile(output, `projectId=${project}\napiKey=${apiKey}\napplicationId=${appId}\ndatabaseUrl=${url.origin}\nplantId=${plant}\n`, { flag: 'wx' });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const { values } = parseArgs({ options: { json: { type: 'string', default: '../app/google-services.json' },
    'database-url': { type: 'string' }, plant: { type: 'string', default: 'default' }, output: { type: 'string', default: '../firebase.properties' } } });
  await configureAndroid(values.json, values['database-url'], values.plant, values.output);
  console.log('Android Firebase configuration written. Rebuild the APK to activate login.');
}
