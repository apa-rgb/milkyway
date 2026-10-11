export const PROJECT = 'milyway-41e9e';
export const LINES = { BUTTER: 'Masłownia', POWDER: 'Proszkownia', UHT: 'UHT' };
const TABLES = ['production_queue', 'production_completions', 'production_product_notes', 'production_rejects'];
export class UserError extends Error {}
export function check(condition, message) { if (!condition) throw new UserError(message); }
export function sharedKey(id) { return Buffer.from(id, 'utf8').toString('base64url'); }
export function warsawDay(now) {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Warsaw', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(now);
  const field = name => parts.find(x => x.type === name).value;
  return `${field('year')}-${field('month')}-${field('day')}`;
}
export function quantity(value) {
  check(typeof value === 'string' && value.length <= 80, 'Wpisz ilość.');
  const normalized = value.trim().replace(',', '.');
  check(/^\d+(?:\.\d+)?$/.test(normalized), 'Wpisz poprawną ilość, np. 1250,5.');
  let [whole, fraction = ''] = normalized.split('.');
  whole = whole.replace(/^0+(?=\d)/, ''); fraction = fraction.replace(/0+$/, '');
  check(whole !== '0' || fraction !== '', 'Ilość musi być większa od zera.');
  check(fraction.length <= 3 && (whole + fraction).replace(/^0+/, '').length <= 24, 'Ilość może mieć do 24 cyfr i dokładność 0,001.');
  return whole + (fraction ? `.${fraction}` : '');
}
export function normalizeName(value) {
  return String(value ?? '').replace(/[\[\]\r\n]/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 60);
}
export function noteText(body, author, important = false) {
  return [important ? '[Ważne]' : '', author ? `[Autor: ${normalizeName(author)}]` : '', body.trim()].filter(Boolean).join('\n');
}
export function visibleNote(value) {
  let result = String(value ?? '').replace(/^\[Ważne\](?:\n|$)/, '').replace(/^\[Autor: [^\[\]\r\n]{1,60}\](?:\n|$)/, '');
  result = result.replace(/^\[Przypomnienie: [0-9]{1,15}\|[a-f0-9-]{36}\|[0-9a-z,]*\|[01]\](?:\n|$)/, '').replace(/^\[Załatwione\](?:\n|$)/, '');
  return result;
}
export function validateOrder(value) {
  check(value && typeof value === 'object', 'Wpisz zamówienie.');
  check(typeof value.id === 'string' && /^windows-order:[a-f0-9-]{36}$/.test(value.id), 'Nieprawidłowy identyfikator zamówienia.');
  check(Object.hasOwn(LINES, value.line), 'Wybierz dział.');
  check(typeof value.title === 'string' && value.title.trim().length > 0 && value.title.trim().length <= 120, 'Wpisz nazwę produktu (do 120 znaków).');
  check(typeof value.description === 'string' && value.description.trim().length <= 10000, 'Opis może mieć do 10 000 znaków.');
  check(typeof value.note === 'string' && value.note.trim().length <= 4000, 'Notatka może mieć do 4000 znaków.');
  check(value.line !== 'BUTTER' || ['BUTTER', 'MIX'].includes(value.butterKind), 'Wybierz Masło albo Mix.');
  return { id: value.id, line: value.line, title: value.title.trim(), description: value.description.trim(), quantity: quantity(value.quantity),
    note: value.note.trim(), important: value.important === true, butterKind: value.line === 'BUTTER' ? value.butterKind : null };
}
export function validateDocument(value) {
  if (value == null) return;
  check(typeof value === 'object' && !Array.isArray(value) && [1, 2, 3].includes(value.schema), 'Nieobsługiwana wersja bazy. Zaktualizuj moduł zamówień.');
  check(Number.isSafeInteger(value.revision) && value.revision >= 0, 'Nieprawidłowa wersja danych. Zapis został przerwany.');
  check(value.tables == null || (typeof value.tables === 'object' && !Array.isArray(value.tables)), 'Nieprawidłowe tabele.');
  for (const [name, rows] of Object.entries(value.tables ?? {})) {
    check(TABLES.includes(name) && rows && typeof rows === 'object' && !Array.isArray(rows), 'Nieobsługiwana tabela danych.');
    for (const [key, row] of Object.entries(rows)) {
      check(row && typeof row === 'object' && typeof row.id === 'string' && key === sharedKey(row.id) && Number.isSafeInteger(row._rowid) && row._rowid > 0, 'Nieprawidłowy wiersz danych. Zapis został przerwany.');
    }
  }
}
function nextRowID(rows) {
  const n = Math.max(0, ...Object.values(rows).map(r => r._rowid)) + 1;
  check(Number.isSafeInteger(n), 'Osiągnięto limit identyfikatorów.'); return n;
}
/** Append both order and optional note in one production-domain transaction, retaining every existing field. */
export function addOrder(previous, input, actor, author, now = Date.now()) {
  validateDocument(previous); const order = validateOrder(input);
  check(actor && typeof actor.uid === 'string' && actor.uid.length > 0 && /^(0[1-9]|10)$/.test(actor.account), 'Zaloguj się do konta operatora.');
  check(Number.isSafeInteger(now) && now > 0, 'Nieprawidłowa data.');
  const key = sharedKey(order.id), noteID = `windows-order-note:${order.id}`, noteKey = sharedKey(noteID);
  const description = order.line === 'BUTTER' ? `Rodzaj produktu: ${order.butterKind === 'MIX' ? 'Mix' : 'Masło'}${order.description ? `\n${order.description}` : ''}` : order.description;
  const note = order.note ? noteText(order.note, author, order.important) : '';
  const old = previous?.tables?.production_queue?.[key];
  if (old) {
    // An ambiguous network result can be retried with the same ID, even after Android schedules the order.
    const existingNote = previous.tables?.production_product_notes?.[noteKey]?.note ?? '';
    check(old.line === order.line && old.title === order.title && old.description === description && old.planned_amount === order.quantity && existingNote === note,
      'To zamówienie zostało już zapisane z innymi danymi. Odśwież listę i sprawdź wpis.');
    return { document: previous, id: order.id, existing: true };
  }
  // Deleted-but-produced entries must never be recreated by a delayed request.
  check(!Object.values(previous?.tables?.production_completions ?? {}).some(r => r.entry_id === order.id), 'To zamówienie ma już historię produkcji.');
  const document = structuredClone(previous ?? {}), day = warsawDay(new Date(now));
  document.tables ??= {};
  const rows = document.tables.production_queue ??= {};
  const notes = document.tables.production_product_notes ??= {};
  const peers = Object.values(rows).filter(r => r.line === order.line && r.plan_date === day);
  for (const row of peers) check(Number.isSafeInteger(row.position) && row.position >= 0, 'Nieprawidłowa kolejność danych.');
  const position = Math.max(-1, ...peers.map(r => r.position)) + 1;
  check(Number.isSafeInteger(position), 'Osiągnięto limit kolejki.');
  const sequence = Math.max(previous?.orderSequence ?? 0, ...Object.values(rows).map(r => r.order_number ?? 0));
  check(Number.isSafeInteger(sequence) && sequence >= 0 && sequence < Number.MAX_SAFE_INTEGER, 'Nieprawidłowy licznik zamówień.');
  const orderNumber = sequence + 1;
  rows[key] = { id: order.id, line: order.line, plan_date: day, position, title: order.title, description,
    created_at: now, updated_at: now, planned_amount: order.quantity, produced_amount: '0', unit: order.line === 'UHT' ? 'LITRES' : 'KG',
    pending_order: 1, order_number: orderNumber, _rowid: nextRowID(rows), _actor: { ...actor, at: now } };
  if (note) notes[noteKey] = { id: noteID, entry_id: order.id, stage: 'ORDER', note, created_at: now, _rowid: nextRowID(notes), _actor: { ...actor, at: now } };
  document.schema = 3; document.orderSequence = orderNumber; document.revision = (previous?.revision ?? 0) + 1;
  check(Number.isSafeInteger(document.revision), 'Osiągnięto limit wersji.');
  document.updatedAt = now; document.actor = { ...actor };
  return { document, id: order.id, existing: false };
}
const milli = value => {
  const [whole, fraction = ''] = String(value ?? '0').split('.');
  return /^\d+$/.test(whole) && /^\d{0,3}$/.test(fraction) ? BigInt(whole) * 1000n + BigInt((fraction + '000').slice(0, 3)) : 0n;
};
function fromMilli(n) { const frac = (n % 1000n).toString().padStart(3, '0').replace(/0+$/, ''); return (n / 1000n).toString() + (frac ? `.${frac}` : ''); }
export function orderList(document) {
  validateDocument(document);
  const notes = Object.values(document?.tables?.production_product_notes ?? {}).sort((a, b) => b.created_at - a.created_at || b._rowid - a._rowid);
  return Object.values(document?.tables?.production_queue ?? {}).filter(r => r.id.startsWith('windows-order:')).sort((a, b) => b.created_at - a.created_at || b._rowid - a._rowid).map(row => {
    const left = milli(row.planned_amount) - milli(row.produced_amount), latest = notes.find(n => n.entry_id === row.id && !/^Kod produkcji: ([0-9]{3}|—)$/.test(n.note));
    return { id: row.id, orderNumber: row.order_number ?? null, line: row.line, title: row.title, quantity: row.planned_amount, unit: row.unit === 'LITRES' ? 'l' : 'kg',
      produced: row.produced_amount, remaining: fromMilli(left > 0n ? left : 0n), createdAt: row.created_at,
      status: left <= 0n ? 'COMPLETED' : row.pending_order === 1 ? 'PENDING' : 'PRODUCTION',
      date: row.plan_date, time: row.planned_time ?? '', note: visibleNote(latest?.note), important: latest?.note.startsWith('[Ważne]') ?? false,
      butterKind: row.line === 'BUTTER' ? (row.description.startsWith('Rodzaj produktu: Mix') ? 'Mix' : 'Masło') : '' };
  });
}
export function warehouseDay(now = new Date()) {
  const day = warsawDay(now), hour = Number(new Intl.DateTimeFormat('en-GB', { timeZone: 'Europe/Warsaw', hour: '2-digit', hourCycle: 'h23' }).format(now));
  if (hour >= 9) return day;
  const [y, m, d] = day.split('-').map(Number); return new Date(Date.UTC(y, m - 1, d - 1)).toISOString().slice(0, 10);
}
function validDay(day) {
  return typeof day === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(day) && Number.isFinite(Date.parse(`${day}T12:00:00Z`)) && new Date(`${day}T12:00:00Z`).toISOString().slice(0, 10) === day;
}
export function warehouseList(document, day = warehouseDay()) {
  validateDocument(document);
  check(validDay(day), 'Wybierz poprawną datę magazynu.');
  const tables = document?.tables ?? {}, grouped = new Map();
  for (const receipt of Object.values(tables.production_completions ?? {})) {
    if (receipt.produced_on !== day || receipt.warehouse_removed_at != null) continue;
    const entry = tables.production_queue?.[sharedKey(receipt.entry_id)]; if (!entry) continue;
    const total = (grouped.get(entry.id)?.amount ?? 0n) + milli(receipt.amount);
    grouped.set(entry.id, { entry, amount: total });
  }
  return {
    day,
    products: [...grouped.values()].map(({ entry: row, amount }) => {
      const planned = milli(row.planned_amount), produced = milli(row.produced_amount);
      return { id: row.id, orderNumber: row.order_number ?? null, title: row.title, line: row.line, amount: fromMilli(amount),
        ordered: row.planned_amount, totalProduced: row.produced_amount, remaining: fromMilli(planned > produced ? planned - produced : 0n),
        excess: fromMilli(produced > planned ? produced - planned : 0n), status: produced > planned ? 'EXCESS' : produced < planned ? 'PARTIAL' : 'FULL', unit: row.unit === 'LITRES' ? 'l' : 'kg' };
    }).sort((a, b) => (a.orderNumber ?? 0) - (b.orderNumber ?? 0)),
    rejects: Object.values(tables.production_rejects ?? {}).filter(r => r.record_date === day).map(r => ({ id: r.id, line: r.line, description: r.description, kilograms: r.kilograms }))
  };
}

/** Planned starts only: the shared application does not record an expected duration. */
export function productionTimeline(document, day = warsawDay(new Date())) {
  validateDocument(document);
  check(validDay(day), 'Wybierz poprawną datę kolejki.');
  const notes = Object.values(document?.tables?.production_product_notes ?? {}).sort((a, b) => b.created_at - a.created_at || b._rowid - a._rowid);
  const entries = Object.values(document?.tables?.production_queue ?? {})
    .filter(r => r.plan_date === day && r.pending_order !== 1 && Object.hasOwn(LINES, r.line) && (r.planned_amount == null || milli(r.planned_amount) > milli(r.produced_amount)))
    .sort((a, b) => (a.planned_time ?? '99:99').localeCompare(b.planned_time ?? '99:99') || a.position - b.position || a._rowid - b._rowid)
    .map(row => {
      const note = notes.find(n => n.entry_id === row.id && !/^Kod produkcji: ([0-9]{3}|—)$/.test(n.note));
      const code = notes.find(n => n.entry_id === row.id && /^Kod produkcji: ([0-9]{3}|—)$/.test(n.note));
      return { id: row.id, orderNumber: row.order_number ?? null, line: row.line, title: row.title,
        time: /^([01]\d|2[0-3]):[0-5]\d$/.test(row.planned_time ?? '') ? row.planned_time : null,
        remaining: row.planned_amount == null ? null : fromMilli(milli(row.planned_amount) - milli(row.produced_amount)), quantity: row.planned_amount ?? null,
        unit: row.unit === 'LITRES' ? 'l' : 'kg', note: visibleNote(note?.note), important: note?.note.startsWith('[Ważne]') ?? false,
        code: code && !code.note.endsWith('—') ? code.note.slice(-3) : '',
        butterKind: row.line === 'BUTTER' ? (row.description.startsWith('Rodzaj produktu: Mix') ? 'Mix' : 'Masło') : '' };
    });
  return { day, entries };
}
