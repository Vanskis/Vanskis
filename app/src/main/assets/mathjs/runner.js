// Liquid Pocket's calculate tool. Runs in an isolated JavaScript sandbox (no files, no network)
// after math.js; the app calls lpRun(expressions, mode) and reads back the JSON it returns.
function lpEngine(config) {
  var engine = math.create();
  engine.config(config);
  return engine;
}

var lpEngines = {
  exact: lpEngine({ number: 'BigNumber', precision: 64 }),
  fraction: lpEngine({ number: 'Fraction' }),
  number: math
};

function lpFormat(engine, value) {
  if (value && value.isResultSet) value = value.entries.length === 1 ? value.entries[0] : value.entries;
  if (typeof value === 'function') return 'function defined';
  if (value === undefined) return 'undefined';
  return engine.format(value, { precision: 32, lowerExp: -12, upperExp: 33 });
}

function lpEvaluate(engine, expressions) {
  var scope = new Map();
  return expressions.map(function (expression) {
    return { expression: expression, result: lpFormat(engine, engine.evaluate(expression, scope)) };
  });
}

function lpRun(expressions, mode) {
  try {
    return JSON.stringify({ results: lpEvaluate(lpEngines[mode] || lpEngines.exact, expressions) });
  } catch (exact) {
    try {
      var results = lpEvaluate(lpEngines.number, expressions);
      return JSON.stringify({ results: results, note: 'Computed in double precision (' + exact.message + ').' });
    } catch (e) {
      return JSON.stringify({ error: String((e && e.message) || e) });
    }
  }
}
