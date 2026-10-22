#!/usr/bin/env node
// Exercise the shipped client with a minimal DOM; no browser or external dependencies.
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const root = path.resolve(__dirname, '../backend/src/main/resources/authentication');
function client({signup = true, authenticated = false, destination, contentType = 'application/json', statusOk = true} = {}) {
  const html = fs.readFileSync(path.join(root, 'index.html'), 'utf8');
  const elements = new Map();
  for (const match of html.matchAll(/<[^>]*\bid="([^"]+)"[^>]*>/g)) {
    const listeners = {};
    elements.set(match[1], {hidden: /\bhidden\b/.test(match[0]), disabled: /\bdisabled\b/.test(match[0]), textContent: '',
      addEventListener: (event, handler) => {listeners[event] = handler;}, listeners, focus() {}});
  }
  const calls = [], navigations = [];
  const location = {href:'https://iruvu.space/', pathname:'/', hash:'', assign: value => navigations.push(value)};
  const handlers = {};
  const window = {location, addEventListener:(event, handler) => {handlers[event] = handler;}, scrollTo() {}};
  const document = {body:{dataset:{page:'login'}}, hidden:false, getElementById:id => elements.get(id) || null,
    addEventListener() {}, title:''};
  const authorizationUrl = destination || 'https://irv-instance-4ftbgu.us1.zitadel.cloud/oauth/v2/authorize?state=test-state&code_challenge_method=S256';
  const fetch = async (url, options) => {
    calls.push({url, options});
    if (url === '/auth/status') return {ok:statusOk,json:async()=>({checks:[],authenticated,signupEnabled:signup})};
    if (url === '/auth/csrf') return {ok:true,json:async()=>({headerName:'X-CSRF-TOKEN',token:'csrf-token'})};
    if (url === '/auth/login') return {ok:true,headers:{get:()=>contentType},json:async()=>({authorizationUrl})};
    throw new Error('Unexpected fetch');
  };
  const context = vm.createContext({document, window, location, history:{replaceState() {}}, URL, fetch,
    AbortController, setTimeout:()=>1,clearTimeout() {},setInterval() {}});
  vm.runInContext(fs.readFileSync(path.join(root, 'app.js'), 'utf8'), context);
  return {elements, calls, navigations, location, handlers, context};
}
const settled = () => new Promise(resolve => setImmediate(resolve));
test('Passkey action fetches with CSRF and navigates only after a valid JSON handoff', async () => {
  const app = client(); await settled();
  await app.elements.get('login').listeners.click();
  assert.equal(app.navigations.length, 1);
  const start = app.calls.find(c => c.url === '/auth/login');
  assert.equal(start.options.method, 'POST');
  assert.equal(start.options.redirect, 'error');
  assert.equal(start.options.headers['X-CSRF-TOKEN'], 'csrf-token');
  assert.equal(app.elements.has('login-form'), false);
});
for (const destination of ['https://evil.invalid/oauth/v2/authorize?state=x&code_challenge_method=S256',
    'https://irv-instance-4ftbgu.us1.zitadel.cloud/download?state=x&code_challenge_method=S256',
    'https://user@irv-instance-4ftbgu.us1.zitadel.cloud/oauth/v2/authorize?state=x&code_challenge_method=S256',
    'https://irv-instance-4ftbgu.us1.zitadel.cloud/oauth/v2/authorize?state=x']) {
  test('Untrusted or malformed login destination cannot navigate: ' + destination, async () => {
    const app = client({destination}); await settled(); await app.elements.get('login').listeners.click();
    assert.equal(app.navigations.length, 0); assert.equal(app.elements.get('message').hidden, false);
  });
}
test('Non-JSON response cannot trigger navigation or download', async () => {
  const app = client({contentType:'application/octet-stream'}); await settled();
  await app.elements.get('login').listeners.click(); assert.equal(app.navigations.length, 0);
});
test('Unavailable signup stays hidden without configuration prose', async () => {
  const app = client({signup:false}); await settled();
  assert.equal(app.elements.get('open-signup').hidden, true);
  assert.equal(app.elements.get('signup-section').hidden, true);
  assert.equal(app.elements.get('message').hidden, true);
  assert.equal(app.elements.get('login').disabled, false);
});
test('Configured signup opens its own view and preserves the icon back action', async () => {
  const app = client(); await settled();
  assert.equal(app.elements.get('open-signup').hidden, false);
  app.location.hash = '#signup'; app.handlers.hashchange();
  assert.equal(app.elements.get('signup-section').hidden, false);
  assert.equal(app.elements.get('signin-section').hidden, true);
  assert.equal(app.elements.get('signup-back').hidden, false);
});
test('An unverified status disables authentication actions', async () => {
  const app = client({statusOk:false}); await settled();
  assert.equal(app.elements.get('login').disabled, true);
  assert.equal(app.elements.get('open-signup').hidden, true);
});
