const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

class Element {
  constructor() {
    this.children = [];
    this.options = [];
    this.value = '';
    this.style = {};
    this.hidden = false;
    this.textContent = '';
    this.files = [];
  }
  set innerHTML(value) {
    this.html = value;
    if (value === '') {
      this.children = [];
      this.options = [];
      this.value = '';
    }
  }
  get innerHTML() { return this.html; }
  append(value) { this.children.push(value); }
  add(value) { this.options.push(value); }
}

function loadApp() {
  const html = fs.readFileSync('index.html', 'utf8');
  const match = html.match(/<script>([\s\S]*?)<\/script>/);
  assert.ok(match, 'app script exists');
  const script = match[1].replace(/render\(\);\s*$/, '');
  const elements = {};
  const storage = {};
  const document = {
    getElementById(id) { return elements[id] ??= new Element(); },
    createElement() { return new Element(); },
    body: { append() {} },
  };
  const context = {
    document,
    localStorage: {
      getItem(key) { return storage[key] ?? null; },
      setItem(key, value) { storage[key] = String(value); },
    },
    Option: function (text, value) { this.text = String(text); this.value = String(value); },
    URL: { createObjectURL() { return ''; }, revokeObjectURL() {} },
    Blob: function () {},
    setTimeout() {},
    confirm() { return true; },
    Date, JSON, Math, Number, Array, Set, Map, Error,
  };
  vm.createContext(context);
  vm.runInContext(`${script}
    globalThis.appTest = {
      render, generate, save, check, getHistory, combos, overlap,
      set(kindValue, numbers, condition, guarantee) {
        kind = kindValue; chosen = numbers; mode = 'cover'; render();
        $('hits').value = String(condition);
        $('hits').onchange();
        $('guarantee').value = String(guarantee);
      },
      state() { return { games, details }; },
      setDraw(numbers) { drawn = numbers; },
      element(id) { return $(id); },
    };`, context);
  return context.appTest;
}

function combinations(items, size) {
  const out = [];
  function visit(start, selected) {
    if (selected.length === size) { out.push(selected); return; }
    for (let i = start; i <= items.length - (size - selected.length); i++) {
      visit(i + 1, [...selected, items[i]]);
    }
  }
  visit(0, []);
  return out;
}

function assertCoverage(app, kind, selected, condition, guarantee, expectedScenarios) {
  app.set(kind, selected, condition, guarantee);
  app.generate();
  const message = app.element('message').textContent;
  assert.match(message, /Garantia condicional verificada/);
  const { games } = app.state();
  const scenarios = combinations(selected, condition);
  assert.equal(scenarios.length, expectedScenarios);
  for (const scenario of scenarios) {
    assert.ok(games.some(game => app.overlap(game, scenario) >= guarantee),
      `uncovered ${kind} scenario: ${scenario.join(',')}`);
  }
}

test('Lotofácil verifies every feasible conditional scenario', () => {
  const app = loadApp();
  assertCoverage(app, 'lotofacil', Array.from({ length: 16 }, (_, i) => i + 1), 14, 14, 120);
});

test('Mega-Sena handles zero selected hits and verifies all one-hit cases', () => {
  const app = loadApp();
  assertCoverage(app, 'mega', [1, 2, 3, 4, 5, 6], 0, 0, 1);
  assertCoverage(app, 'mega', [1, 2, 3, 4, 5, 6, 7], 1, 1, 7);
});

test('Quina verifies all one-hit cases', () => {
  const app = loadApp();
  assertCoverage(app, 'quina', [1, 2, 3, 4, 5, 6], 1, 1, 6);
});

test('rejects impossible conditions and prevents consecutive duplicate saves', () => {
  const app = loadApp();
  app.set('lotofacil', Array.from({ length: 15 }, (_, i) => i + 1), 5, 1);
  app.element('hits').value = '4'; // simulate a stale or tampered condition
  app.generate();
  assert.match(app.element('message').textContent, /Condição impossível/);

  app.set('mega', [1, 2, 3, 4, 5, 6], 6, 6);
  app.generate();
  app.save();
  app.save();
  assert.equal(app.getHistory().length, 1);
});

test('condition selectors include only mathematically feasible zero-to-maximum hits', () => {
  const app = loadApp();
  app.set('mega', [1, 2, 3, 4, 5, 6], 0, 0);
  assert.deepEqual(app.element('hits').options.map(option => Number(option.value)), [0, 1, 2, 3, 4, 5, 6]);
  assert.deepEqual(app.element('guarantee').options.map(option => Number(option.value)), [0]);

  app.set('lotofacil', Array.from({ length: 15 }, (_, i) => i + 1), 5, 5);
  assert.deepEqual(app.element('hits').options.map(option => Number(option.value)), [5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15]);
});

test('conferência reports the best hit count across generated games', () => {
  const app = loadApp();
  app.set('mega', [1, 2, 3, 4, 5, 6], 6, 6);
  app.generate();
  app.setDraw([1, 2, 3, 4, 5, 6]);
  app.check();
  assert.match(app.element('checkResult').textContent, /Melhor jogo: 6 acertos/);
});
