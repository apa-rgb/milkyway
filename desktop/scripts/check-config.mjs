import { readFile } from 'node:fs/promises';
import { validateConfig } from '../src/client.mjs';
validateConfig(JSON.parse(await readFile(new URL('../config/firebase.json', import.meta.url), 'utf8')));
console.log('Konfiguracja Firebase poprawna.');
