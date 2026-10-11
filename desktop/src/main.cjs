const { app, BrowserWindow, ipcMain, net } = require('electron');
const path = require('node:path');
const fs = require('node:fs');
let client, window, sending = false;
app.setName('Milkyway — Zamówienia');
if (!app.isPackaged && process.env.MILKYWAY_E2E === '1' && process.env.MILKYWAY_E2E_USER_DATA) {
  app.setPath('userData', process.env.MILKYWAY_E2E_USER_DATA);
}
const single = app.requestSingleInstanceLock();
if (!single) app.quit();
else app.on('second-instance', () => { if (window) { if (window.isMinimized()) window.restore(); window.focus(); } });
app.whenReady().then(async () => {
  const { CloudClient, validateConfig } = await import('./client.mjs');
  let configurationError;
  const test = !app.isPackaged && process.env.MILKYWAY_E2E === '1';
  try {
    const filename = app.isPackaged ? path.join(process.resourcesPath, 'firebase.json') : path.join(__dirname, '../config/firebase.json');
    const config = test ? { projectId: 'demo-milkyway', apiKey: 'demo-key', plantId: 'default', databaseUrl: 'http://127.0.0.1:9000' } : JSON.parse(fs.readFileSync(filename, 'utf8'));
    validateConfig(config, { emulator: test });
    client = new CloudClient(config, { fetchImpl: (url, options) => net.fetch(String(url), options), ...(test ? { emulator: true, authBase: 'http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1', tokenBase: 'http://127.0.0.1:9099/securetoken.googleapis.com/v1' } : {}) });
  } catch { configurationError = 'Brak poprawnej konfiguracji połączenia. Przygotuj plik Firebase zgodnie z instrukcją modułu.'; }
  window = new BrowserWindow({ width: 1260, height: 860, minWidth: 940, minHeight: 660, backgroundColor: '#f4f6fb',
    icon: path.join(__dirname, '../assets/cow.png'), title: 'Milkyway — Zamówienia',
    webPreferences: { preload: path.join(__dirname, 'preload.cjs'), nodeIntegration: false, contextIsolation: true, sandbox: true, webSecurity: true } });
  window.removeMenu(); window.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  window.webContents.on('will-navigate', event => event.preventDefault());
  window.webContents.on('will-attach-webview', event => event.preventDefault());
  ipcMain.handle('milkyway', async (event, command, input) => {
    if (event.sender !== window.webContents || event.senderFrame !== window.webContents.mainFrame) return { ok: false, error: 'Brak dostępu.' };
    try {
      if (command === 'config') return { ok: true, value: { configured: !configurationError, version: app.getVersion(), profile: client?.profile(), error: configurationError } };
      if (!client) throw new Error(configurationError);
      switch (command) {
        case 'login': return { ok: true, value: await client.login(input) };
        case 'logout': client.logout(); return { ok: true };
        case 'view': return { ok: true, value: await client.view(input?.day, input?.queueDay) };
        case 'submit':
          if (sending) throw new Error('Trwa wysyłanie poprzedniego zamówienia.');
          sending = true; try { return { ok: true, value: await client.submit(input) }; } finally { sending = false; }
        default: throw new Error('Nieprawidłowe polecenie.');
      }
    } catch (error) { return { ok: false, error: error.name === 'UserError' ? error.message : 'Operacja nie powiodła się. Spróbuj ponownie.' }; }
  });
  await window.loadFile(path.join(__dirname, '../renderer/index.html'));
});
app.on('window-all-closed', () => { client?.logout(); app.quit(); });
