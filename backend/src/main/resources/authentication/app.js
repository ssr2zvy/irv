'use strict';
const byId = id => document.getElementById(id);
const home = document.body.dataset.page === 'login';
const labels = {passed:'Passed',failed:'Failed',pending:'Pending',not_configured:'Not configured',not_required:'Not required'};
const errors = {
  login_validation_failed:'Sign-in could not be verified. Please try again.',
  passkey_evidence_missing:'Passkey verification was incomplete. Please try again.',
  passkey_evidence_rejected:'Use a passkey to sign in.',
  fresh_authentication_required:'Please authenticate again with your passkey.',
  required_role_missing:'This account does not have access.',
  certificate_required:'Certificate verification failed.',
  login_transaction_missing:'Your sign-in expired. Please try again.',
  invalid_subject:'This account could not be verified.'
};
let busy = false, authenticated = false, signupEnabled = false, activityAt = 0;
let noticeTimer, statusFailed = false, loggingIn = false, enrolling = false;
function notify(text) {
  const notice = byId('message');
  clearTimeout(noticeTimer); notice.textContent = text; notice.hidden = false;
  noticeTimer = setTimeout(() => {notice.hidden = true; notice.textContent = '';}, 6500);
}
function signupPage(focus = false) {
  if (!home) return;
  const open = location.hash === '#signup' && signupEnabled && !authenticated;
  byId('signin-section').hidden = open;
  byId('signup-section').hidden = !open;
  byId('signup-back').hidden = !open;
  byId('page-title').textContent = open ? 'Create account' : authenticated ? 'Signed in' : 'Sign in';
  byId('page-title').hidden = !open && !authenticated;
  document.title = 'irv · ' + byId('page-title').textContent;
  if (focus) {(byId('page-title').hidden ? byId('login') : byId('page-title')).focus(); window.scrollTo(0, 0);}
}
if (home) {
  byId('open-signup').addEventListener('click', () => {location.hash = 'signup';});
  byId('signup-back').addEventListener('click', () => {
    history.replaceState(null, '', '/'); signupPage(true);
  });
  window.addEventListener('hashchange', () => signupPage(true));
}
const error = new URL(location.href).searchParams.get('auth_error');
if (error) {
  notify(errors[error] || 'Sign-in failed. Please try again.');
  history.replaceState(null, '', location.pathname);
}
async function csrf() {
  const response = await fetch('/auth/csrf', {cache:'no-store',credentials:'same-origin'});
  if (!response.ok) throw new Error('Request could not be verified.');
  return response.json();
}
async function post(path, body) {
  const token = await csrf();
  return fetch(path, {method:'POST',credentials:'same-origin',cache:'no-store',redirect:'error',
    headers:{[token.headerName]:token.token,Accept:'application/json'},body});
}
async function refresh() {
  if (busy) return;
  busy = true;
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 5000);
  try {
    const response = await fetch('/auth/status', {cache:'no-store',credentials:'same-origin',signal:controller.signal});
    if (!response.ok) throw new Error('Unavailable');
    const data = await response.json();
    if (!Array.isArray(data.checks) || typeof data.authenticated !== 'boolean') throw new Error('Invalid report');
    authenticated = data.authenticated; signupEnabled = data.signupEnabled === true; statusFailed = false;
    if (home) {byId('open-signup').hidden = authenticated || !signupEnabled; signupPage();}
    const list = byId('checks');
    if (list) {
      list.replaceChildren();
      for (const check of data.checks) {
        const item = document.createElement('li');
        const title = document.createElement('strong'); title.textContent = check.label;
        const badge = document.createElement('span');
        badge.className = 'badge ' + (Object.hasOwn(labels, check.status) ? check.status : 'pending');
        badge.textContent = labels[check.status] || 'Unknown';
        const detail = document.createElement('p'); detail.textContent = check.detail;
        item.append(title, badge, detail); list.append(item);
      }
      byId('overall').textContent = data.overall;
      byId('freshness').textContent = 'Checked ' + new Date(data.checkedAt).toLocaleTimeString();
      byId('expiry').textContent = authenticated
        ? 'Idle expiry ' + new Date(data.idleExpiresAt).toLocaleTimeString() + ' · Session expiry ' + new Date(data.absoluteExpiresAt).toLocaleTimeString() : '';
    }
  } catch (_) {
    authenticated = false; signupEnabled = false;
    if (home) {byId('open-signup').hidden = true; signupPage();}
    else {
      byId('overall').textContent = 'Status unavailable'; byId('freshness').textContent = '';
      byId('checks').replaceChildren(); byId('expiry').textContent = '';
    }
    if (!statusFailed) notify('Connection could not be verified. Try again shortly.');
    statusFailed = true;
  } finally {
    byId('login').hidden = authenticated;
    byId('login').disabled = statusFailed || loggingIn;
    byId('logout').hidden = !authenticated;
    if (byId('probe')) byId('probe').hidden = !authenticated;
    clearTimeout(timeout); busy = false;
  }
}
byId('login').addEventListener('click', async () => {
  if (loggingIn) return;
  loggingIn = true; byId('login').disabled = true;
  try {
    // Fetch never follows redirects. Only the fixed provider authorization URL can navigate.
    const response = await post('/auth/login');
    if (!response.ok || !response.headers.get('content-type')?.startsWith('application/json')) throw new Error('Login failed');
    const data = await response.json();
    const destination = new URL(data.authorizationUrl);
    if (destination.origin !== 'https://irv-instance-4ftbgu.us1.zitadel.cloud'
        || destination.pathname !== '/oauth/v2/authorize' || destination.username || destination.password
        || destination.hash || !destination.searchParams.get('state')
        || destination.searchParams.get('code_challenge_method') !== 'S256') throw new Error('Invalid destination');
    window.location.assign(destination.href);
  } catch (_) {
    loggingIn = false; byId('login').disabled = statusFailed;
    notify('Sign-in could not start. Please try again.');
  }
});
byId('logout').addEventListener('click', async () => {
  try {const response = await post('/auth/logout'); notify(response.ok ? 'Signed out.' : 'Sign-out could not be confirmed.');}
  catch (_) {notify('Sign-out could not be confirmed.');}
  await refresh();
});
byId('probe')?.addEventListener('click', async () => {
  try {
    const response = await fetch('/auth/protected', {cache:'no-store',credentials:'same-origin'});
    notify(response.ok ? 'Protected request passed.' : 'Protected request denied.');
  } catch (_) {notify('Connection unavailable.');}
});
async function activity(event) {
  if (!event.isTrusted || document.hidden || !authenticated || Date.now() - activityAt < 15000) return;
  if (event.target.closest?.('#logout,#login')) return;
  activityAt = Date.now();
  try {const response = await post('/auth/activity'); if (!response.ok) await refresh();} catch (_) {}
}
document.addEventListener('pointerdown', activity);
document.addEventListener('keydown', activity);
byId('refresh')?.addEventListener('click', refresh);
document.addEventListener('visibilitychange', () => {if (!document.hidden) refresh();});
function showEnrollment(data) {
  const url = new URL(data.enrollmentUrl);
  if (url.origin !== 'https://irv-instance-4ftbgu.us1.zitadel.cloud' || url.pathname !== '/ui/v2/login/passkey/set'
      || url.username || url.password) throw new Error('Unexpected enrollment response.');
  const link = byId('enrollment-link'); link.href = url.href; link.hidden = false;
  byId('enrollment-message').textContent = 'Your account is ready for passkey setup.';
  byId('enrollment-next').hidden = false; byId('signup-form').hidden = true;
}
byId('signup-form')?.addEventListener('submit', async event => {
  event.preventDefault(); if (enrolling) return;
  enrolling = true; byId('signup').disabled = true; byId('signup').textContent = 'Creating…';
  try {
    const response = await post('/auth/signup', new URLSearchParams(new FormData(event.target)));
    const data = await response.json();
    if (!response.ok) {notify(data.error || 'Account creation failed.'); return;}
    event.target.reset(); showEnrollment(data);
  } catch (_) {notify('Account creation could not be confirmed. Try resuming passkey setup.');}
  finally {enrolling = false; byId('signup').disabled = false; byId('signup').textContent = 'Create account';}
});
byId('resume-enrollment')?.addEventListener('click', async () => {
  try {
    const response = await post('/auth/signup/resume'); const data = await response.json();
    if (!response.ok) {notify(data.error || 'Cannot resume setup.'); return;}
    showEnrollment(data);
  } catch (_) {notify('Passkey setup could not be resumed.');}
});
refresh();
setInterval(() => {if (!document.hidden) refresh();}, 15000);
