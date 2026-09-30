import test from 'node:test';
import assert from 'node:assert/strict';
import { esPrecisoParaTrazo, PRECISION_MAX_TRAZO_M } from '../src/ruteo.js';

test('fix de GPS (10 m) entra al trazo', () => {
  assert.equal(esPrecisoParaTrazo({ precisionM: 10 }), true);
});

test('fix de antena (92.9 m, Manzaba 29/09) queda fuera del trazo', () => {
  assert.equal(esPrecisoParaTrazo({ precisionM: 92.9 }), false);
});

test('el límite es inclusivo y sin precisión no se descarta', () => {
  assert.equal(esPrecisoParaTrazo({ precisionM: PRECISION_MAX_TRAZO_M }), true);
  assert.equal(esPrecisoParaTrazo({ precisionM: null }), true);
  assert.equal(esPrecisoParaTrazo({ precision_m: 120 }), false);
});
