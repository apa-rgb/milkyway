import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { mkdir, mkdtemp, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { _electron } from 'playwright';
import { sharedKey } from '../src/orders.mjs';
const directory = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const waitFor = async (page, predicate) => page.waitForFunction(predicate, null, { timeout: 15000 });
test('Desktop login, pending order, partial warehouse and live calendar timeline work through the isolated Electron bridge', { timeout: 90000 }, async () => {
  let document = null, revision = 0, writes = 0, privateProfile = null, loginPassword = 'local-test-password';
  const token = 'e30.' + Buffer.from(JSON.stringify({ plant: 'default', account: '01', role: 'operator', credentialVersion: 1 })).toString('base64url') + '.test';
  const auth = createServer(async (req, res) => {
    const chunks = []; for await (const chunk of req) chunks.push(chunk);
    const body = JSON.parse(Buffer.concat(chunks).toString());
    res.setHeader('Content-Type', 'application/json');
    if (req.url.includes('accounts:sendOobCode')) { assert(['PASSWORD_RESET', 'VERIFY_AND_CHANGE_EMAIL'].includes(body.requestType)); res.end('{}'); return; }
    if (req.url.includes('accounts:lookup')) { res.end(JSON.stringify({ users: [{ localId: 'operator01', email: 'konto01@demo-milkyway.accounts.invalid', emailVerified: false }] })); return; }
    if (req.url.includes('accounts:update')) { loginPassword = body.password; }
    else { assert(['konto01@demo-milkyway.accounts.invalid', 'anna@example.com'].includes(body.email)); assert.equal(body.password, loginPassword); }
    res.end(JSON.stringify({ localId: 'operator01', idToken: token, refreshToken: 'local-only-refresh', expiresIn: '3600' }));
  });
  const database = createServer(async (req, res) => {
    res.setHeader('Content-Type', 'application/json');
    if (req.url.startsWith('/accountProfiles/')) {
      if (req.method === 'PUT') { const chunks = []; for await (const chunk of req) chunks.push(chunk); privateProfile = JSON.parse(Buffer.concat(chunks).toString()); privateProfile.passwordChangedAt = Date.now(); }
      res.end(JSON.stringify(privateProfile)); return;
    }
    if (req.method === 'PUT') {
      const chunks = []; for await (const chunk of req) chunks.push(chunk);
      if (req.headers['if-match'] !== `"${revision}"`) { res.statusCode = 412; res.end(JSON.stringify(document)); return; }
      document = JSON.parse(Buffer.concat(chunks).toString()); revision++; writes++;
    }
    res.setHeader('ETag', `"${revision}"`); res.end(JSON.stringify(document));
  });
  let application;
  const userData = await mkdtemp(path.join(os.tmpdir(), 'milkyway-ui-'));
  try {
    auth.listen(9099, '127.0.0.1'); database.listen(9000, '127.0.0.1'); await Promise.all([once(auth, 'listening'), once(database, 'listening')]);
    application = await _electron.launch({ args: [directory, ...(process.platform === 'linux' ? ['--no-sandbox'] : [])], env: { ...process.env, MILKYWAY_E2E: '1', MILKYWAY_E2E_USER_DATA: userData } });
    const page = await application.firstWindow();
    await page.locator('#password').fill('local-test-password'); await page.locator('#name').fill('Anna');
    await mkdir(path.join(directory, 'test-results'), { recursive: true });
    await page.screenshot({ path: path.join(directory, 'test-results/login.png') });
    await page.locator('#reset-open').click(); await page.locator('#reset-email').fill('anna@example.com'); await page.locator('#reset-send').click();
    await waitFor(page, () => document.getElementById('reset-message').textContent.includes('Jeśli e-mail')); await page.locator('#reset-close').click();
    await page.locator('#login-button').click(); await waitFor(page, () => document.getElementById('password-dialog').open);
    assert.equal(await page.locator('#app-screen').isVisible(), false);
    await page.locator('#current-password').fill('local-test-password'); await page.locator('#new-password').fill('Chosen88');
    await page.locator('#repeat-password').fill('Wrong888'); await page.locator('#recovery-email').fill('anna@example.com'); await page.locator('#password-save').click();
    await waitFor(page, () => document.getElementById('password-error').textContent.includes('nie jest takie samo'));
    await page.locator('#repeat-password').fill('Chosen88'); await page.locator('#password-save').click();
    await waitFor(page, () => document.getElementById('login-message').textContent.includes('Hasło zmieniono'));
    assert.equal(privateProfile.recoveryEmail, 'anna@example.com'); assert(!JSON.stringify(privateProfile).includes('Chosen88'));
    assert.equal(await page.locator('#email').inputValue(), 'anna@example.com');
    await page.locator('#password').fill('Chosen88'); await page.locator('#login-button').click(); await waitFor(page, () => !document.getElementById('app-screen').hidden);
    assert.equal(await page.evaluate(() => typeof window.require), 'undefined');
    assert.equal(await page.evaluate(() => typeof window.process), 'undefined');
    await page.locator('#product').fill('Mix 60%'); await page.locator('#quantity').fill('1500,5'); await page.locator('#submit-order').click();
    await waitFor(page, () => document.getElementById('order-error').textContent.includes('Wybierz Masło'));
    await page.locator('[data-kind="MIX"]').click(); await page.locator('#note').fill('<img src=x onerror="window.injected=true"> pilna dostawa');
    await page.locator('#important').check(); await page.locator('#submit-order').click();
    await waitFor(page, () => document.getElementById('success').textContent.includes('#000001'));
    assert.equal(writes, 1); assert.equal(Object.values(document.tables.production_queue)[0].pending_order, 1);
    assert.equal(await page.locator('.order-number').first().textContent(), '#000001');
    assert.equal(await page.locator('.order-note img').count(), 0); assert.equal(await page.evaluate(() => window.injected), undefined);
    await page.locator('[data-line="POWDER"]').click(); await page.locator('#product').fill('Proszek mleczny'); await page.locator('#quantity').fill('300'); await page.locator('#submit-order').click();
    await waitFor(page, () => document.getElementById('success').textContent.includes('#000002')); assert.equal(writes, 2);
    await page.screenshot({ path: path.join(directory, 'test-results/orders.png'), fullPage: true });
    const warehouseDay = await page.locator('#warehouse-date').inputValue(), queueDay = await page.locator('#queue-date').inputValue();
    const rows = Object.values(document.tables.production_queue);
    Object.assign(rows[0], { pending_order: 0, planned_time: '10:30', plan_date: queueDay, produced_amount: '250' });
    Object.assign(rows[1], { pending_order: 0, planned_time: '14:00', plan_date: queueDay });
    document.tables.production_completions = { [sharedKey('receipt')]: { id: 'receipt', entry_id: rows[0].id, amount: '250', produced_on: warehouseDay, occurred_at: Date.now(), _rowid: 1 } }; revision++;
    await page.locator('#refresh').click(); await waitFor(page, () => document.getElementById('count-production').textContent === '2');
    await page.locator('[data-tab="warehouse"]').click(); await waitFor(page, () => !!document.querySelector('.warehouse-card.PARTIAL'));
    assert.match(await page.locator('.warehouse-card').textContent(), /#000001/); assert.match(await page.locator('.warehouse-detail').textContent(), /pozostało 1\s*250,5/);
    await page.screenshot({ path: path.join(directory, 'test-results/warehouse.png'), fullPage: true });
    await page.locator('[data-tab="timeline"]').click(); await waitFor(page, () => document.querySelectorAll('.timeline-card').length === 2);
    assert.match(await page.locator('.timeline-card').first().textContent(), /10:30 · #000001/);
    await page.locator('#timeline-grid').evaluate(node => { node.scrollTop = 300; });
    await page.screenshot({ path: path.join(directory, 'test-results/timeline.png'), fullPage: true });
    await page.locator('#queue-next').click(); await waitFor(page, () => document.querySelectorAll('.timeline-card').length === 0);
    await page.locator('#queue-previous').click(); await waitFor(page, () => document.querySelectorAll('.timeline-card').length === 2);
    await page.locator('#queue-line').selectOption('POWDER'); assert.equal(await page.locator('.timeline-card').count(), 1);
    await application.evaluate(({ BrowserWindow }) => { BrowserWindow.getAllWindows()[0].setSize(940, 660); });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true);
    await page.locator('#logout').click(); await waitFor(page, () => !document.getElementById('login-screen').hidden);
    assert.equal(await page.locator('#password').inputValue(), '');
    assert.equal(await page.evaluate(() => Object.values(localStorage).some(v => v.includes('local-test-password') || v.includes('Chosen88') || v.includes('local-only-refresh'))), false);
  } finally {
    await application?.close(); await Promise.all([new Promise(resolve => auth.close(resolve)), new Promise(resolve => database.close(resolve))]);
    await rm(userData, { recursive: true, force: true });
  }
});
