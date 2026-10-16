'use strict';
const labels = {passed: 'Passed', failed: 'Failed', pending: 'Pending', not_configured: 'Not configured', not_required: 'Not required'};
const messages = {
  login_validation_failed: 'Login transaction or token validation failed. Start a new login.',
  passkey_evidence_missing: 'The signed token did not provide usable passkey evidence.',
  passkey_evidence_rejected: 'Authentication was not accepted as passkey-only. Password/OTP or unknown methods are rejected.',
  fresh_authentication_required: 'Fresh authentication was not proven. Start a new passkey login.',
  required_role_missing: 'The required irv user role was not present.',
  certificate_required: 'Certificate admission failed.',
  login_transaction_missing: 'The certificate-bound login transaction was missing.',
  invalid_subject: 'The provider returned an invalid account identifier.'
};
let busy = false, authenticated = false, activityAt = 0;
const message = document.getElementById('message');
const error = new URL(location.href).searchParams.get('auth_error');
if (error) {
  message.textContent = messages[error] || 'Login failed.';
  history.replaceState(null, '', '/');
}
async function csrf() {
  const response = await fetch('/auth/csrf', {cache: 'no-store', credentials: 'same-origin'});
  if (!response.ok) throw new Error('Certificate approval or trusted forwarding is not configured.');
  return response.json();
}
async function post(path) {
  const token = await csrf();
  return fetch(path, {method: 'POST', credentials: 'same-origin', cache: 'no-store', headers: {[token.headerName]: token.token}});
}
async function refresh() {
  if (busy) return;
  busy = true;
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 5000);
  try {
    const response = await fetch('/auth/status', {cache: 'no-store', credentials: 'same-origin', signal: controller.signal});
    if (!response.ok) throw new Error('Unavailable');
    const data = await response.json();
    if (!Array.isArray(data.checks) || typeof data.authenticated !== 'boolean') throw new Error('Invalid report');
    authenticated = data.authenticated;
    document.getElementById("signup-section").hidden = authenticated || !data.signupEnabled;
    const list = document.getElementById('checks'); list.replaceChildren();
    for (const check of data.checks) {
      const item = document.createElement('li');
      const title = document.createElement('strong'); title.textContent = check.label;
      const badge = document.createElement('span');
      badge.className = 'badge ' + (Object.hasOwn(labels, check.status) ? check.status : 'pending');
      badge.textContent = labels[check.status] || 'Unknown';
      const detail = document.createElement('p'); detail.textContent = check.detail;
      item.append(title, badge, detail); list.append(item);
    }
    document.getElementById('overall').textContent = data.overall;
    document.getElementById('freshness').textContent = 'Checked: ' + new Date(data.checkedAt).toLocaleTimeString();
    document.getElementById('expiry').textContent = authenticated
      ? 'Idle expiry: ' + new Date(data.idleExpiresAt).toLocaleTimeString() + ' · Absolute expiry: ' + new Date(data.absoluteExpiresAt).toLocaleTimeString()
      : '';
  } catch (_) {
    authenticated = false;
    document.getElementById('overall').textContent = 'Current status unavailable';
    document.getElementById('freshness').textContent = 'Could not verify this request.';
    document.getElementById('checks').replaceChildren();
    document.getElementById('expiry').textContent = '';
  } finally {
    document.getElementById('login').hidden = authenticated;
    document.getElementById('logout').hidden = !authenticated;
    document.getElementById('probe').hidden = !authenticated;
    clearTimeout(timeout); busy = false;
  }
}
document.getElementById('login').addEventListener('click', async () => {
  try {
    const token = await csrf();
    const form = document.getElementById('login-form'); form.replaceChildren();
    const input = document.createElement('input'); input.type = 'hidden'; input.name = token.parameterName; input.value = token.token;
    form.append(input); form.requestSubmit();
  } catch (e) { message.textContent = e.message; }
});
document.getElementById('logout').addEventListener('click', async () => {
  try { const r = await post('/auth/logout'); message.textContent = r.ok ? 'Signed out of irv.' : 'Sign-out could not be confirmed.'; }
  catch (_) { message.textContent = 'Sign-out could not be confirmed.'; }
  await refresh();
});
document.getElementById('probe').addEventListener('click', async () => {
  try {
    const r = await fetch('/auth/protected', {cache: 'no-store', credentials: 'same-origin'});
    message.textContent = r.ok ? 'Protected backend request passed the configured authentication checks.' : 'Protected backend request denied.';
  } catch (_) { message.textContent = 'Backend unavailable.'; }
});
// Only deliberate foreground input sends activity; background polling/visibility/refresh never do.
async function activity(event) {
  if (!event.isTrusted || document.hidden || !authenticated || Date.now() - activityAt < 15000) return;
  if (event.target.closest && event.target.closest('#logout,#login')) return;
  activityAt = Date.now();
  try { const r = await post('/auth/activity'); if (!r.ok) await refresh(); } catch (_) {}
}
document.addEventListener('pointerdown', activity);
document.addEventListener('keydown', activity);
document.getElementById('refresh').addEventListener('click', refresh);
document.addEventListener('visibilitychange', () => { if (!document.hidden) refresh(); });
refresh();
setInterval(() => { if (!document.hidden) refresh(); }, 15000);

let enrolling = false;
function showEnrollment(data) {
  const url = new URL(data.enrollmentUrl);
  if (url.origin !== 'https://irv-instance-4ftbgu.us1.zitadel.cloud' || url.pathname !== '/ui/v2/login/passkey/set') throw new Error('Unexpected enrollment response.');
  const link = document.getElementById('enrollment-link'); link.href = url.href; link.hidden = false;
  document.getElementById('enrollment-message').textContent = 'Account created. Open passkey setup below; then return here to sign in. Do not share the setup link.';
}
document.getElementById('signup-form').addEventListener('submit', async event => {
  event.preventDefault(); if (enrolling) return;
  enrolling = true; document.getElementById('signup').disabled = true;
  const status = document.getElementById('enrollment-message'); status.textContent = 'Creating your account…';
  try {
    const token = await csrf();
    const r = await fetch('/auth/signup', {method: 'POST', credentials: 'same-origin', cache: 'no-store',
      headers: {[token.headerName]: token.token}, body: new URLSearchParams(new FormData(event.target))});
    const data = await r.json();
    if (!r.ok) throw new Error(data.error || 'Registration failed.');
    event.target.reset(); showEnrollment(data);
  } catch (e) { status.textContent = e.message || 'Registration could not be confirmed. Try Resume passkey setup before submitting another invite.'; }
  finally { enrolling = false; document.getElementById('signup').disabled = false; }
});
document.getElementById('resume-enrollment').addEventListener('click', async () => {
  try {
    const r = await post('/auth/signup/resume'); const data = await r.json();
    if (!r.ok) throw new Error(data.error || 'Cannot resume enrollment.'); showEnrollment(data);
  } catch (e) { document.getElementById('enrollment-message').textContent = e.message; }
});
