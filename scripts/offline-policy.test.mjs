// node --experimental-strip-types scripts/offline-policy.test.mjs
import assert from 'node:assert/strict';
const p = await import('../web/lib/offline/policy.ts');
const K = 'a'.repeat(32);
// only idempotent POSTs are queued
assert.equal(p.queueKind('POST', '/billing/invoices', { idempotencyKey: K }), 'Invoice');
assert.equal(p.queueKind('POST', '/payments', { idempotencyKey: K }), 'Payment');
assert.equal(p.queueKind('POST', '/daily-routes/r1/reconciliation', { idempotencyKey: K }), 'End-of-day count');
assert.equal(p.queueKind('POST', '/billing/invoices', {}), null, 'no key → not queued');
assert.equal(p.queueKind('POST', '/expenses', { idempotencyKey: K }), null, 'not server-idempotent → not queued');
assert.equal(p.queueKind('PATCH', '/billing/invoices', { idempotencyKey: K }), null);
assert.equal(p.queueKind('POST', '/login', { idempotencyKey: K }), null);
// never "synced" without 2xx; stock/validation rejections become conflicts, not deletions
assert.equal(p.classify(200), 'synced'); assert.equal(p.classify(null), 'network');
assert.equal(p.classify(400), 'conflict'); assert.equal(p.classify(409), 'conflict'); assert.equal(p.classify(403), 'conflict');
assert.equal(p.classify(401), 'auth'); assert.equal(p.classify(503), 'retry'); assert.equal(p.classify(429), 'retry');
// bounded backoff
assert.equal(p.backoffMs(0), 2000); assert.equal(p.backoffMs(3), 16000); assert.equal(p.backoffMs(50), 5 * 60 * 1000);
assert.equal(p.serverRef({ number: 'INV-1', id: 'x' }), 'INV-1'); assert.equal(p.serverRef({ reference_number: 'TR-2' }), 'TR-2');
console.log('offline policy: all tests passed');
