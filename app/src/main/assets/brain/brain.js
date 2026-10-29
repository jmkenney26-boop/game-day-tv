// Game Day TV "brain": Goal Zone and CFB Red Zone game picking, the same logic as the Chrome extension
// (nhl.js, director-core.js, cfb.js, cfb-director.js are bundled in unchanged). Instead of showing a web
// page, each decision is handed to the Android app (window.Native), which opens the app you chose.
//
// Native → brain:  Brain.start(mode, settingsJson), Brain.stop(mode), Brain.next(mode), Brain.prev(mode),
//                  Brain.result(mode, gameId, ok, why), Brain.settings(json)
// Brain → native:  Native.httpGet(url) → text, Native.tune(json), Native.idle(json), Native.status(json), Native.log(text)

const { mergeSettings, setHttp, fetchScoreboard, fetchSituation, fetchPlayState, mergeGoals, channelFor,
  LIVE_STATES, DONE_STATES, periodLabel } = __mods.nhl;
const { decide, enqueueGoals, emptyDirector, clockText, onBreak } = __mods['director-core'];
const { fetchCfbBoard, setCfbHttp, eligible, cfbClock } = __mods.cfb;
const { decideRz, detectScores, emptyRz } = __mods['cfb-director'];

const N = window.Native;
const log = (t) => { try { N.log(String(t)); } catch (e) { /* tests */ } };

async function httpJSON(url) {
  const t = N.httpGet(url);
  if (!t || t.startsWith('ERR ')) throw new Error(t ? t.slice(4) : 'no response');
  return JSON.parse(t);
}
setHttp(httpJSON);
setCfbHttp(httpJSON);

let settings = mergeSettings();
// Every network is "on": which app plays it is decided on the Android side (Sources screen).
function withAllChannels(s) {
  for (const k of Object.keys(s.channels)) s.channels[k] = { ...s.channels[k], on: true };
  s.findUnlisted = true;
  return s;
}

const teamOut = (t) => ({ abbrev: t?.abbrev ?? '', name: t?.name ?? '', place: t?.place ?? '', nick: t?.nick ?? '' });

// ---------- shared per-mode bits ----------
function makeMisses() {
  const misses = new Map();
  return (id) => {
    const now = Date.now();
    const recent = (misses.get(id) ?? []).filter((t) => now - t < 10 * 60_000);
    recent.push(now);
    misses.set(id, recent);
    return recent.length === 1 ? 15_000 : 3 * 60_000;   // a first miss is retried quickly
  };
}
function noteHist(map, id, v, now) {
  const hist = (map.get(id) ?? []).filter((h) => now - h.at < 15 * 60_000);
  if (!hist.length || hist[hist.length - 1].v !== v) hist.push({ at: now, v });
  map.set(id, hist);
}
function histAt(map, id, t, maxMs = Infinity, now = Date.now()) {
  let cur = null;
  for (const h of map.get(id) ?? []) if (h.at <= t) cur = h;
  return !!cur?.v && now - cur.at < maxMs;
}

// ================= Goal Zone (NHL) =================
const GZ = {
  on: false, busy: false, timer: null,
  st: { goals: [], games: [], date: '', primed: false, director: emptyDirector() },
  blocked: new Map(), failWhy: new Map(), blockFor: makeMisses(),
  sit: new Map(), zone: new Map(), tv: new Map(), tvChecked: new Map(), intm: new Map(), lastZoneAt: 0,
};

async function gzCycle(force = false) {
  if (!GZ.on || GZ.busy) return;
  GZ.busy = true;
  const now = Date.now();
  try {
    let board;
    try { board = await fetchScoreboard(); } catch (e) { status('gz', { error: 'NHL scores: ' + e.message }); return; }
    const live = board.games.filter((g) => LIVE_STATES.has(g.state));
    if (live.length && !live.some((g) => g.hasSituationField)) {
      await Promise.all(live.map(async (g) => {
        const c = GZ.sit.get(g.id);
        let sit = c && now - c.at < 8000 ? c.sit : null;
        if (!sit) { try { sit = await fetchSituation(g.id); GZ.sit.set(g.id, { at: now, sit }); } catch { sit = c?.sit ?? null; } }
        g.pp = sit?.pp ?? null;
        g.fourOnFour = !!sit?.fourOnFour;
      }));
    }
    for (const g of board.games) g.blocked = (GZ.blocked.get(g.id) ?? 0) > now;

    const sameDay = GZ.st.date === board.date;
    const merged = mergeGoals(sameDay ? GZ.st.goals : [], board.goals, now);
    let ds = sameDay && GZ.st.director ? GZ.st.director : emptyDirector();
    if (sameDay && GZ.st.primed) enqueueGoals(ds, merged.fresh, board.games, settings, now);
    ds.clipQueue = [];   // no goal clips on TV: live games only

    const showing = ds.program?.type === 'live' ? board.games.find((g) => g.id === ds.program.gameId) : null;
    if (showing && LIVE_STATES.has(showing.state) && now - GZ.lastZoneAt >= 5000) {
      GZ.lastZoneAt = now;
      try {
        const { zone, tv } = await fetchPlayState(showing);
        noteHist(GZ.zone, showing.id, zone, now);
        noteHist(GZ.tv, showing.id, tv, now);
      } catch { /* unknown */ }
    }
    if (settings.skipCommercials) {
      const due = live.filter((g) => !g.intermission && g.id !== showing?.id && now - (GZ.tvChecked.get(g.id) ?? 0) >= 10_000)
        .sort((a, b) => (GZ.tvChecked.get(a.id) ?? 0) - (GZ.tvChecked.get(b.id) ?? 0)).slice(0, 3);
      await Promise.all(due.map(async (g) => {
        GZ.tvChecked.set(g.id, now);
        try { noteHist(GZ.tv, g.id, (await fetchPlayState(g)).tv, now); } catch { /* unknown */ }
      }));
    }
    const delayMs = settings.streamDelaySeconds * 1000;
    const leadMs = Math.min(delayMs, Math.max(0, (settings.commercialLeadSeconds ?? 10) * 1000));
    const tailMs = Math.max(0, (settings.commercialTailSeconds ?? 25) * 1000);
    for (const g of board.games) {
      noteHist(GZ.intm, g.id, !!g.intermission, now);
      const tv = (t) => histAt(GZ.tv, g.id, t, 5 * 60_000, now);
      g.commercial = !!settings.skipCommercials && !g.intermission && (
        tv(now - delayMs + leadMs) || tv(now - delayMs) || tv(now - delayMs - tailMs)
        || histAt(GZ.intm, g.id, now - delayMs) || histAt(GZ.intm, g.id, now - delayMs - tailMs));
    }
    let zoneNow = 'unknown';
    if (showing) { let cur = null; for (const h of GZ.zone.get(showing.id) ?? []) if (h.at <= now - delayMs) cur = h; zoneNow = cur?.v ?? 'unknown'; }

    const r = decide(ds, board.games, merged.goals, settings, now, { zoneSafe: force || zoneNow !== 'zone' });
    ds = r.ds;
    GZ.st = { goals: merged.goals, games: board.games, date: board.date, primed: true, director: ds };
    if (r.action?.type === 'tune') {
      const g = board.games.find((x) => x.id === r.action.gameId);
      tune('gz', 'nhl', g, r.action.channel);
    } else if (r.action?.type === 'idle') {
      N.idle(JSON.stringify({ mode: 'gz' }));
    }
    gzStatus();
  } catch (e) {
    log('Goal Zone error: ' + (e && e.stack || e));
  } finally {
    GZ.busy = false;
  }
}

function gzStatus() {
  const ds = GZ.st.director;
  const now = Date.now();
  const onAir = ds.program?.type === 'live' ? GZ.st.games.find((g) => g.id === ds.program.gameId) : null;
  const games = GZ.st.games.map((g) => {
    const left = (GZ.blocked.get(g.id) ?? 0) - now;
    const st = LIVE_STATES.has(g.state) ? clockText(g) : DONE_STATES.has(g.state) ? 'Final'
      : (g.start ? new Date(g.start).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' }) : '');
    const why = !LIVE_STATES.has(g.state) ? '' : left > 0 ? `couldn't play it, retry in ${Math.ceil(left / 1000)}s${GZ.failWhy.get(g.id) ? ': ' + GZ.failWhy.get(g.id) : ''}`
      : g.intermission ? 'intermission' : g.commercial ? 'commercial break' : '';
    return { id: g.id, label: `${g.away.abbrev} ${g.away.score}-${g.home.score} ${g.home.abbrev}`, state: st, net: g.broadcasts.map((b) => b.network).join(', '), why, onAir: onAir?.id === g.id };
  });
  const manual = ds.manual && now < ds.manual.until;
  status('gz', { onAir: onAir ? `${onAir.away.abbrev} at ${onAir.home.abbrev}` : '', manual: !!manual, games });
}

// ================= CFB Red Zone =================
const RZ = {
  on: false, busy: false, timer: null,
  st: { games: [], date: '', primed: false, director: emptyRz() },
  blocked: new Map(), failWhy: new Map(), blockFor: makeMisses(), lastPlays: new Map(),
};
const BREAK_AFTER = /^(timeout|end period|end of half|end of quarter)/i;
const SCORE_PLAY = /touchdown|field goal good|safety|extra point|two-point|2pt/i;
function rzBreakAt(g, t) {
  let p = RZ.lastPlays.get(g.id);
  while (p && p.at > t) p = p.prev;
  if (p && p.prev && t - p.prev.at > 8 * 60_000) p.prev = null;
  const since = p ? t - p.at : 0;
  return !!p && since < 4 * 60_000 && (BREAK_AFTER.test(p.type) || (SCORE_PLAY.test(p.type) && since > 45_000));
}
function rzMarkCommercials(games, now) {
  const delayMs = (settings.streamDelaySeconds ?? 30) * 1000;
  const leadMs = Math.min(delayMs, Math.max(0, (settings.commercialLeadSeconds ?? 10) * 1000));
  const tailMs = Math.max(0, (settings.commercialTailSeconds ?? 25) * 1000);
  for (const g of games) {
    if (g.state !== 'in') { RZ.lastPlays.delete(g.id); continue; }
    const prev = RZ.lastPlays.get(g.id);
    if (g.lastPlayId && prev?.id !== g.lastPlayId) RZ.lastPlays.set(g.id, { id: g.lastPlayId, type: g.lastPlayType, at: now, prev });
    const brk = rzBreakAt(g, now - delayMs + leadMs) || rzBreakAt(g, now - delayMs) || rzBreakAt(g, now - delayMs - tailMs);
    g.commercial = brk;
    if (brk) g.break = true;
  }
}

async function rzCycle() {
  if (!RZ.on || RZ.busy) return;
  RZ.busy = true;
  const now = Date.now();
  try {
    let board;
    try { board = await fetchCfbBoard(); } catch (e) { status('rz', { error: 'College scores: ' + e.message }); return; }
    for (const g of board.games) g.blocked = (RZ.blocked.get(g.id) ?? 0) > now;
    if (settings.skipCommercials !== false) rzMarkCommercials(board.games, now);
    const sameDay = RZ.st.date === board.date;
    let ds = sameDay && RZ.st.director ? RZ.st.director : emptyRz();
    detectScores(ds, board.games, settings, now, sameDay && RZ.st.primed);
    const r = decideRz(ds, board.games, settings, now);
    ds = r.ds;
    RZ.st = { games: board.games, date: board.date, primed: true, director: ds };
    if (r.action?.type === 'tune') {
      const g = board.games.find((x) => x.id === r.action.gameId);
      tune('rz', 'cfb', g, r.action.channel);
    } else if (r.action?.type === 'idle') {
      N.idle(JSON.stringify({ mode: 'rz' }));
    }
    rzStatus();
  } catch (e) {
    log('Red Zone error: ' + (e && e.stack || e));
  } finally {
    RZ.busy = false;
  }
}
function rzStatus() {
  const ds = RZ.st.director;
  const now = Date.now();
  const onAir = ds.program?.type === 'live' ? RZ.st.games.find((g) => g.id === ds.program.gameId) : null;
  const games = RZ.st.games.filter((g) => g.state === 'in' && eligible(g)).map((g) => {
    const left = (RZ.blocked.get(g.id) ?? 0) - now;
    const why = left > 0 ? `couldn't play it, retry in ${Math.ceil(left / 1000)}s${RZ.failWhy.get(g.id) ? ': ' + RZ.failWhy.get(g.id) : ''}`
      : g.commercial ? 'commercial break' : g.break ? 'break' : '';
    return { id: g.id, label: `${g.away.abbrev} ${g.away.score}-${g.home.score} ${g.home.abbrev}`, state: cfbClock(g), net: g.broadcasts.map((b) => b.network).join(', '), why, onAir: onAir?.id === g.id };
  });
  status('rz', { onAir: onAir ? `${onAir.away.abbrev} at ${onAir.home.abbrev}` : '', manual: !!(ds.manual && now < ds.manual.until), games });
}

// ---------- to the Android side ----------
function tune(mode, sport, g, ch) {
  if (!g) return;
  const req = {
    mode, sport, gameId: String(g.id), away: teamOut(g.away), home: teamOut(g.home),
    networkKey: ch?.key ?? 'FIND', network: ch?.network ?? (g.broadcasts?.[0]?.network ?? ''),
    networks: (g.broadcasts ?? []).map((b) => b.network),
  };
  log(`${mode === 'gz' ? 'Goal Zone' : 'Red Zone'}: play ${g.away.abbrev} at ${g.home.abbrev} (${req.network || 'no network listed'})`);
  N.tune(JSON.stringify(req));
}
function status(mode, data) {
  try { N.status(JSON.stringify({ mode, at: Date.now(), ...data })); } catch (e) { /* tests */ }
}

// ---------- arrows: pick a game yourself (even one on a commercial) ----------
function pick(M, mode, step) {
  const ds = M.st.director;
  const live = mode === 'gz'
    ? M.st.games.filter((g) => LIVE_STATES.has(g.state))
    : M.st.games.filter((g) => g.state === 'in' && eligible(g));
  const list = live.sort((a, b) => String(a.start ?? '').localeCompare(String(b.start ?? '')) || String(a.id).localeCompare(String(b.id)));
  if (!list.length) return;
  const cur = ds.manual?.gameId ?? (ds.program?.type === 'live' ? ds.program.gameId : null);
  const i = list.findIndex((g) => g.id === cur);
  const next = list[((i < 0 ? (step > 0 ? -1 : 0) : i) + step + list.length) % list.length];
  ds.manual = { gameId: next.id, until: Date.now() + 3 * 60_000 };
  ds.skip = null;
  ds.waitSince = null;
}

window.Brain = {
  start(mode, settingsJson) {
    if (settingsJson) this.settings(settingsJson);
    const M = mode === 'rz' ? RZ : GZ;
    if (M.on) return;
    M.on = true;
    M.st.director.program = null;   // fresh tune
    (mode === 'rz' ? rzCycle : gzCycle)(true);
    log(`${mode === 'rz' ? 'Red Zone' : 'Goal Zone'} started`);
  },
  stop(mode) {
    const M = mode === 'rz' ? RZ : GZ;
    M.on = false;
    log(`${mode === 'rz' ? 'Red Zone' : 'Goal Zone'} stopped`);
  },
  // Called by the app every few seconds (it keeps time, so a background web view can't fall asleep).
  tick() { if (GZ.on) gzCycle(); if (RZ.on) rzCycle(); },
  next(mode) { const M = mode === 'rz' ? RZ : GZ; pick(M, mode, 1); (mode === 'rz' ? rzCycle : gzCycle)(true); },
  prev(mode) { const M = mode === 'rz' ? RZ : GZ; pick(M, mode, -1); (mode === 'rz' ? rzCycle : gzCycle)(true); },
  // The app couldn't open that game: skip it for a while (briefly the first time).
  result(mode, gameId, ok, why) {
    const M = mode === 'rz' ? RZ : GZ;
    const g = M.st.games.find((x) => String(x.id) === String(gameId));
    if (!g) return;
    if (ok) { M.failWhy.delete(g.id); return; }
    M.blocked.set(g.id, Date.now() + M.blockFor(g.id));
    M.failWhy.set(g.id, why || '');
    if (M.st.director.program?.gameId === g.id) M.st.director.program = null;
    if (M.st.director.manual?.gameId === g.id) M.st.director.manual = null;   // your pick couldn't open: back to automatic
    (mode === 'rz' ? rzCycle : gzCycle)(true);
  },
  settings(json) {
    let saved = {};
    try { saved = JSON.parse(json) || {}; } catch (e) { /* defaults */ }
    settings = withAllChannels(mergeSettings(saved));
  },
};
settings = withAllChannels(mergeSettings());
log('Brain ready');
