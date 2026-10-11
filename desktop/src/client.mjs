import { PROJECT, UserError, check, normalizeName, validateDocument, addOrder, orderList, warehouseList, productionTimeline } from './orders.mjs';

export function validateConfig(value, { emulator = false } = {}) {
  check(value && value.projectId === (emulator ? 'demo-milkyway' : PROJECT), 'Nieprawidłowy projekt Firebase.');
  check(typeof value.apiKey === 'string' && value.apiKey.length > 0 && value.apiKey.length < 200, 'Brak konfiguracji Firebase.');
  check(typeof value.plantId === 'string' && /^[A-Za-z0-9_-]+$/.test(value.plantId), 'Nieprawidłowy identyfikator zakładu.');
  let url; try { url = new URL(value.databaseUrl); } catch { throw new UserError('Nieprawidłowy adres bazy.'); }
  check(!url.username && !url.password && !url.search && !url.hash && ['/', ''].includes(url.pathname), 'Nieprawidłowy adres bazy.');
  check(emulator ? url.protocol === 'http:' && ['127.0.0.1', 'localhost'].includes(url.hostname) : url.protocol === 'https:' && /\.(firebaseio\.com|firebasedatabase\.app)$/.test(url.hostname), 'Nieprawidłowy adres bazy.');
  return { projectId: value.projectId, apiKey: value.apiKey, plantId: value.plantId, databaseUrl: url.origin };
}
export class CloudClient {
  constructor(config, { fetchImpl = fetch, now = () => Date.now(), authBase, tokenBase, emulator = false } = {}) {
    this.config = validateConfig(config, { emulator }); this.fetch = fetchImpl; this.now = now; this.emulator = emulator;
    this.authBase = authBase ?? 'https://identitytoolkit.googleapis.com/v1';
    this.tokenBase = tokenBase ?? 'https://securetoken.googleapis.com/v1';
    this.session = null; this.generation = 0; this.refreshing = null;
  }
  logout() { this.generation++; this.session = null; this.refreshing = null; }
  profile() { return this.session ? { account: this.session.account, name: this.session.name } : null; }
  async http(url, options = {}) {
    let response;
    try { response = await this.fetch(url, { ...options, signal: AbortSignal.timeout(20_000), redirect: 'error' }); }
    catch { throw new UserError('Brak połączenia. Zapis mógł dotrzeć do serwera — ponów to samo zamówienie, aby sprawdzić bez tworzenia duplikatu.'); }
    let data;
    try { data = await response.json(); } catch { throw new UserError('Nieprawidłowa odpowiedź serwera. Ponów zapis tego samego zamówienia.'); }
    return { response, data };
  }
  claims(token) {
    let value;
    try { value = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString('utf8')); }
    catch { throw new UserError('Nieprawidłowa sesja. Zaloguj się ponownie.'); }
    check(value.plant === this.config.plantId && /^(0[1-9]|10)$/.test(value.account) && ['operator', 'laboratory'].includes(value.role), 'To konto nie ma dostępu do zakładu.');
    return { account: value.account, role: value.role };
  }
  async login({ account, password, name }) {
    this.logout(); const generation = this.generation;
    check(/^(0[1-9]|10)$/.test(account) && typeof password === 'string' && password.length > 0 && password.length <= 4096, 'Wybierz konto i wpisz hasło.');
    const { response, data } = await this.http(`${this.authBase}/accounts:signInWithPassword?key=${encodeURIComponent(this.config.apiKey)}`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email: `konto${account}@${this.config.projectId}.accounts.invalid`, password, returnSecureToken: true })
    });
    check(generation === this.generation, 'Logowanie zostało anulowane.');
    if (!response.ok) {
      const message = data?.error?.message ?? '';
      throw new UserError(message.startsWith('TOO_MANY_ATTEMPTS') ? 'Za dużo prób logowania. Spróbuj później.' : 'Nie udało się zalogować. Sprawdź konto i hasło.');
    }
    const claims = this.claims(data.idToken);
    check(claims.account === account && typeof data.localId === 'string' && data.localId.length > 0 && typeof data.refreshToken === 'string', 'Nieprawidłowa sesja.');
    this.session = { uid: data.localId, ...claims, token: data.idToken, refresh: data.refreshToken, expires: this.now() + Number(data.expiresIn ?? 3600) * 1000, name: normalizeName(name) };
    return this.profile();
  }
  async token() {
    check(this.session, 'Zaloguj się ponownie.');
    if (this.session.expires - this.now() > 120_000) return this.session.token;
    if (!this.refreshing) {
      const generation = this.generation, session = this.session;
      this.refreshing = (async () => {
        const { response, data } = await this.http(`${this.tokenBase}/token?key=${encodeURIComponent(this.config.apiKey)}`, {
          method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ grant_type: 'refresh_token', refresh_token: session.refresh }).toString()
        });
        check(generation === this.generation && this.session === session, 'Sesja została zakończona.');
        check(response.ok, 'Sesja wygasła. Zaloguj się ponownie.');
        const claims = this.claims(data.id_token);
        check(claims.account === session.account && data.user_id === session.uid, 'Sesja konta została zmieniona.');
        Object.assign(session, { token: data.id_token, refresh: data.refresh_token, expires: this.now() + Number(data.expires_in ?? 3600) * 1000 });
      })();
    }
    const refresh = this.refreshing;
    try { await refresh; } finally { if (this.refreshing === refresh) this.refreshing = null; }
    check(this.session, 'Zaloguj się ponownie.'); return this.session.token;
  }
  async database(method = 'GET', body, etag) {
    const generation = this.generation;
    const token = await this.token(); check(generation === this.generation, 'Sesja została zakończona.');
    const url = new URL(`${this.config.databaseUrl}/plants/${encodeURIComponent(this.config.plantId)}/domains/production.json`);
    if (this.emulator) url.searchParams.set('ns', `${this.config.projectId}-default-rtdb`);
    url.searchParams.set('auth', token);
    const headers = method === 'GET' ? { 'X-Firebase-ETag': 'true' } : { 'Content-Type': 'application/json', 'if-match': etag };
    const result = await this.http(url, { method, headers, ...(body ? { body: JSON.stringify(body) } : {}) });
    check(generation === this.generation && this.session, 'Sesja została zakończona.');
    if (result.response.status === 412) return result;
    check(result.response.ok, result.response.status === 401 || result.response.status === 403 ? 'Brak dostępu do danych. Sprawdź konto i aktualizację reguł Firebase dla numerów zamówień (schemat 3).' : 'Nie udało się zapisać danych. Ponów to samo zamówienie.');
    validateDocument(result.data); return result;
  }
  async view(day, queueDay) {
    const { data } = await this.database();
    return { orders: orderList(data), warehouse: warehouseList(data, day), timeline: productionTimeline(data, queueDay), fetchedAt: this.now() };
  }
  async submit(input) {
    check(this.session, 'Zaloguj się ponownie.');
    const actor = { uid: this.session.uid, account: this.session.account }, author = this.session.name, now = this.now(), generation = this.generation;
    for (let attempt = 0; attempt < 8; attempt++) {
      const { data, response } = await this.database();
      check(generation === this.generation, 'Sesja została zakończona.');
      const result = addOrder(data, input, actor, author, now);
      const entry = result.document.tables.production_queue[Buffer.from(result.id).toString('base64url')];
      if (result.existing) return { id: result.id, orderNumber: entry.order_number, existing: true };
      const etag = response.headers.get('etag'); check(etag, 'Nie można potwierdzić wersji bazy. Zamówienie nie zostało wysłane.');
      const saved = await this.database('PUT', result.document, etag);
      if (saved.response.status === 412) continue;
      return { id: result.id, orderNumber: entry.order_number, existing: false };
    }
    throw new UserError('W bazie trwa wiele zmian. Ponów to samo zamówienie — numer nie zostanie zdublowany.');
  }
}
