'use strict';
const $ = id => document.getElementById(id);
const api = window.milkyway;
const labels = { BUTTER: 'Masłownia', POWDER: 'Proszkownia', UHT: 'UHT' };
const statuses = { PENDING: 'Oczekuje', PRODUCTION: 'W produkcji', COMPLETED: 'Wyprodukowano' };
let profile, timer, viewGeneration = 0, refreshing = false, currentTab = 'orders', line = 'BUTTER', kind = '', requestID = newID(), latest = { orders: [], warehouse: { products: [], rejects: [] }, timeline: { entries: [] } };
function newID() { return `windows-order:${crypto.randomUUID()}`; }
function day(now = new Date()) {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Warsaw', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(now);
  const field = n => parts.find(p => p.type === n).value; return `${field('year')}-${field('month')}-${field('day')}`;
}
function currentDay() {
  const date = day(), hour = Number(new Intl.DateTimeFormat('en-GB', { timeZone: 'Europe/Warsaw', hour: '2-digit', hourCycle: 'h23' }).format(new Date()));
  if (hour >= 9) return date; return changeDay(date, -1);
}
function changeDay(date, offset) { const [y, m, d] = date.split('-').map(Number); return new Date(Date.UTC(y, m - 1, d + offset)).toISOString().slice(0, 10); }
function number(n) { return n == null ? 'Bez numeru' : `#${String(n).padStart(6, '0')}`; }
function amount(value) {
  const [whole = '0', fraction] = String(value ?? '0').split('.');
  return /^\d+$/.test(whole) ? new Intl.NumberFormat('pl-PL').format(BigInt(whole)) + (fraction ? `,${fraction}` : '') : '—';
}
function dateTime(ms) { return new Intl.DateTimeFormat('pl-PL', { timeZone: 'Europe/Warsaw', day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' }).format(new Date(ms)); }
function notice(id, message) { $(id).textContent = message || ''; $(id).hidden = !message; }
function empty(container, message) { const node = document.createElement('div'); node.className = 'empty'; node.textContent = message; container.replaceChildren(node); }
function busyForm(busy) { $('order-fields').disabled = busy; $('submit-order').textContent = busy ? 'Zapisywanie…' : 'Wyślij zamówienie →'; }
for (let n = 1; n <= 10; n++) { const option = document.createElement('option'); option.value = String(n).padStart(2, '0'); option.textContent = `Operator ${option.value}`; $('account').append(option); }
$('warehouse-date').value = currentDay(); $('queue-date').value = day();
function restoreProfile() { $('name').value = localStorage.getItem(`milkyway-name:${$('account').value}`) ?? ''; }
$('account').value = localStorage.getItem('milkyway-account') ?? '01'; restoreProfile(); $('account').addEventListener('change', restoreProfile);
api.config().then(result => { if (result.ok) { $('version').textContent = `Windows · v${result.value.version}`; if (!result.value.configured) { $('login-error').textContent = result.value.error; $('login-button').disabled = true; } } });
$('login-form').addEventListener('submit', async event => {
  event.preventDefault(); $('login-button').disabled = true; notice('login-error', '');
  const result = await api.login({ account: $('account').value, password: $('password').value, name: $('name').value });
  $('password').value = ''; $('login-button').disabled = false;
  if (!result.ok) { notice('login-error', result.error); return; }
  profile = result.value; localStorage.setItem('milkyway-account', profile.account); localStorage.setItem(`milkyway-name:${profile.account}`, profile.name);
  $('profile-name').textContent = profile.name || `Operator ${profile.account}`; $('profile-account').textContent = `Konto ${profile.account}`;
  $('login-screen').hidden = true; $('app-screen').hidden = false; restoreDraft();
  await refresh(); timer = setInterval(refresh, 5000);
});
$('logout').addEventListener('click', async () => {
  clearInterval(timer); viewGeneration++; await api.logout(); profile = null; refreshing = false; latest = { orders: [], warehouse: { products: [], rejects: [] }, timeline: { entries: [] } };
  $('app-screen').hidden = true; $('login-screen').hidden = false; $('orders-list').replaceChildren(); $('warehouse-list').replaceChildren(); $('timeline-grid').replaceChildren(); notice('global-error', ''); notice('success', '');
  $('login-button').disabled = false; $('order-form').reset(); requestID = newID(); selectLine('BUTTER'); selectKind('');
});
function selectLine(value) {
  line = value; document.querySelectorAll('[data-line]').forEach(b => { b.classList.toggle('selected', b.dataset.line === line); b.setAttribute('aria-pressed', String(b.dataset.line === line)); });
  $('butter-options').hidden = line !== 'BUTTER'; $('quantity-label').textContent = line === 'UHT' ? 'Zamówiona ilość [l]' : 'Zamówiona masa [kg]';
}
function selectKind(value) { kind = value; document.querySelectorAll('[data-kind]').forEach(b => { b.classList.toggle('selected', b.dataset.kind === kind); b.setAttribute('aria-pressed', String(b.dataset.kind === kind)); }); }
document.querySelectorAll('[data-line]').forEach(b => b.addEventListener('click', () => { selectLine(b.dataset.line); persistDraft(); }));
document.querySelectorAll('[data-kind]').forEach(b => b.addEventListener('click', () => { selectKind(b.dataset.kind); persistDraft(); }));
function input() { return { id: requestID, line, butterKind: kind, title: $('product').value, description: $('description').value, quantity: $('quantity').value, note: $('note').value, important: $('important').checked }; }
function persistDraft() { if (profile) localStorage.setItem(`milkyway-draft:${profile.account}`, JSON.stringify(input())); }
function restoreDraft() {
  let draft; try { draft = JSON.parse(localStorage.getItem(`milkyway-draft:${profile.account}`)); } catch { /* Ignore corrupted local draft. */ }
  if (!draft || typeof draft.id !== 'string' || !/^windows-order:[a-f0-9-]{36}$/.test(draft.id)) { requestID = newID(); return; }
  requestID = draft.id; selectLine(Object.hasOwn(labels, draft.line) ? draft.line : 'BUTTER'); selectKind(draft.butterKind ?? '');
  for (const [id, key] of [['product', 'title'], ['quantity', 'quantity'], ['description', 'description'], ['note', 'note']]) $(id).value = typeof draft[key] === 'string' ? draft[key] : '';
  $('important').checked = draft.important === true;
}
$('order-form').addEventListener('input', persistDraft);
$('order-form').addEventListener('submit', async event => {
  event.preventDefault(); notice('order-error', ''); notice('success', '');
  if (line === 'BUTTER' && !kind) { notice('order-error', 'Wybierz Masło albo Mix.'); return; }
  const generation = viewGeneration; const order = input(); persistDraft(); busyForm(true);
  const result = await api.submit(order); busyForm(false); if (generation !== viewGeneration || !profile) return;
  if (!result.ok) { notice('order-error', result.error); return; }
  localStorage.removeItem(`milkyway-draft:${profile.account}`); requestID = newID(); $('order-form').reset(); selectKind('');
  notice('success', `Zamówienie ${number(result.value.orderNumber)} zapisane. ${labels[line]} — dostępne w oczekujących w aplikacji.`);
  await refresh();
});
async function refresh() {
  if (!profile || refreshing) return; refreshing = true; const generation = viewGeneration, requestedDay = $('warehouse-date').value, requestedQueueDay = $('queue-date').value;
  const result = await api.view(requestedDay, requestedQueueDay); if (generation !== viewGeneration || !profile) { refreshing = false; return; } refreshing = false;
  if (!result.ok) { notice('global-error', result.error); $('sync-label').textContent = 'Nie udało się odświeżyć'; return; }
  // A calendar change during the request must never show the previous day's rows under the new date.
  if (requestedDay !== $('warehouse-date').value || requestedQueueDay !== $('queue-date').value) { await refresh(); return; }
  latest = result.value; notice('global-error', ''); $('sync-label').textContent = `Aktualizacja: ${dateTime(latest.fetchedAt)}`;
  renderOrders(); renderWarehouse(); renderTimeline();
}
function renderOrders() {
  for (const [id, status] of [['count-pending', 'PENDING'], ['count-production', 'PRODUCTION'], ['count-completed', 'COMPLETED']]) $(id).textContent = latest.orders.filter(o => o.status === status).length;
  const search = $('search-orders').value.trim().toLocaleLowerCase('pl-PL'), selected = $('filter-line').value;
  const rows = latest.orders.filter(o => (!selected || o.line === selected) && (!search || `${number(o.orderNumber)} ${o.title}`.toLocaleLowerCase('pl-PL').includes(search)));
  $('orders-total').textContent = rows.length;
  if (!rows.length) { empty($('orders-list'), 'Tutaj zobaczysz złożone zamówienia i postęp ich realizacji.'); return; }
  const fragment = document.createDocumentFragment();
  for (const order of rows) {
    const node = $('order-card').content.firstElementChild.cloneNode(true); node.classList.add(order.status); node.dataset.orderId = order.id;
    node.querySelector('.order-number').textContent = number(order.orderNumber); node.querySelector('.order-status').textContent = statuses[order.status]; node.querySelector('h3').textContent = order.title;
    node.querySelector('.order-meta').textContent = `${labels[order.line]}${order.butterKind ? ` · ${order.butterKind}` : ''} · ${dateTime(order.createdAt)}${order.status === 'PRODUCTION' ? ` · ${order.date} ${order.time}` : ''}`;
    node.querySelector('.order-progress').textContent = `Zamówiono ${amount(order.quantity)} ${order.unit} · wyprodukowano ${amount(order.produced)} ${order.unit}${order.status !== 'COMPLETED' ? ` · pozostało ${amount(order.remaining)} ${order.unit}` : ''}`;
    const note = node.querySelector('.order-note'); note.textContent = order.note; note.hidden = !order.note; note.classList.toggle('important', order.important); fragment.append(node);
  }
  $('orders-list').replaceChildren(fragment);
}
function renderWarehouse() {
  const data = latest.warehouse, fragment = document.createDocumentFragment();
  if (!data.products.length && !data.rejects.length) { empty($('warehouse-list'), 'Brak wpisów magazynu z tego dnia.'); return; }
  for (const product of data.products) {
    const node = $('warehouse-card').content.firstElementChild.cloneNode(true); node.classList.add(product.status);
    node.querySelector('.order-number').textContent = number(product.orderNumber); node.querySelector('h3').textContent = product.title;
    node.querySelector('.warehouse-detail').textContent = `${labels[product.line]} · zamówiono ${amount(product.ordered)} ${product.unit}${product.status === 'EXCESS' ? ` + nadmiar ${amount(product.excess)} ${product.unit}` : product.status === 'PARTIAL' ? ` · pozostało ${amount(product.remaining)} ${product.unit}` : ' · wyprodukowano w całości'}`;
    node.querySelector('.warehouse-amount strong').textContent = `${amount(product.amount)} ${product.unit}`; node.querySelector('.warehouse-amount span').textContent = 'Wyprodukowano tego dnia'; fragment.append(node);
  }
  for (const reject of data.rejects) { const node = document.createElement('div'); node.className = 'reject-row'; node.textContent = `Wybrakowany towar · ${labels[reject.line]} · ${reject.description} · ${amount(reject.kilograms)} kg`; fragment.append(node); }
  $('warehouse-list').replaceChildren(fragment);
}
document.querySelectorAll('[data-tab]').forEach(b => b.addEventListener('click', () => {
  currentTab = b.dataset.tab; $('orders-page').hidden = currentTab !== 'orders'; $('warehouse-page').hidden = currentTab !== 'warehouse'; $('timeline-page').hidden = currentTab !== 'timeline'; $('page-title').textContent = { orders: 'Zamówienia', warehouse: 'Podgląd magazynu', timeline: 'Kolejka produkcji' }[currentTab];
  document.querySelectorAll('[data-tab]').forEach(n => n.classList.toggle('active', n.dataset.tab === currentTab)); refresh();
}));
$('filter-line').addEventListener('change', renderOrders); $('search-orders').addEventListener('input', renderOrders); $('refresh').addEventListener('click', refresh);
$('warehouse-date').addEventListener('change', () => { if ($('warehouse-date').value) refresh(); });
for (const [id, offset] of [['previous-day', -1], ['next-day', 1]]) $(id).addEventListener('click', () => { $('warehouse-date').value = changeDay($('warehouse-date').value || currentDay(), offset); refresh(); });
$('current-day').addEventListener('click', () => { $('warehouse-date').value = currentDay(); $('queue-date').value = day(); refresh(); });
selectLine('BUTTER');

function renderTimeline() {
  const lines = $('queue-line').value ? [$('queue-line').value] : Object.keys(labels);
  const entries = latest.timeline.entries.filter(e => lines.includes(e.line));
  $('queue-count').textContent = `${entries.length} pozycji · godziny rozpoczęcia, bez szacowania czasu trwania`;
  const grid = $('timeline-grid'), head = $('timeline-head'), scrollPosition = grid.scrollTop;
  grid.style.setProperty('--lanes', lines.length); head.style.setProperty('--lanes', lines.length);
  const timeLabel = document.createElement('span'); timeLabel.textContent = 'Godzina'; head.replaceChildren(timeLabel);
  for (const line of lines) { const title = document.createElement('strong'); title.textContent = labels[line]; title.className = line; head.append(title); }
  grid.replaceChildren();
  function slot(label, matches) {
    const time = document.createElement('div'); time.className = 'timeline-time'; time.textContent = label; grid.append(time);
    for (const line of lines) {
      const cell = document.createElement('div'); cell.className = `timeline-cell ${line}`;
      for (const entry of entries.filter(e => e.line === line && matches(e))) {
        const card = document.createElement('article'); card.className = `timeline-card ${line} ${entry.butterKind === 'Mix' ? 'mix-card' : ''}`; card.dataset.orderId = entry.id;
        const top = document.createElement('div'); top.className = 'timeline-card-top'; top.textContent = `${entry.time ?? 'Bez godziny'} · ${number(entry.orderNumber)}`;
        const title = document.createElement('h3'); title.textContent = entry.title;
        const quantity = document.createElement('p'); quantity.textContent = `${entry.remaining == null ? 'Ilość do uzupełnienia' : `Pozostało ${amount(entry.remaining)} ${entry.unit}`}${entry.code ? ` · kod ${entry.code}` : ''}`;
        card.append(top, title, quantity);
        if (entry.note) { const note = document.createElement('p'); note.className = 'timeline-note'; note.classList.toggle('important', entry.important); note.textContent = entry.note; card.append(note); }
        cell.append(card);
      }
      grid.append(cell);
    }
  }
  if (entries.some(e => !e.time)) slot('Bez godziny', e => !e.time);
  for (let hour = 0; hour < 24; hour += 2) slot(`${String(hour).padStart(2, '0')}:00`, e => e.time && Math.floor(Number(e.time.slice(0, 2)) / 2) * 2 === hour);
  grid.scrollTop = scrollPosition;
}
$('queue-line').addEventListener('change', renderTimeline); $('queue-refresh').addEventListener('click', refresh);
$('queue-date').addEventListener('change', () => { if ($('queue-date').value) refresh(); });
for (const [id, offset] of [['queue-previous', -1], ['queue-next', 1]]) $(id).addEventListener('click', () => { $('queue-date').value = changeDay($('queue-date').value || day(), offset); refresh(); });
$('queue-today').addEventListener('click', () => { $('queue-date').value = day(); refresh(); });
