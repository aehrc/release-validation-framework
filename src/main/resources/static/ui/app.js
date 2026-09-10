/*
 * RVF console.
 *
 * Same-origin calls only. The gateway has already authenticated the browser
 * and injects the X-AUTH-* headers the server reads, so there is no token
 * handling here and nothing secret in this file.
 */
'use strict';

// The console is served from /ui/, the API sits at the root.
const API = '..';

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

/* Every value from a report goes through this. Reports carry SQL text and
 * content from the release, so inserting them as HTML would be an injection
 * waiting to happen. */
const esc = (v) => String(v ?? '').replace(/[&<>"']/g,
  (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

const num = (n) => (typeof n === 'number' ? n.toLocaleString() : '0');

function toast(message, ok = false) {
  const el = $('#toast');
  el.textContent = message;
  el.classList.toggle('ok', ok);
  el.hidden = false;
  clearTimeout(toast._t);
  toast._t = setTimeout(() => { el.hidden = true; }, ok ? 4000 : 9000);
}

/* A 302 to the identity provider means the session has gone. fetch follows it
 * and we end up parsing a login page as JSON, so detect it and say so plainly
 * rather than reporting a syntax error. */
async function api(path, options) {
  const res = await fetch(`${API}${path}`, { credentials: 'same-origin', ...options });
  if (res.redirected && /openid-connect|protocol\/openid/.test(res.url)) {
    throw new Error('Your session has ended. Reload the page to sign in again.');
  }
  if (!res.ok) {
    let detail = '';
    try { detail = (await res.text()).slice(0, 300); } catch { /* body already gone */ }
    throw new Error(`${res.status} ${res.statusText}${detail ? ` - ${detail}` : ''}`);
  }
  const type = res.headers.get('content-type') || '';
  return type.includes('json') ? res.json() : res.text();
}

/* ------------------------------------------------------------------- tabs */

$$('.tab').forEach((tab) => {
  tab.addEventListener('click', () => {
    $$('.tab').forEach((t) => { t.classList.remove('active'); t.setAttribute('aria-selected', 'false'); });
    $$('.panel').forEach((p) => p.classList.remove('active'));
    tab.classList.add('active');
    tab.setAttribute('aria-selected', 'true');
    $('#' + tab.dataset.panel).classList.add('active');
    // Load on first view rather than at start-up: it is a directory walk on a
    // network file share, so it should not be paid for by someone who only
    // wants to submit a run.
    if (tab.dataset.panel === 'panel-open' && !loadRuns.done) loadRuns();
    if (tab.dataset.panel === 'panel-releases' && !loadReleases.done) loadReleases();
  });
});

/* --------------------------------------------------------------- start-up */

function defaults() {
  const stamp = Date.now();
  $('#runId').value = String(stamp);
  $('#storageLocation').value = `ui_${new Date().toISOString().slice(0, 10).replace(/-/g, '')}_${String(stamp).slice(-6)}`;
}

async function loadVersion() {
  try {
    const v = await api('/version');
    const text = typeof v === 'string' ? v : (v.version || JSON.stringify(v));
    $('#serverInfo').textContent = `connected \u00b7 ${text}`;
    $('#footVersion').textContent = `RVF ${text}`;
  } catch (e) {
    $('#serverInfo').textContent = 'not connected';
    toast(e.message);
  }
}

/* The group names come from the server rather than a list in this file, so a
 * group added to the store shows up here with no change to the console. */
async function loadGroups() {
  const box = $('#groups');
  try {
    const raw = await api('/groups');
    const list = (Array.isArray(raw) ? raw : raw.content || raw.items || [])
      .map((g) => (typeof g === 'string' ? g : g.name || g.id))
      .filter(Boolean)
      .sort((a, b) => a.localeCompare(b));

    if (!list.length) { box.innerHTML = '<p class="muted">This server has no assertion groups.</p>'; return; }

    box.innerHTML = list.map((name) => `
      <label class="check">
        <input type="checkbox" name="group" value="${esc(name)}">
        <span>${esc(name)}</span>
      </label>`).join('');

    // Preselect the group the nightly leans on, when it is present.
    const first = $$('input[name=group]', box).find((i) => i.value === 'file-centric-validation');
    if (first) first.checked = true;
  } catch (e) {
    box.innerHTML = `<p class="muted">Could not load groups: ${esc(e.message)}</p>`;
  }
}

/* ------------------------------------------------------------- the upload */

const drop = $('#drop');
const fileInput = $('#file');

['dragenter', 'dragover'].forEach((ev) =>
  drop.addEventListener(ev, (e) => { e.preventDefault(); drop.classList.add('over'); }));
['dragleave', 'drop'].forEach((ev) =>
  drop.addEventListener(ev, (e) => { e.preventDefault(); drop.classList.remove('over'); }));

drop.addEventListener('drop', (e) => {
  const file = e.dataTransfer?.files?.[0];
  if (!file) return;
  const data = new DataTransfer();
  data.items.add(file);
  fileInput.files = data.files;
  showFile();
});
fileInput.addEventListener('change', showFile);

function showFile() {
  const f = fileInput.files?.[0];
  $('#dropFile').textContent = f ? `${f.name} (${(f.size / 1048576).toFixed(1)} MB)` : '';
  previewPrevious();
}

$('#enableDrools').addEventListener('change', (e) => { $('#droolsGroupsWrap').hidden = !e.target.checked; });
$('#enableMrcmValidation').addEventListener('change', (e) => { $('#mrcmHint').hidden = !e.target.checked; });

/* ----------------------------------------------------------------- upload */

/* fetch() cannot report upload progress - there is no event for it - so a
 * release of several hundred megabytes appeared to hang with a disabled button
 * and nothing else. XMLHttpRequest still exposes upload progress, so it is used
 * for the two requests that carry a file. Everything else stays on fetch. */
function upload(path, body, onProgress) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    upload.current = xhr;
    xhr.open('POST', `${API}${path}`);
    xhr.withCredentials = true;

    xhr.upload.addEventListener('progress', (e) => {
      if (e.lengthComputable) onProgress(e.loaded, e.total);
    });
    // The last byte leaving is not the end: the server still has to accept the
    // release and enqueue it. Say that, rather than sit at 100% looking stuck.
    xhr.upload.addEventListener('load', () => onProgress(-1, -1));

    xhr.addEventListener('load', () => {
      upload.current = null;
      if (/openid-connect|protocol\/openid/.test(xhr.responseURL || '')) {
        reject(new Error('Your session has ended. Reload the page to sign in again.'));
        return;
      }
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve(xhr.responseText);
      } else {
        reject(new Error(`${xhr.status} ${xhr.statusText}`
          + (xhr.responseText ? ` - ${xhr.responseText.slice(0, 200)}` : '')));
      }
    });
    xhr.addEventListener('error', () => { upload.current = null; reject(new Error('The connection failed during the upload')); });
    xhr.addEventListener('abort', () => { upload.current = null; reject(new Error('Upload cancelled')); });
    xhr.send(body);
  });
}

const MB = 1048576;

/* Renders into a container: a bar, a byte count, and a cancel button. */
function progressUI(container, label) {
  container.hidden = false;
  container.innerHTML = `
    <div class="uprogress">
      <div class="ubar"><div class="ufill" style="width:0%"></div></div>
      <div class="urow">
        <span class="utext">${esc(label)}</span>
        <button type="button" class="ghost ucancel">Cancel</button>
      </div>
    </div>`;
  container.querySelector('.ucancel').addEventListener('click', () => upload.current?.abort());
  const fill = container.querySelector('.ufill');
  const text = container.querySelector('.utext');
  return (loaded, total) => {
    if (loaded < 0) {
      fill.style.width = '100%';
      fill.classList.add('waiting');
      text.textContent = 'uploaded - waiting for the server to accept it\u2026';
      container.querySelector('.ucancel').hidden = true;
      return;
    }
    const pct = total ? Math.round((loaded / total) * 100) : 0;
    fill.style.width = `${pct}%`;
    text.textContent = `${(loaded / MB).toFixed(0)} of ${(total / MB).toFixed(0)} MB \u00b7 ${pct}%`;
  };
}


/* ------------------------------------------------------------------ submit */

$('#runForm').addEventListener('submit', async (e) => {
  e.preventDefault();

  const groups = $$('input[name=group]:checked').map((i) => i.value);
  if (!groups.length) { toast('Choose at least one assertion group.'); return; }

  const runId = $('#runId').value.trim();
  const storageLocation = $('#storageLocation').value.trim();

  const body = new FormData();
  body.append('file', fileInput.files[0]);
  body.append('runId', runId);
  body.append('storageLocation', storageLocation);
  body.append('groups', groups.join(','));
  body.append('writeSuccesses', $('#writeSuccesses').checked);
  body.append('rf2DeltaOnly', $('#rf2DeltaOnly').checked);
  body.append('releaseAsAnEdition', $('#releaseAsAnEdition').checked);
  body.append('standAloneProduct', $('#standAloneProduct').checked);
  body.append('enableDrools', $('#enableDrools').checked);
  body.append('enableMrcmValidation', $('#enableMrcmValidation').checked);
  body.append('failureExportMax', $('#failureExportMax').value || '10');

  const manifest = $('#manifest').files?.[0];
  if (manifest) body.append('manifest', manifest);

  // '__auto__' is resolved before submitting, so what goes on the wire is
  // always a real filename or nothing at all.
  let previous = $('#previousRelease').value;
  if (previous === '__auto__') {
    previous = (await detectPrevious(fileInput.files[0]?.name)) || '';
  }

  const optional = {
    previousRelease: previous,
    effectiveTime: $('#effectiveTime').value.trim(),
    droolsRulesGroups: $('#enableDrools').checked ? $('#droolsRulesGroups').value.trim() : '',
  };
  for (const [k, v] of Object.entries(optional)) if (v) body.append(k, v);

  const btn = $('#submitBtn');
  btn.disabled = true;
  btn.textContent = 'Uploading\u2026';

  try {
    await upload('/run-post', body, progressUI($('#submitProgress'), 'starting\u2026'));
    $('#submitProgress').hidden = true;
    toast('Validation submitted.', true);
    // The list is now stale, so make the next visit to that tab reload it.
    loadRuns.done = false;
    watch(runId, storageLocation);
    defaults();                       // so a second run cannot reuse the id
  } catch (err) {
    $('#submitProgress').hidden = true;
    toast(`Could not submit: ${err.message}`);
  } finally {
    btn.disabled = false;
    btn.textContent = 'Start validation';
  }
});

$('#openForm').addEventListener('submit', (e) => {
  e.preventDefault();
  watch($('#openRunId').value.trim(), $('#openStorage').value.trim(), true);
});

/* A report is something people link TO - from an ADO build summary, a chat
 * message, a ticket. Without this the link can only land on the console and
 * leave the reader retyping a run id and a storage location off a build log,
 * which is exactly the friction the link was meant to remove.
 *
 * Deliberately a query string rather than a fragment: the fragment is not sent
 * to the server, and these URLs get pasted into tools that rewrite or strip
 * it. Both parameters are required - a run id without its storage location
 * does not identify a report. */
function openFromLink() {
  const q = new URLSearchParams(location.search);
  const runId = (q.get('run') || '').trim();
  const storage = (q.get('storage') || '').trim();
  if (!runId || !storage) return;

  $('#openRunId').value = runId;
  $('#openStorage').value = storage;
  // Reuse the tab's own click handler so the panel is activated and the run
  // list is lazily loaded by exactly the same path as a human click.
  $$('.tab').find((t) => t.dataset.panel === 'panel-open').click();
  watch(runId, storage, true);
}

/* ----------------------------------------------------------- the run list */

let allRuns = [];

const AGO = [[86400000, 'd'], [3600000, 'h'], [60000, 'm']];
function ago(ms) {
  if (!ms) return '';
  const d = Date.now() - ms;
  if (d < 60000) return 'just now';
  for (const [unit, label] of AGO) {
    if (d >= unit) return `${Math.floor(d / unit)}${label} ago`;
  }
  return '';
}

/* How long a run has been going, from its submission instant. Distinct from
 * ago(): that answers "when did this last do something", which for a run in
 * flight is the age of the progress file and not the age of the run. A run
 * thirty minutes in that logged a phase ten seconds ago is 30m old, and the
 * console used to call it "just now". */
function since(iso) {
  if (!iso) return '';
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return '';
  const d = Math.max(0, Date.now() - t);
  const s = Math.floor(d / 1000);
  if (s < 60) return `${s}s`;
  // No seconds past a minute: the panel refreshes every ten, so a seconds
  // field would sit visibly wrong nine times out of ten.
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m`;
  const h = Math.floor(m / 60);
  if (h < 48) return `${h}h ${m % 60}m`;
  // A queue that has been stuck for days should say days. "96h 0m" is the
  // shape of a number nobody reads.
  return `${Math.floor(h / 24)}d ${h % 24}h`;
}

/* The wall clock of an instant, for "submitted at". Date, too, when it was not
 * today: a queue that has been stuck since yesterday should not read 09:31. */
function at(iso) {
  if (!iso) return '';
  const t = new Date(iso);
  if (Number.isNaN(t.getTime())) return '';
  const time = t.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  return t.toDateString() === new Date().toDateString()
    ? time
    : `${t.toLocaleDateString([], { month: 'short', day: 'numeric' })} ${time}`;
}

function statePill(state, failures) {
  if (state === 'COMPLETE') {
    return failures > 0
      ? `<span class="pill bad">${num(failures)} failed</span>`
      : '<span class="pill ok">clean</span>';
  }
  if (state === 'FAILED') return '<span class="pill bad">run failed</span>';
  if (state === 'QUEUED' || state === 'READY') return '<span class="pill warn">queued</span>';
  if (state === 'RUNNING') return '<span class="pill warn">running</span>';
  return `<span class="pill off">${esc(state || 'unknown')}</span>`;
}

async function loadRuns(opts = {}) {
  loadRuns.done = true;
  const box = $('#runList');
  // A poll must not blank the table it is refreshing.
  if (!opts.quiet) box.innerHTML = '<p class="muted">loading&hellip;</p>';
  try {
    allRuns = await api('/result?limit=200');
    drawRunning();
    drawRuns();
    scheduleRunsRefresh();
  } catch (e) {
    // A server without the listing endpoint answers 404. Say what to do rather
    // than leave the panel blank.
    box.innerHTML = /404/.test(e.message)
      ? '<p class="muted">This server does not support listing runs. Use the run id below.</p>'
      : `<p class="muted">Could not list runs: ${esc(e.message)}</p>`;
  }
}

const IN_FLIGHT = new Set(['QUEUED', 'READY', 'RUNNING']);

/* A run in flight is the thing someone is most likely to have come to see, so
 * it gets its own panel above the list, and a count on the tab so it is visible
 * from anywhere in the console. */
function drawRunning() {
  const running = allRuns.filter((r) => IN_FLIGHT.has(r.state));

  const tab = $$('.tab').find((x) => x.dataset.panel === 'panel-open');
  const label = tab.textContent.replace(/\s*\d+$/, '').trim();
  tab.innerHTML = running.length
    ? `${esc(label)}<span class="badge">${running.length}</span>`
    : esc(label);

  $('#runningCard').hidden = running.length === 0;
  if (!running.length) return;

  $('#runningNote').textContent = 'refreshing every 10 seconds';
  $('#runningList').innerHTML = running.map((r) => {
    // Two different questions, and the console used to answer only the second
    // while looking like it answered the first: how long has this been going
    // (submitted, written once when the run was enqueued) versus when did it
    // last do anything (the state and progress files' mtime).
    const elapsed = since(r.submitted);
    const age = r.submitted
      ? `<span class="jage" title="submitted ${esc(r.submitted)}">${esc(at(r.submitted))} &middot; ${esc(elapsed)}</span>`
      // Runs submitted before that stamp existed have no age to show, so this
      // says what it does know rather than passing activity off as duration.
      // .jage is already muted; `dim` is scoped to the runs table only.
      : `<span class="jage" title="this run predates the submission stamp, so only its last activity is known">last activity ${esc(ago(r.lastModified) || 'unknown')}</span>`;
    const queued = r.state !== 'RUNNING'
      // `off` is the neutral variant; a bare .pill has no background.
      ? '<span class="pill off">queued</span>'
      : '';
    return `
    <div class="job">
      <span class="pulse" aria-hidden="true"></span>
      <span class="jname">${esc(r.testFileName || r.storageLocation)}</span>
      ${queued}
      <span class="jphase">${esc(r.progress || (r.state === 'RUNNING' ? 'starting' : 'waiting for a worker'))}</span>
      ${age}
    </div>`;
  }).join('');
}

/* Poll only while something is in flight, and stop when nothing is. A console
 * left open on an idle server should not talk to it forever. */
let runsTimer = null;
function scheduleRunsRefresh() {
  clearTimeout(runsTimer);
  if (!allRuns.some((r) => IN_FLIGHT.has(r.state))) return;
  runsTimer = setTimeout(() => loadRuns({ quiet: true }), 10000);
}

/* A page of rows, not all of them. The server hands back up to 200 runs and a
 * week of nightlies plus ad-hoc submissions fills that; a table that long
 * buries the newest run under a scroll bar, which is the one a person came to
 * see. The in-flight card above is deliberately NOT paged - it is fed by the
 * whole list, so a queued run cannot hide on page three. */
const RUNS_PER_PAGE = 25;
let runsPage = 0;

function drawRuns() {
  const needle = $('#runFilter').value.trim().toLowerCase();
  const onlyFailures = $('#onlyFailures').checked;

  const rows = allRuns.filter((r) => {
    if (onlyFailures && !(r.totalFailures > 0)) return false;
    if (!needle) return true;
    return [r.storageLocation, r.testFileName, r.groups, r.runId]
      .some((v) => String(v ?? '').toLowerCase().includes(needle));
  });

  if (!rows.length) {
    $('#runList').innerHTML = `<p class="empty">${allRuns.length ? 'No run matches that filter.' : 'No runs on this server yet.'}</p>`;
    return;
  }

  // A filter that shortens the list must not leave the view on a page that no
  // longer exists, showing an empty table over a "page 4 of 2".
  const pages = Math.ceil(rows.length / RUNS_PER_PAGE);
  runsPage = Math.min(Math.max(0, runsPage), pages - 1);
  const from = runsPage * RUNS_PER_PAGE;
  const page = rows.slice(from, from + RUNS_PER_PAGE);

  $('#runList').innerHTML = `
    <table class="runs">
      <thead>
        <tr><th>when</th><th>package</th><th>groups</th><th>result</th><th>run id</th><th></th></tr>
      </thead>
      <tbody>
        ${page.map((r, i) => `
          <tr data-i="${from + i}"${r.runId ? ' class="openable" tabindex="0" role="button"' : ''}>
            <td>${esc(ago(r.lastModified))}</td>
            <td>${esc(r.testFileName || r.storageLocation)}</td>
            <td class="dim">${esc(r.groups || '')}</td>
            <td>${statePill(r.state, r.totalFailures)}${
              r.totalTestsRun ? ` <span class="dim">${num(r.totalTestsRun)} assertions</span>` : ''}</td>
            <td class="dim">${esc(r.runId ?? '')}</td>
            <td>${r.runId ? '<span class="dim">open &rarr;</span>' : ''}</td>
          </tr>`).join('')}
      </tbody>
    </table>
    ${pages > 1 ? `
    <div class="pager">
      <button type="button" class="ghost" id="runsPrev"${runsPage === 0 ? ' disabled' : ''}>&larr; Newer</button>
      <span class="muted">${from + 1}&ndash;${from + page.length} of ${rows.length}</span>
      <button type="button" class="ghost" id="runsNext"${runsPage >= pages - 1 ? ' disabled' : ''}>Older &rarr;</button>
    </div>` : ''}`;

  const open = (i) => {
    const r = rows[i];
    if (r?.runId) watch(String(r.runId), r.storageLocation, true);
  };
  $$('#runList tr.openable').forEach((tr) => {
    tr.addEventListener('click', () => open(Number(tr.dataset.i)));
    tr.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(Number(tr.dataset.i)); }
    });
  });
  const step = (by) => { runsPage += by; drawRuns(); };
  $('#runsPrev')?.addEventListener('click', () => step(-1));
  $('#runsNext')?.addEventListener('click', () => step(1));
}

/* Filtering restarts at the newest page: the row someone is looking for is not
 * on the page they happened to be on when they started typing. */
function drawRunsFromTheTop() {
  runsPage = 0;
  drawRuns();
}

$('#refreshRuns').addEventListener('click', loadRuns);
$('#runFilter').addEventListener('input', drawRunsFromTheTop);
$('#onlyFailures').addEventListener('change', drawRunsFromTheTop);

/* ------------------------------------------------------------- the polling */

let timer = null;
let started = 0;

$('#stopPolling').addEventListener('click', () => {
  clearTimeout(timer);
  $('#statusTitle').textContent = 'Stopped watching';
  $('#progress').classList.add('done');
});

function watch(runId, storageLocation, once = false) {
  clearTimeout(timer);
  started = Date.now();

  const status = $('#status');
  status.hidden = false;
  $('#report').hidden = true;
  $('#progress').classList.remove('done');
  $('#statusTitle').textContent = once ? 'Loading report' : 'Validation running';
  $('#statusMeta').innerHTML = `
    <dt>run id</dt><dd>${esc(runId)}</dd>
    <dt>storage</dt><dd>${esc(storageLocation)}</dd>`;
  status.scrollIntoView({ behavior: 'smooth', block: 'nearest' });

  const tick = async () => {
    let data;
    try {
      data = await api(`/result/${encodeURIComponent(runId)}?storageLocation=${encodeURIComponent(storageLocation)}`);
    } catch (err) {
      if (once) { toast(`Could not open that report: ${err.message}`); $('#status').hidden = true; return; }
      // A report that does not exist yet is the normal case while the worker
      // starts, so keep waiting rather than treat it as a failure.
      return schedule();
    }

    const state = data.status || (data.rvfValidationResult?.TestResult ? 'COMPLETE' : 'RUNNING');

    if (state === 'COMPLETE') {
      $('#statusTitle').textContent = 'Validation complete';
      $('#progress').classList.add('done');
      $('#statusHint').textContent = '';
      render(data, runId, storageLocation);
      return;
    }
    if (state === 'FAILED') {
      $('#statusTitle').textContent = 'Validation failed';
      $('#progress').classList.add('done');
      render(data, runId, storageLocation);
      return;
    }
    // Opened from the list and not finished yet: the status panel already says
    // so, and a toast that is contradicted five seconds later reads as an error.
    if (once) { $('#statusTitle').textContent = 'Validation running'; }
    schedule();
  };

  const schedule = () => {
    const mins = Math.floor((Date.now() - started) / 60000);
    $('#statusHint').textContent = mins < 1
      ? 'The first check can take a minute. A worker starts only when the queue is not empty.'
      : `Waiting. ${mins} minute${mins === 1 ? '' : 's'} so far. A full edition takes about 13 minutes.`;
    timer = setTimeout(tick, 5000);
  };

  tick();
}

/* -------------------------------------------------------------- rendering */

const BUCKETS = [
  ['failed', 'assertionsFailed', 'Failed'],
  ['warning', 'assertionsWarning', 'Warnings'],
  ['skipped', 'assertionsSkipped', 'Skipped'],
  ['passed', 'assertionsPassed', 'Passed'],
];

function phasePill(text) {
  const s = String(text);
  if (/disabled/i.test(s)) return '<span class="pill off">off</span>';
  const m = s.match(/Failures count:\s*(\d+)/i);
  if (m) return Number(m[1]) > 0
    ? `<span class="pill bad">${m[1]} failed</span>`
    : '<span class="pill ok">clean</span>';
  return '<span class="pill warn">see message</span>';
}

function itemHtml(item, kind) {
  const count = item.failureCount;
  // -1 is not a count. It means the assertion never finished, which is a fault
  // in the validation rather than a finding about the content.
  const badge = count === -1
    ? '<span class="pill warn">did not complete</span>'
    : (typeof count === 'number' && count > 0 ? `${num(count)} instances` : '');

  const instances = item.firstNInstances || [];
  const cols = instances.length ? Object.keys(instances[0]) : [];

  return `
  <details class="item ${kind}">
    <summary>
      <span class="t">${esc(item.assertionText || item.assertionUuid || 'unnamed assertion')}</span>
      <span class="c">${badge}</span>
    </summary>
    <div class="body">
      ${item.failureMessage ? `<div class="msg">${esc(item.failureMessage)}</div>` : ''}
      ${instances.length ? `
        <table class="inst">
          <thead><tr>${cols.map((c) => `<th>${esc(c)}</th>`).join('')}</tr></thead>
          <tbody>${instances.map((row) => `<tr>${cols.map((c) => `<td>${esc(row[c])}</td>`).join('')}</tr>`).join('')}</tbody>
        </table>
        <p class="meta">first ${instances.length} of ${count === -1 ? 'unknown' : num(count)}
          \u00b7 <a href="#" class="allfail" data-uuid="${esc(item.assertionUuid || '')}" data-testtype="${esc(item.testType || '')}" data-count="${
            count === -1 ? '' : num(count)}">all ${
            count === -1 ? '' : num(count)} as CSV</a></p>` : ''}
      ${item.assertionUuid ? `
        <details class="src" data-uuid="${esc(item.assertionUuid)}">
          <summary>What this assertion checks</summary>
          <div class="srcbody"><p class="meta">loading\u2026</p></div>
        </details>` : ''}
      <p class="meta">${esc(item.testType || '')} \u00b7 ${esc(item.testCategory || '')} \u00b7 ${esc(item.assertionUuid || '')}</p>
  </details>`;
}

/* Which group an assertion belongs to, for the report listing.
 *
 * SQL assertions carry testCategory - `amtv4`, `release-type-validation`,
 * `file-centric-validation,mdrs` and so on. It is a comma-separated token list,
 * so the FIRST token is used: it is the category the assertion is filed under,
 * and the rest are edition and scope qualifiers that would fragment the listing
 * into a group per combination.
 *
 * Drools rules and MRCM checks carry no category at all - null and "" in a real
 * report - so they are bucketed by testType instead. Without that they would
 * all collapse into one unnamed group, which on a nightly is 1,057 of 1,482
 * records. */
const TYPE_GROUPS = {
  DROOL_RULES: 'Drools rules',
  MRCM: 'MRCM',
  TRACEABILITY: 'Traceability',
  ARCHIVE_STRUCTURAL: 'Structure',
};

function groupOf(item) {
  const category = String(item.testCategory || '').trim();
  if (category) return category.split(',')[0].trim();
  return TYPE_GROUPS[item.testType] || item.testType || 'ungrouped';
}

/* Groups ordered by how much is wrong in them, then by name. Someone opening a
 * red report wants the worst group first, not alphabetical order. */
function groupRows(rows) {
  const groups = new Map();
  for (const item of rows) {
    const name = groupOf(item);
    if (!groups.has(name)) groups.set(name, { name, items: [], instances: 0 });
    const g = groups.get(name);
    g.items.push(item);
    if (typeof item.failureCount === 'number' && item.failureCount > 0) {
      g.instances += item.failureCount;
    }
  }
  return [...groups.values()].sort((a, b) =>
    b.instances - a.instances || a.name.localeCompare(b.name));
}

// Which assertion packs produced this report.
//
// Named because the image tag stopped answering it: the corpus used to be baked
// in, so the tag said which assertions ran; packs are fetched at run time and a
// tag cannot describe them. A report that cannot say is worth saying so about -
// silence there reads as "the usual ones", and the whole point of recording
// provenance is that nobody has to assume that.
function packProvenance(packs) {
  if (!Array.isArray(packs) || !packs.length) {
    return '<span class="muted">not recorded - this report predates pack '
      + 'provenance, so which assertions produced it cannot be answered</span>';
  }
  return packs.map((pack) => {
    const digest = String(pack.digest || '').replace(/^sha256:/, '').slice(0, 12);
    const bits = [];
    if (pack.version) bits.push(esc(pack.version));
    if (digest) bits.push(esc(digest));
    if (pack.assertions) bits.push(`${num(pack.assertions)} assertions`);
    return `<div><b>${esc(pack.name || '(unnamed)')}</b>`
      + (bits.length ? ` <span class="muted">${bits.join(' \u00b7 ')}</span>` : '')
      + '</div>';
  }).join('');
}

function render(data, runId, storageLocation) {
  const result = data.rvfValidationResult || {};
  const test = result.TestResult || {};
  const summary = result.reportSummary || {};
  const failures = result.failureMessages || [];
  const el = $('#report');

  const incomplete = test.totalTestsIncomplete || 0;

  el.innerHTML = `
    <div class="tiles">
      <div class="tile"><div class="n">${num(test.totalTestsRun)}</div><div class="l">assertions run</div></div>
      <div class="tile ${test.totalFailures ? 'bad' : 'ok'}"><div class="n">${num(test.totalFailures)}</div><div class="l">failures</div></div>
      <div class="tile ${test.totalWarnings ? 'warn' : ''}"><div class="n">${num(test.totalWarnings)}</div><div class="l">warnings</div></div>
      <div class="tile"><div class="n">${num(test.totalSkips)}</div><div class="l">skipped</div></div>
      <div class="tile ${incomplete ? 'warn' : ''}"><div class="n">${num(incomplete)}</div><div class="l">incomplete</div></div>
      <div class="tile"><div class="n">${num(result.totalRF2FilesLoaded)}</div><div class="l">RF2 files</div></div>
    </div>

    ${failures.length ? `<div class="card"><legend>The run reported errors</legend>
      <div class="items">${failures.map((m) => `<div class="msg">${esc(typeof m === 'string' ? m : JSON.stringify(m))}</div>`).join('')}</div></div>` : ''}

    <div class="card">
      <legend>Phases</legend>
      <div class="phases">
        ${Object.entries(summary).map(([name, text]) =>
          `<div class="phase"><b>${esc(name)}</b> ${phasePill(text)} <span class="muted">${esc(text)}</span></div>`).join('')
          || '<p class="muted">No phase summary in this report.</p>'}
      </div>
      <dl class="kv" style="margin-top:14px">
        <dt>run id</dt><dd>${esc(runId)}</dd>
        <dt>storage</dt><dd>${esc(storageLocation)}</dd>
        <dt>package</dt><dd>${esc(result.validationConfig?.testFileName || '')}</dd>
        <dt>assertions from</dt><dd>${packProvenance(test.assertionPacks)}</dd>
        <dt>started</dt><dd>${esc(result.startTime || '')}</dd>
        <dt>ended</dt><dd>${esc(result.endTime || '')}</dd>
        <dt>duration</dt><dd>${test.timeTakenInSeconds ? `${num(test.timeTakenInSeconds)} s` : ''}</dd>
      </dl>
    </div>

    <div class="filters">
      <div class="seg" id="seg">
        ${BUCKETS.map(([kind, key, label], i) =>
          `<button data-kind="${kind}" class="${i === 0 ? 'on' : ''}">${label} (${num((test[key] || []).length)})</button>`).join('')}
      </div>
      <input type="text" id="filter" placeholder="Filter by assertion text\u2026">
      <label class="check"><input type="checkbox" id="grouped" checked> Group by assertion group</label>
      <button type="button" class="ghost" id="download">Download JSON</button>
      <button type="button" class="ghost" id="downloadCsv">All failures (CSV)</button>
    </div>

    <div class="items" id="items"></div>`;

  el.hidden = false;

  let kind = 'failed';

  const draw = () => {
    const key = BUCKETS.find(([k]) => k === kind)[1];
    const needle = $('#filter').value.trim().toLowerCase();
    const rows = (test[key] || []).filter((it) =>
      !needle || String(it.assertionText || '').toLowerCase().includes(needle));

    if (!rows.length) {
      $('#items').innerHTML =
        `<p class="empty">Nothing in this category${needle ? ' for that filter' : ''}.</p>`;
      return;
    }

    if (!$('#grouped').checked) {
      $('#items').innerHTML = rows.map((it) => itemHtml(it, kind)).join('');
      return;
    }

    // Groups open by default: a collapsed report hides the thing the reader
    // came for. The count in the header is instances, not assertions, because
    // "3 assertions" and "40,000 concepts" are different questions.
    $('#items').innerHTML = groupRows(rows).map((g) => `
      <details class="grp" open>
        <summary>
          <span class="gname">${esc(g.name)}</span>
          <span class="gcount">${num(g.items.length)} assertion${g.items.length === 1 ? '' : 's'}${
            g.instances ? ` \u00b7 ${num(g.instances)} instance${g.instances === 1 ? '' : 's'}` : ''}</span>
        </summary>
        <div class="gbody">${g.items.map((it) => itemHtml(it, kind)).join('')}</div>
      </details>`).join('');
  };

  $$('#seg button').forEach((b) => b.addEventListener('click', () => {
    $$('#seg button').forEach((x) => x.classList.remove('on'));
    b.classList.add('on');
    kind = b.dataset.kind;
    draw();
  }));
  $('#filter').addEventListener('input', draw);
  $('#grouped').addEventListener('change', draw);

  // Straight to the server, not built from the report: the report only carries
  // the first N instances per assertion, which is the cap this button exists to
  // get past. A whole run can be millions of rows, so it is a navigation rather
  // than a fetch-into-memory.
  $('#downloadCsv').addEventListener('click', () => {
    const url = `${API}/result/${encodeURIComponent(runId)}/failures`
      + `?storageLocation=${encodeURIComponent(storageLocation)}&format=csv`;
    window.location.assign(url);
  });

  /* The source is fetched when the section is OPENED, once per assertion.
   *
   * A red nightly has 21 failures and a green one has 1,441 passes; fetching
   * every assertion's SQL up front would be that many requests to show text
   * nobody has asked to read. Delegated on #items because draw() replaces its
   * innerHTML on every filter keystroke, so per-element listeners would be
   * rebound constantly - and `toggle` does not bubble, hence the click. */
  const sourceCache = new Map();
  $('#items').addEventListener('click', async (e) => {
    const link = e.target.closest('a.allfail');
    if (link) {
      e.preventDefault();
      const uuid = link.dataset.uuid;
      const url = `${API}/result/${encodeURIComponent(runId)}/failures`
        + `?storageLocation=${encodeURIComponent(storageLocation)}&format=csv`
        + (uuid ? `&assertionId=${encodeURIComponent(uuid)}` : '');

      /* Asked for before it is navigated to, so an archive that cannot answer
       * says so instead of saving an empty file.
       *
       * This link was rendered for every failure and the export reads
       * failures.parquet, which until recently held SQL assertion rows and
       * nothing else - so on a Drools or MRCM failure it downloaded a header
       * line with no rows. That reads as "no failures" while the report right
       * beside it says 5,158. New runs archive every test type; a run archived
       * before that still cannot, and answers 404. */
      /* Only SQL assertions were ever certain to be in the archive, so only the
       * other two are worth a check. The check costs a second stage of
       * failures.parquet out of the job store when it passes, which is why it is
       * not done for every failure on the page. */
      if ((link.dataset.testtype || '') === 'SQL') {
        window.location.assign(url);
        return;
      }
      link.textContent = 'checking\u2026';
      try {
        const head = await fetch(url, { method: 'HEAD' });
        if (head.status === 404) {
          link.replaceWith(Object.assign(document.createElement('span'), {
            className: 'nofail',
            textContent: 'no archived rows for this assertion',
            title: 'This run archived SQL assertion failures only, so the full list '
              + 'was never kept for this one. The instances above are what RVF exported.',
          }));
          return;
        }
        window.location.assign(url);
      } catch (err) {
        // A HEAD that could not be made is not a reason to withhold the export.
        window.location.assign(url);
      } finally {
        if (link.isConnected) link.textContent = `all ${link.dataset.count || ''} as CSV`.replace('  ', ' ');
      }
      return;
    }

    const summary = e.target.closest('details.src > summary');
    if (!summary) return;
    const details = summary.parentElement;
    // The click precedes the state change, so `open` is still the old value.
    if (details.open) return;

    const uuid = details.dataset.uuid;
    const body = details.querySelector('.srcbody');
    if (sourceCache.has(uuid)) {
      body.innerHTML = sourceCache.get(uuid);
      return;
    }
    try {
      const res = await fetch(`${API}/assertions/${encodeURIComponent(uuid)}/source`);
      const json = await res.json();
      let html;
      if (res.ok) {
        const statements = json.statements || [];
        html = `
          <p class="meta">${esc(json.file || '')}${json.severity ? ` \u00b7 ${esc(json.severity)}` : ''}</p>
          ${statements.map((s) => `<pre class="sql">${esc(s)}</pre>`).join('')}
          ${statements.length ? '' : '<p class="meta">No statements recorded.</p>'}`;
      } else {
        // 404 for a Drools rule or MRCM check, 501 on the MySQL engine. Both
        // carry a reason, and showing it is the point - "not found" alone reads
        // as though the assertion itself were unknown.
        html = `<p class="meta">${esc(json.message || `No source available (${res.status}).`)}</p>`;
      }
      sourceCache.set(uuid, html);
      body.innerHTML = html;
    } catch (err) {
      body.innerHTML = `<p class="meta">Could not load the source: ${esc(String(err))}</p>`;
    }
  });

  $('#download').addEventListener('click', () => {
    const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' }));
    const a = document.createElement('a');
    a.href = url;
    a.download = `rvf-${runId}.json`;
    a.click();
    URL.revokeObjectURL(url);
  });

  draw();
  el.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

/* ------------------------------------------------------- kept releases */

let keptReleases = [];

/* Ask the server which kept release precedes this package. The rule - same
 * edition, newest effective time before it, status ignored - lives on the
 * server so the nightly and this page cannot disagree about it. */
async function detectPrevious(filename) {
  if (!filename) return '';
  try {
    const res = await fetch(`${API}/releases/previous?forFile=${encodeURIComponent(filename)}`,
      { credentials: 'same-origin' });
    if (res.status === 204) return '';        // nothing suitable kept
    if (!res.ok) return '';
    return (await res.json()).previousRelease || '';
  } catch {
    return '';
  }
}

/* Show what auto-detect would choose, so the choice is visible before the run
 * rather than discovered afterwards in the report. */
async function previewPrevious() {
  const hint = $('#previousHint');
  const name = fileInput.files?.[0]?.name;
  if ($('#previousRelease').value !== '__auto__') {
    hint.textContent = 'Kept releases are loaded from the server.';
    return;
  }
  if (!name) { hint.textContent = 'Choose a package and the match will be shown here.'; return; }
  hint.textContent = 'checking\u2026';
  const found = await detectPrevious(name);
  hint.textContent = found
    ? `will use ${found}`
    : 'no earlier release of this edition is kept - it will run as a first-time release';
}

async function loadReleases() {
  loadReleases.done = true;
  const box = $('#releaseList');
  box.innerHTML = '<p class="muted">loading&hellip;</p>';
  try {
    keptReleases = await api('/releases');
    const select = $('#previousRelease');
    const chosen = select.value;
    select.innerHTML =
      '<option value="__auto__">Detect from the package I chose</option>'
      + '<option value="">None - first-time release</option>'
      + keptReleases.map((n) => `<option value="${esc(n)}">${esc(n)}</option>`).join('');
    select.value = chosen;

    box.innerHTML = keptReleases.length
      ? `<table class="runs"><tbody>${keptReleases.map((n) =>
          `<tr><td>${esc(n)}</td></tr>`).join('')}</tbody></table>`
      : '<p class="empty">No releases kept yet. A validation with no previous release runs as a first-time release.</p>';
  } catch (e) {
    box.innerHTML = `<p class="muted">Could not list releases: ${esc(e.message)}</p>`;
  }
}

const releaseInput = $('#releaseFile');
releaseInput.addEventListener('change', () => {
  const f = releaseInput.files?.[0];
  $('#releaseDropFile').textContent = f ? `${f.name} (${(f.size / 1048576).toFixed(1)} MB)` : '';
});

$('#releaseForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const file = releaseInput.files?.[0];
  if (!file) { toast('Choose a zip first.'); return; }

  const btn = $('#releaseBtn');
  btn.disabled = true;
  btn.textContent = 'Uploading\u2026';
  try {
    const body = new FormData();
    body.append('file', file);
    // The path segments are the MySQL schema-naming convention and are ignored
    // by the DuckDB catalogue, which keeps the package under its own filename.
    // They are still required by the route, so they are derived rather than
    // asked for.
    const version = (file.name.match(/(?<!\d)(\d{8})(?:T\d{6}Z)?(?!\d)/g) || ['00000000']).pop().slice(0, 8);
    await upload(`/releases/kept/${version}`, body,
      progressUI($('#releaseProgress'), 'starting\u2026'));
    $('#releaseProgress').hidden = true;
    toast(`Kept ${file.name}`, true);
    releaseInput.value = '';
    $('#releaseDropFile').textContent = '';
    await loadReleases();
    previewPrevious();
  } catch (err) {
    $('#releaseProgress').hidden = true;
    toast(`Could not keep that release: ${err.message}`);
  } finally {
    btn.disabled = false;
    btn.textContent = 'Keep this release';
  }
});

$('#refreshReleases').addEventListener('click', loadReleases);
$('#previousRelease').addEventListener('change', previewPrevious);

/* ------------------------------------------------------------------- boot */

defaults();
loadVersion();
loadGroups();
loadReleases();
// Start-up: learn whether anything is in flight, so the tab badge is
// right without the reports tab having been opened.
loadRuns({ quiet: true });
// Last, so a deep link overrides the default panel and the generated run id
// that the calls above have just put on the form.
openFromLink();
