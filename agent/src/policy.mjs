export const statuses = new Set(['kosher', 'not_kosher', 'unknown']);
export const markets = new Set(['IL', 'GB', 'OTHER']);
export function clean(value, max = 300) { return typeof value === 'string' ? value.trim().slice(0, max) : ''; }
export function safeUrl(value) {
  try { const u = new URL(value); return u.protocol === 'https:' && !u.username && !u.password && !u.port && !/^(localhost|127\.|10\.|192\.168\.|169\.254\.|\[)/.test(u.hostname) ? u.href : ''; } catch { return ''; }
}
export function normalizeSubmission(input) {
  const barcode = clean(input.barcode, 14), market = input.market;
  if (!/^\d{8,14}$/.test(barcode) || !markets.has(market) || !/^[a-f0-9-]{36}$/.test(input.requestId || '')) throw new Error('invalid_submission');
  const photo = clean(input.barcodePhoto, 280000);
  if (photo && (!/^\/9j\/[A-Za-z0-9+/]*={0,2}$/.test(photo) || photo.length > 270000)) throw new Error('invalid_photo');
  return {barcode, market, requestId: input.requestId, name: clean(input.product?.name), brand: clean(input.product?.brand),
    imageUrl: safeUrl(input.product?.imageUrl), photo};
}
export function visibleResult(row, now = Date.now()) {
  const valid = row?.phase === 'approved' && row.reviewed_at && statuses.has(row.status) && row.status !== 'unknown' &&
    /^\d{4}-\d{2}-\d{2}$/.test(row.expires_at) && Date.parse(row.expires_at + 'T23:59:59Z') >= now;
  return {barcode: row?.barcode, market: row?.market, phase: row?.phase || 'missing', approved: Boolean(valid),
    status: valid ? row.status : 'unknown', details: '', name: row?.product_name || '', brand: row?.brand || '',
    expiresAt: valid ? row.expires_at : '', reviewedAt: valid ? row.reviewed_at : null};
}
export function validateReview(body, now = Date.now()) {
  const status = body.status, evidence = safeUrl(body.evidenceUrl), expires = clean(body.expiresAt, 10);
  if (!statuses.has(status)) throw new Error('invalid_status');
  if (status !== 'unknown' && (!evidence || !/^\d{4}-\d{2}-\d{2}$/.test(expires) ||
      !Number.isFinite(Date.parse(expires + 'T00:00:00Z')) ||
      new Date(expires + 'T00:00:00Z').toISOString().slice(0,10) !== expires ||
      Date.parse(expires + 'T23:59:59Z') < now || Date.parse(expires) - now > 366 * 86400000)) throw new Error('evidence_and_current_expiry_required');
  return {status, evidence, expires: status === 'unknown' ? '' : expires, details: clean(body.details, 180)};
}
export function validateSuggestion(raw) {
  // AI identifies leads for review; it cannot publish or approve a verdict.
  return {name: clean(raw.name), brand: clean(raw.brand), suggestedStatus: statuses.has(raw.suggestedStatus) ? raw.suggestedStatus : 'unknown', imageCertification:clean(raw.imageCertification,80),
    explanation: clean(raw.explanation, 1400), evidence: Array.isArray(raw.evidence) ? raw.evidence.slice(0, 8).map(x => ({url: safeUrl(x.url), title: clean(x.title), quote: clean(x.quote, 400)})).filter(x => x.url) : []};
}
