/**
 * sw_season.js — Traitement de saison en arrière-plan (MV3)
 * Survit à la fermeture du popup/sidebar grâce au document offscreen.
 * État persisté dans chrome.storage.local.
 */

'use strict';

// ── Compat ──────────────────────────────────────────────────────────────────
if (typeof browser === 'undefined') self.browser = chrome;

const SB_KEY      = 'season_bg_state';
const SB_OFF_URL  = chrome.runtime.getURL('/content/season_offscreen.html');
const SB_CHANNEL  = 'season_bg_v1';  // BroadcastChannel name (invisible to panel.js)
const SB_ICPT     = 'season-icpt-main';  // ID for the main interceptor content script

// In-memory state
let sbState = null;

// ── URL normalization: convert video detail pages to embed URLs ──────────────
function sbNormalizeEmbed(url) {
  if (!url) return url;
  try {
    // Sibnet detail page → embed
    const sibDet = url.match(/video\.sibnet\.ru\/video(\d+)/i);
    if (sibDet) return 'https://video.sibnet.ru/shell.php?videoid=' + sibDet[1];
    // Sibnet /v/ → shell.php
    const sibV = url.match(/video\.sibnet\.ru\/v\/([a-zA-Z0-9_-]+)/i);
    if (sibV) return 'https://video.sibnet.ru/shell.php?videoid=' + sibV[1];
    // Dood /v/ → /e/
    const doodV = url.match(/(dood\.[a-z]+)\/v\/([a-z0-9]+)/i);
    if (doodV) return url.replace('/v/', '/e/');
    // Streamtape /v/ → /e/
    const stape = url.match(/(streamtape\.[a-z]+)\/v\/([^?\s]+)/i);
    if (stape) return url.replace('/v/', '/e/');
  } catch(e) {}
  return url;
}

// ── webRequest m3u8/mpd capture (catches requests even without content scripts) ──
const sbCaptured = new Map(); // tabId → Set<url>

function sbStartCapture(tabId) {
  if (!sbCaptured.has(tabId)) sbCaptured.set(tabId, new Set());
}
function sbGetCaptured(tabId) {
  return sbCaptured.has(tabId) ? [...sbCaptured.get(tabId)] : [];
}
function sbStopCapture(tabId) { sbCaptured.delete(tabId); }

// Listen to all requests - catch m3u8/mpd for tracked tabs
chrome.webRequest.onBeforeRequest.addListener(
  (details) => {
    const { tabId, url } = details;
    if (tabId > 0 && sbCaptured.has(tabId)) {
      const lv = url.toLowerCase();
      if ((lv.includes('.m3u8') || lv.includes('.mpd')) && !lv.match(/index-v[0-9]/i)) {
        sbCaptured.get(tabId).add(url);
        console.log('[SeasonBG] webRequest captured:', url.substring(0, 80), 'tab:', tabId);
      }
    }
  },
  { urls: ['<all_urls>'], types: ['xmlhttprequest', 'media', 'other'] }
);

// ── Serde (identique à season.js) ──────────────────────────────────────────
function sbL(v) {
  const C = function(x) { if (!(this instanceof C)) return new C(x); this.value = x; };
  C.prototype.isSome   = () => true;
  C.prototype.isNone   = () => false;
  C.prototype.unwrapOr = function(d) { return this.value; };
  C.prototype.map      = function(f) { return sbL(f(this.value)); };
  return new C(v);
}
const sbZ = { isSome:()=>false, isNone:()=>true, unwrapOr:(d)=>d, map:()=>sbZ };

function sbXe(e) {
  if (!e || typeof e !== 'object') return e;
  if (e.__serde_tag === 'primitive') return e.__serde_val;
  if (e.__serde_tag === 'object')  { const t={}; for (const [k,v] of Object.entries(e.__serde_val)) t[k]=sbXe(v); return t; }
  if (e.__serde_tag === 'map')     return new Map(e.__serde_val.map(([k,v])=>[sbXe(k),sbXe(v)]));
  if (e.__serde_tag === 'set')     return new Set(e.__serde_val.map(v=>sbXe(v)));
  if (e.__serde_tag === 'url')     return new URL(e.__serde_val);
  if (e.__serde_tag === 'array')   return e.__serde_val.map(v=>sbXe(v));
  if (e.__serde_tag === 'headers') return new Headers(e.__serde_val);
  if (e.__serde_tag === 'regex')   return new RegExp(e.__serde_val[0], e.__serde_val[1]);
  if (e.__serde_tag === 'some')    return sbL(sbXe(e.__serde_val));
  if (e.__serde_tag === 'none')    return sbZ;
  if (e.__serde_tag === 'ok')      return { isOk:()=>true, isErr:()=>false, value:sbXe(e.__serde_val) };
  if (e.__serde_tag === 'err')     return { isOk:()=>false, isErr:()=>true, error:sbXe(e.__serde_val) };
  return e;
}
function sbOe(e) {
  if (typeof e==='string'||typeof e==='number'||typeof e==='boolean'||e===undefined||e===null)
    return { __serde_tag:'primitive', __serde_val:e };
  if (Array.isArray(e))   return { __serde_tag:'array',   __serde_val:e.map(sbOe) };
  if (e instanceof URL)   return { __serde_tag:'url',     __serde_val:e.href };
  if (e instanceof Headers) { const o=[]; e.forEach((v,k)=>o.push([k,v])); return { __serde_tag:'headers', __serde_val:o }; }
  if (e instanceof Set)   return { __serde_tag:'set',     __serde_val:[...e].map(sbOe) };
  if (e instanceof Map)   return { __serde_tag:'map',     __serde_val:[...e.entries()].map(([k,v])=>[sbOe(k),sbOe(v)]) };
  if (e instanceof RegExp) return { __serde_tag:'regex',  __serde_val:[e.source,e.flags] };
  if (e && typeof e.isSome==='function')
    return e.isSome() ? { __serde_tag:'some', __serde_val:sbOe(e.value) } : { __serde_tag:'none' };
  if (e && typeof e.isOk==='function')
    return e.isOk() ? { __serde_tag:'ok', __serde_val:sbOe(e.value) } : { __serde_tag:'err', __serde_val:sbOe(e.error) };
  if (typeof e==='object') { const o={}; for (const [k,v] of Object.entries(e)) o[k]=sbOe(v); return { __serde_tag:'object', __serde_val:o }; }
  return { __serde_tag:'primitive', __serde_val:String(e) };
}

// ── Smart Naming ─────────────────────────────────────────────────────────────
const SN_STRIP = ['vostfr','vfra','vfi','vost','vf','va','au','fr','sub','dub','hd','streaming','french','eng','multi'];
const SN_SKIP  = new Set(['anime-vf','anime-vostfr','anime-vost','anime','voir-series','voir-anime','voir','series','vf','vostfr','streaming','video','episodes']);
const SN_GENRE = ['drame','action','comedie','romance','fantastique','thriller','horreur','mystere','shonen','seinen','shojo','isekai','sport','aventure','historique','policier'];
const SN_SMALL = new Set(['de','du','la','le','les','un','une','des','et','ou','en','au','aux','ta','ton','sa','son','ma','mon','sur','sous','par','pour','avec','sans','dans']);

function sbSmartName(epUrl, epNum) {
  try {
    const clean = String(epUrl).replace('{N}', String(epNum || 1));
    const u     = new URL(clean);
    const parts = u.pathname.split('/').filter(Boolean);
    let animeName = '', seasonNum = null, detectedEp = epNum || null;

    const isIdSlug = (p) => /^\d+-/.test(p);
    for (const part of parts) {
      const pl = part.toLowerCase();
      // Episode
      const em = pl.match(/^episode-(\d+)(?:\.html?)?$/) || pl.match(/^(\d+)-episode(?:\.html?)?$/);
      if (em) { if (!detectedEp) detectedEp = parseInt(em[1]); continue; }
      // Season
      const sm = pl.match(/^(\d+)-saison$/) || pl.match(/^saison-(\d+)$/);
      if (sm) { seasonNum = parseInt(sm[1]); continue; }
      // Skip generic
      if (SN_SKIP.has(pl)) continue;
      if (SN_GENRE.some(g => pl.startsWith(g))) continue;
      // Skip category segments (streamdeouf: "animation-s", "drame-s", "action-s", etc.)
      if (/^[a-z]+-s$/.test(pl)) continue;
      if (part.includes('{N}') || part.includes('{n}')) continue;
      if (/^\d+$/.test(pl)) continue;
      // Named slug
      const hasId = isIdSlug(part);
      let slug = part.replace(/^\d+-/, '');
      let prev = '';
      while (slug !== prev) { prev = slug; slug = slug.replace(new RegExp('[-_](' + SN_STRIP.join('|') + ')$','i'),''); }
      if (!slug) continue;
      const words  = slug.split(/[-_]+/);
      const titled = words.map((w,i) => (i===0 || !SN_SMALL.has(w.toLowerCase())) ? w.charAt(0).toUpperCase()+w.slice(1).toLowerCase() : w.toLowerCase());
      const cand   = titled.join(' ').trim();
      // ID-prefixed slugs are always the real series name — always prefer them
      if (cand && (hasId || !animeName)) animeName = cand;
    }
    const ep   = detectedEp || epNum || 1;
    const name = animeName || 'Episode';
    const safeName = name.replace(/\.(?:html?|php|asp)$/i,'').trim() || name;
  const base = seasonNum ? (safeName + ' Saison ' + seasonNum + ' episode ' + ep) : (safeName + ' episode ' + ep);
    return { animeName, seasonNum, epNum:ep, basename:base };
  } catch(e) {
    return { animeName:'', seasonNum:null, epNum:epNum||1, basename:'episode ' + (epNum||1) };
  }
}

// ── Offscreen keepalive ──────────────────────────────────────────────────────
async function sbEnsureOffscreen() {
  try {
    const existing = await chrome.offscreen.hasDocument().catch(() => false);
    if (!existing) {
      await chrome.offscreen.createDocument({
        url: SB_OFF_URL,
        reasons: ['DOM_SCRAPING'],
        justification: 'Season keepalive timer relay'
      });
      await new Promise(r => setTimeout(r, 300));
    }
  } catch(e) { /* Already exists or not supported */ }
}
async function sbCloseOffscreen() {
  try { await chrome.offscreen.closeDocument(); } catch(e) {}
}

// ── Timer via offscreen (maintient le SW éveillé) ────────────────────────────
async function sbSleep(ms) {
  if (ms <= 0) return;
  try {
    await chrome.runtime.sendMessage({ name:'season_timer', delay:ms });
  } catch(e) {
    await new Promise(r => setTimeout(r, ms));
  }
}

// ── State: in-memory + chrome.storage.session backup ────────────────────────
// SW can be killed between detection and download click — session storage survives.
async function sbGet() {
  if (sbState) return sbState;
  // SW was restarted — restore from session storage
  try {
    const r = await chrome.storage.session.get('season_bg_state');
    if (r.season_bg_state) { sbState = r.season_bg_state; }
  } catch(e) {}
  return sbState;
}
async function sbSet(state) {
  sbState = state;
  // Persist to session so SW restart doesn't lose detected episodes
  try { await chrome.storage.session.set({ season_bg_state: state }); } catch(e) {}
}
async function sbClear() {
  sbState = null;
  try { await chrome.storage.session.remove('season_bg_state'); } catch(e) {}
}

// ── Broadcast via BroadcastChannel (panel.js never sees this) ───────────────
function sbBroadcast(data) {
  let ch;
  try {
    ch = new BroadcastChannel(SB_CHANNEL);
    ch.postMessage(data);
  } catch(e) {}
  finally { try { ch?.close(); } catch(e) {} }
}

// ── Tab helpers ───────────────────────────────────────────────────────────────
function sbWaitTabLoad(tabId) {
  return new Promise(resolve => {
    let done = false;
    const finish = () => { if (done) return; done=true; chrome.tabs.onUpdated.removeListener(listener); clearInterval(poll); resolve(); };
    const listener = (id, info) => { if (id===tabId && info.status==='complete') finish(); };
    chrome.tabs.onUpdated.addListener(listener);
    // Poll as backup (handles race condition where tab already completed)
    const poll = setInterval(async () => {
      try {
        const tab = await chrome.tabs.get(tabId);
        if (tab && tab.status === 'complete') finish();
      } catch(e) { finish(); }  // Tab removed
    }, 600);
    setTimeout(finish, 30000);
  });
}

// ── Media detection ───────────────────────────────────────────────────────────
async function sbWaitMedia(tabId, ms, minTs) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    try {
      const r = await chrome.storage.session.get('global_session_state');
      if (r.global_session_state) {
        const st    = sbXe(r.global_session_state);
        const entry = st && st.discovered && st.discovered.get(tabId);
        if (entry && entry.media) {
          const vals = entry.media instanceof Map ? [...entry.media.values()] : Object.values(entry.media);
          if (vals.length > 0) {
            if (minTs) { const ts = vals[0].discovery_timestamp_ms; if (ts && ts < minTs) { await new Promise(r=>setTimeout(r,1500)); continue; } }
            return { media:vals[0], meta:entry.meta };
          }
        }
      }
    } catch(e) {}
    await new Promise(r => setTimeout(r, 1500));
  }
  return null;
}

async function sbExtractFromPage(tabId) {
  try {
    const res = await chrome.scripting.executeScript({
      target: { tabId, allFrames: true },
      func: () => {
        const out = [], seen = new Set();
        const BAD = ['yastatic','yandex','facebook','twitter','google','googlesyndication',
                     'doubleclick','youtube','instagram','linkedin','disqus','cloudflare'];
        const isBad = u => !u || BAD.some(d => u.toLowerCase().includes(d));
        const add = u => {
          if (!u || typeof u !== 'string' || seen.has(u)) return;
          if (!u.startsWith('http') || u.match(/^(data:|blob:|about:|javascript:)/i)) return;
          if (isBad(u)) return;
          seen.add(u); out.push(u);
        };

        // 1. Video elements
        for (const v of document.querySelectorAll('video')) {
          add(v.currentSrc); add(v.src);
          for (const s of v.querySelectorAll('source')) add(s.src);
        }

        // 2. Performance resource entries (catches network requests)
        try {
          for (const e of performance.getEntriesByType('resource')) {
            const u = e.name;
            if (u.match(/\.(m3u8|mpd|mp4|webm|mkv|ts|m4v)(\?|$)/i) ||
                u.includes('/hls/') || u.includes('/dash/')) add(u);
          }
        } catch(e) {}

        // 3. Hls.js instances
        try {
          if (window.Hls && window.Hls.instances) {
            for (const h of Object.values(window.Hls.instances)) { if (h && h.url) add(h.url); }
          }
        } catch(e) {}

        // 4. Common JS variables
        for (const k of ['videoUrl','playerUrl','streamUrl','masterUrl','hlsUrl','file','src']) {
          try { if (window[k] && typeof window[k]==='string' && window[k].startsWith('http')) add(window[k]); } catch(e) {}
        }

        // 5. Raw HTML scan for embed URLs (same regexes as season.js)
        const html = document.documentElement.innerHTML;
        const embedPatterns = [
          /https?:\/\/[^\s"'<>]*video\.sibnet[^\s"'<>]*\/shell\.php[^\s"'<>]*/gi,
          /https?:\/\/[^\s"'<>]*sibnet[^\s"'<>]*\/v\/[^\s"'<>]*/gi,
          /https?:\/\/[^\s"'<>]*(?:voe\.[a-z]+)\/e\/[^\s"'<>]*/gi,
          /https?:\/\/[^\s"'<>]*(?:dood\.[a-z]+)\/(?:e|d)\/[^\s"'<>]*/gi,
          /https?:\/\/[^\s"'<>]*(?:filemoon|moonplayer)[^\s"'<>]*/gi,
          /https?:\/\/[^\s"'<>]*(?:streamwish|wishembed)[^\s"'<>]*/gi,
          /https?:\/\/[^\s"'<>]*(?:netu|hqq)\.[a-z]+[^\s"'<>]*/gi,
          /https?:\/\/[^\s"'<>]*vidmoly\.[a-z]+\/e\/[^\s"'<>]*/gi,
          /https?:\/\/[^\s"'<>]*vidoza\.[a-z]+\/[^\s"'<>]*/gi,
          /https?:\/\/[^\s"'<>]*mp4upload[^\s"'<>]*\/embed-[^\s"'<>]*/gi,
          /https?:\/\/[^\s"'<>]*streamtape[^\s"'<>]*\/[a-z]\/[^\s"'<>]*/gi,
          /https?:\/\/[^\s"'<>]*(?:upstream|uptostream)[^\s"'<>]*/gi,
          /https?:\/\/[^\s"'<>]*ultracdn[^\s"'<>]*/gi,
        ];
        for (const pat of embedPatterns) {
          let m; pat.lastIndex = 0;
          while ((m = pat.exec(html)) !== null) add(m[0].replace(/["'<>].*/, ''));
        }

        // 6. data-src / data-link attributes
        document.querySelectorAll('[data-src],[data-link],[data-file],[data-url],[data-iframe]').forEach(el => {
          for (const a of ['data-src','data-link','data-file','data-url','data-iframe']) {
            const v = el.getAttribute(a); if (v) add(v);
          }
        });

        // Only return DIRECT video/stream URLs — not embed pages
        // Embed page URLs go to sbClickServers (Phase 2) for proper detection
        const direct = out.filter(u => {
          const l = u.toLowerCase();
          return l.match(/\.(m3u8|mpd|mp4|webm|mkv|ts|m4v)(\?|$)/i) ||
                 l.includes('/hls/') || l.includes('/dash/');
        });
        return direct.length ? direct : null;
      }
    });
    if (!res) return null;
    const all = [];
    for (const fr of res) if (fr.result) for (const u of fr.result) all.push(u);
    return all.length ? all.map(u => sbNormalizeEmbed(u)) : null;
  } catch(e) { return null; }
}


async function sbScanHtmlForEmbeds(tabId) {
  try {
    const res = await chrome.scripting.executeScript({
      target: { tabId },
      func: () => {
        const out = [], seen = new Set();
        const add = u => {
          u = u && u.replace(/["'<>\s].*/, '').trim();
          if (!u || !u.startsWith('http') || seen.has(u)) return;
          seen.add(u); out.push(u);
        };
        const html = document.documentElement.innerHTML;
        const pats = [
          /https?:\/\/[^\s"'<>]*sibnet[^\s"'<>]*shell\.php[^\s"'<>]*/gi,
          /https?:\/\/[^\s"'<>]*sibnet[^\s"'<>]*\/v\/[^\s"'<>]*/gi,
          /https?:\/\/[^\s"'<>]*voe\.[a-z]+\/e\/[^\s"'<>]*/gi,
          /https?:\/\/[^\s"'<>]*dood\.[a-z]+\/(?:e|d)\/[^\s"'<>]*/gi,
          /https?:\/\/[^\s"'<>]*filemoon\.[a-z]+\/e\/[^\s"'<>]*/gi,
          /https?:\/\/[^\s"'<>]*(?:streamwish|wishembed)[^\s"'<>]*\/[a-z0-9]+/gi,
          /https?:\/\/[^\s"'<>]*netu\.[a-z]+[^\s"'<>]*/gi,
          /https?:\/\/[^\s"'<>]*hqq\.[a-z]+[^\s"'<>]*/gi,
          /https?:\/\/[^\s"'<>]*vidmoly\.[a-z]+\/e\/[^\s"'<>]*/gi,
          /https?:\/\/[^\s"'<>]*vidoza\.[a-z]+\/[a-z0-9]+/gi,
          /https?:\/\/[^\s"'<>]*mp4upload[^\s"'<>]*\/embed-[^\s"'<>]*/gi,
          /https?:\/\/[^\s"'<>]*streamtape[^\s"'<>]*\/e\/[^\s"'<>]*/gi,
          /https?:\/\/[^\s"'<>]*ultracdn[^\s"'<>]*/gi,
          /https?:\/\/[^\s"'<>]*upstream[^\s"'<>]*/gi,
        ];
        for (const pat of pats) {
          let m; pat.lastIndex = 0;
          while ((m = pat.exec(html)) !== null) add(m[0]);
        }
        // Also data-link
        document.querySelectorAll('[data-link]').forEach(el => {
          const v = el.getAttribute('data-link');
          if (v && v.startsWith('http')) add(v);
        });
        return out;
      }
    });
    const urls = (res && res[0] && res[0].result) || [];
    return urls.map(u => sbNormalizeEmbed(u));
  } catch(e) { return []; }
}

async function sbClickServers(tabId) {
  try {
    const res = await chrome.scripting.executeScript({
      target: { tabId },
      func: () => {
        const BAD = ['yastatic','yandex','facebook','twitter','google','disqus','cloudflare','adsystem','doubleclick'];
        const isBad = u => !u || BAD.some(d => u.toLowerCase().includes(d));

        const getText = el => el ? el.textContent.trim().toLowerCase() : '';
        const pr = name => {
          if (name.includes('voe'))                          return 1;
          if (name.includes('dood'))                         return 2;
          if (name.includes('filemoon'))                     return 3;
          if (name.includes('streamwish')||name.includes('wish')) return 4;
          if (name.includes('vidmoly'))                      return 5;
          if (name.includes('vidstream'))                    return 6;
          if (name.includes('netu')||name.includes('hqq'))   return 7;
          if (name.includes('vidoza'))                       return 8;
          if (name.includes('upstream')||name.includes('ultracdn')) return 9;
          if (name.includes('mp4upload'))                    return 10;
          if (name.includes('streamtape'))                   return 11;
          if (name.includes('sibnet'))                       return 20;
          return 15;
        };

        const seen = new Set();
        const servers = [];
        const addEl = (el, name, url) => {
          const key = url || name;
          if (!key || seen.has(key)) return;
          seen.add(key);
          servers.push({ el, name, url: url || null, priority: pr(name) });
        };

        // ── animesultra: [data-link] with direct embed URLs ──────────────────
        document.querySelectorAll('[data-link]').forEach(el => {
          const url = el.getAttribute('data-link') || '';
          if (url.startsWith('http') && !isBad(url)) {
            addEl(el, getText(el), url);
          }
        });

        // ── animesultra: <a class="btn [active]"> server buttons ────────────
        // These don't have data-link — they load the player via onclick JS
        document.querySelectorAll('a.btn, .server-list a, .players a, ul.nav-tabs a').forEach(el => {
          const name = getText(el);
          if (name && name.length > 1 && !name.includes('http')) {
            addEl(el, name, null);
          }
        });

        // ── Generic server containers ─────────────────────────────────────────
        document.querySelectorAll('.lien, .server-item, .server-link, .player-btn, .tab-video, [class*="server-"],[class*="-server"]').forEach(el => {
          const url = el.getAttribute('data-src') || el.getAttribute('data-url') || null;
          const name = getText(el);
          if (name) addEl(el, name, url);
        });

        // ── onclick patterns ──────────────────────────────────────────────────
        document.querySelectorAll('[onclick*="playEpisode"],[onclick*="playVideo"],[onclick*="loadPlayer"],[onclick*="changeServer"],[onclick*="change_server"]').forEach(el => {
          addEl(el, getText(el), null);
        });

        servers.sort((a, b) => a.priority - b.priority);

        // If we have direct embed URLs (data-link), return them all
        const direct = servers.filter(s => s.url);
        if (direct.length > 0) {
          return { mode: 'direct', items: direct.map(s => ({ url: s.url, server: s.name })) };
        }

        // Otherwise return clickable buttons info
        return { mode: 'click', items: servers.map(s => ({ server: s.name, priority: s.priority })) };
      }
    });

    const result = res && res[0] && res[0].result;
    if (!result) return [];

    // Direct URLs — no clicking needed
    if (result.mode === 'direct') return result.items;

    // Click mode — click each button and collect the resulting embed
    const clickItems = result.items;
    console.log('[SeasonBG] Click-mode servers:', clickItems.map(s => s.server).join(', '));
    const collected = [];

    for (let idx = 0; idx < Math.min(clickItems.length, 8); idx++) {
      try {
        const clickRes = await chrome.scripting.executeScript({
          target: { tabId },
          func: (idx) => {
            const BAD = ['yastatic','yandex','facebook','twitter','google','disqus','cloudflare'];
            const isBad = u => !u || BAD.some(d => u.toLowerCase().includes(d));

            // Find all clickable server buttons (same selectors as above)
            const btns = [
              ...document.querySelectorAll('a.btn, .server-list a, .players a, ul.nav-tabs a'),
              ...document.querySelectorAll('.lien, .server-item, .server-link, .player-btn, .tab-video'),
              ...document.querySelectorAll('[onclick*="playEpisode"],[onclick*="playVideo"],[onclick*="loadPlayer"],[onclick*="changeServer"]')
            ];
            // Deduplicate by textContent
            const seen = new Set();
            const unique = btns.filter(b => {
              const k = b.textContent.trim().toLowerCase();
              if (!k || seen.has(k)) return false;
              seen.add(k); return true;
            });

            const btn = unique[idx];
            if (!btn) return null;

            const serverName = btn.textContent.trim();
            console.log('[Season] Clicking server button:', serverName);

            // Capture current iframes before click
            const before = new Set([...document.querySelectorAll('iframe')].map(i => i.src || i.getAttribute('src') || '').filter(s => s.startsWith('http')));
            const beforeSrc = document.querySelector('iframe') ? document.querySelector('iframe').src : '';

            btn.click();

            return new Promise(resolve => {
              let attempts = 0;
              const check = setInterval(() => {
                attempts++;
                // Check for new/changed iframes
                const iframes = [...document.querySelectorAll('iframe')];
                for (const ifr of iframes) {
                  const src = ifr.src || ifr.getAttribute('src') || '';
                  if (src && src.startsWith('http') && !isBad(src) && !before.has(src)) {
                    clearInterval(check);
                    return resolve({ url: src, server: serverName });
                  }
                }
                // Also check if existing iframe src changed
                const cur = iframes[0] ? (iframes[0].src || iframes[0].getAttribute('src') || '') : '';
                if (cur && cur.startsWith('http') && !isBad(cur) && cur !== beforeSrc) {
                  clearInterval(check);
                  return resolve({ url: cur, server: serverName });
                }
                if (attempts > 12) { clearInterval(check); resolve({ url: null, server: serverName }); }
              }, 500);
            });
          },
          args: [idx]
        });

        const r = clickRes && clickRes[0] && clickRes[0].result;
        if (!r) break;
        if (r.url) {
          console.log('[SeasonBG] Server clicked:', r.server, '→', r.url.substring(0, 80));
          collected.push({ url: r.url, server: r.server });
        } else {
          console.log('[SeasonBG] Server clicked (no iframe):', r.server);
        }
        await new Promise(r2 => setTimeout(r2, 300));
      } catch(e) { break; }
    }

    return collected;
  } catch(e) {
    console.error('[SeasonBG] sbClickServers error:', e.message);
    return [];
  }
}


async function sbRegisterEmbedInterceptor(hostname) {
  const eid = 'season-icpt-embed-' + hostname.replace(/[^a-z0-9]/g, '_');
  try { await chrome.scripting.unregisterContentScripts({ ids: [eid] }); } catch(e) {}
  try {
    await chrome.scripting.registerContentScripts([{
      id: eid, matches: ['*://' + hostname + '/*'],
      js: ['content/season_interceptor.js'], runAt: 'document_start', world: 'MAIN'
    }]);
    return eid;  // Return ID so caller can track and clean up
  } catch(e) {
    return null;
  }
}


async function sbGetM3u8(tabId) {
  try {
    const r = await chrome.scripting.executeScript({ target:{tabId}, world:'MAIN', func:()=>window.__seasonCapturedM3u8||[] });
    return (r&&r[0]&&Array.isArray(r[0].result)&&r[0].result.length) ? r[0].result : [];
  } catch(e) { return []; }
}

// ── Known embed hosts for interceptor pre-registration ───────────────────────
const EMBED_HOSTS = [
  'sibnet.ru','voe.sx','dood.wf','dood.cx','dood.sh','dood.la','dood.to',
  'mystream.to','netu.tv','hqq.tv','filemoon.sx','moonplayer.xyz',
  'streamwish.to','wishembed.net','mp4upload.com','streamtape.com',
  'vidmoly.to','vidoza.net','upstream.to','ultracdn.net','ultravid.to',
];

// ── Process one episode ───────────────────────────────────────────────────────
async function sbProcessOne(epUrl, epNum, timeout) {
  const t0 = Date.now();
  let tabId = null;
  // Declared before try so the finally block can always access it safely
  const dynamicScriptIds = new Set();

  // Pre-register interceptors for common embed hosts
  // Note: when called from sbRunBatch, hosts are pre-registered at batch level.
  // This loop handles standalone calls (e.g. season_bg_detect_embed).
  for (const host of EMBED_HOSTS) {
    const id = 'season-icpt-' + host.replace(/[^a-z0-9]/g, '_');
    try {
      await chrome.scripting.registerContentScripts([{
        id, matches: ['*://' + host + '/*', '*://*.' + host + '/*'],
        js: ['content/season_interceptor.js'], runAt: 'document_start', world: 'MAIN'
      }]);
    } catch(e) { /* Already registered or not supported — ignore */ }
  }

  try {
    const tab = await chrome.tabs.create({ url: epUrl, active: true });
    tabId = tab.id;
    sbStartCapture(tabId);
    const cur = await sbGet(); if (cur) { cur.currentTabId = tabId; await sbSet(cur); }

    await sbWaitTabLoad(tabId);
    await new Promise(r => setTimeout(r, 3000));
    const st2 = await sbGet(); if (!st2 || !st2.running) return { status: 'skipped' };

    // Phase 1: Extension native detection (best quality)
    let found = await sbWaitMedia(tabId, 4000, t0);
    if (found) return { status:'success', media:found.media, meta:found.meta, embedUrl:null };

    // Phase 1b: webRequest capture
    let wr = sbGetCaptured(tabId);
    if (wr.length > 0) {
      console.log('[SeasonBG] ep', epNum, 'webRequest hit:', wr[0].substring(0,80));
      return { status:'success', media:null, meta:null, videoUrls:wr, embedUrl:null };
    }

    // Phase 1c: Page extraction — direct video/stream URLs only
    let pageUrls = await sbExtractFromPage(tabId);
    if (pageUrls && pageUrls.length) {
      console.log('[SeasonBG] ep', epNum, 'direct URL from page:', pageUrls[0].substring(0,80));
      return { status:'success', media:null, meta:null, videoUrls:pageUrls, embedUrl:null };
    }
    // Also scan HTML for embed server URLs to feed into Phase 2
    const htmlEmbeds = await sbScanHtmlForEmbeds(tabId);

    // Phase 2: Get ALL server URLs and try each one sequentially
    const allServers = await sbClickServers(tabId);
    // Prepend embeds found in HTML scan (already normalized)
    const htmlEmbedItems = (htmlEmbeds||[]).map(u => ({ url: u, server: 'html-scan' }));
    // Merge, deduplicate
    const seenUrls = new Set();
    const servers = [...htmlEmbedItems, ...allServers].filter(s => {
      if (!s.url || seenUrls.has(s.url)) return false;
      seenUrls.add(s.url); return true;
    });
    console.log('[SeasonBG] ep', epNum, 'servers found:', servers.length, servers.map(s=>s.server).join(', '));

    // Track dynamically registered embed interceptors for cleanup in finally
    // (dynamicScriptIds is declared above the try block)

    for (const srv of servers) {
      const st3 = await sbGet(); if (!st3 || !st3.running) break;

      const embedUrl = sbNormalizeEmbed(srv.url);
      if (!embedUrl || !embedUrl.startsWith('http')) continue;

      console.log('[SeasonBG] ep', epNum, 'trying server:', srv.server, '→', embedUrl.substring(0,80));

      // Register interceptor for this embed domain and track its ID
      try {
        const eu = new URL(embedUrl);
        const eid = await sbRegisterEmbedInterceptor(eu.hostname);
        if (eid) dynamicScriptIds.add(eid);
      } catch(e) {}

      // Navigate AND briefly activate so player autoloads (Vidmoly/Sibnet require visible tab)
      await chrome.tabs.update(tabId, { url: embedUrl, active: true });
      await sbWaitTabLoad(tabId);
      await new Promise(r => setTimeout(r, 4000)); // extra wait for JS player init

      // Multiple detection attempts per server
      for (let retry = 0; retry < 8; retry++) {
        const st4 = await sbGet(); if (!st4 || !st4.running) break;

        found = await sbWaitMedia(tabId, 4000, t0);
        if (found) {
          console.log('[SeasonBG] ep', epNum, 'native detection on server:', srv.server);
          try { await chrome.tabs.update(tabId, { active: false }); } catch(e) {}
          return { status:'success', media:found.media, meta:found.meta, embedUrl };
        }

        wr = sbGetCaptured(tabId);
        if (wr.length > 0) {
          console.log('[SeasonBG] ep', epNum, 'webRequest on server:', srv.server, wr[0].substring(0,80));
          try { await chrome.tabs.update(tabId, { active: false }); } catch(e) {}
          return { status:'success', media:null, meta:null, videoUrls:wr, embedUrl };
        }

        const m3 = await sbGetM3u8(tabId);
        if (m3.length > 0) {
          console.log('[SeasonBG] ep', epNum, 'm3u8 interceptor on server:', srv.server);
          try { await chrome.tabs.update(tabId, { active: false }); } catch(e) {}
          return { status:'success', media:null, meta:null, videoUrls:m3, embedUrl };
        }

        await new Promise(r => setTimeout(r, 2000));
      }

      // Last resort: page extraction on embed
      pageUrls = await sbExtractFromPage(tabId);
      if (pageUrls && pageUrls.length) {
        console.log('[SeasonBG] ep', epNum, 'page extraction on server:', srv.server);
        return { status:'success', media:null, meta:null, videoUrls:pageUrls, embedUrl };
      }

      console.log('[SeasonBG] ep', epNum, 'server failed:', srv.server, '— trying next...');
    }

    console.warn('[SeasonBG] ep', epNum, 'all', servers.length, 'servers exhausted');
    return { status: 'error' };

  } catch(e) {
    console.warn('[SeasonBG] ep', epNum, e.message);
    return { status: 'error' };
  } finally {
    if (tabId) {
      sbStopCapture(tabId);
      const st = await sbGet();
      if (st ? st.autoClose : true) { try { await chrome.tabs.remove(tabId); } catch(e){} }
      const st2 = await sbGet(); if (st2) { st2.currentTabId = null; await sbSet(st2); }
    }
    // Clean up any per-episode embed interceptor scripts
    for (const id of dynamicScriptIds) {
      try { await chrome.scripting.unregisterContentScripts({ ids: [id] }); } catch(e) {}
    }
  }
}


async function sbSendDl(msg) { return chrome.runtime.sendMessage({ msg, channel:1 }); }

function sbBuildArgs(media, meta, basename) {
  const entry = media.playlist ? media.playlist[0] : null;
  const rawUrl = entry ? (entry.av ? (entry.av.video||entry.av.audio||entry.av) : (media.url||media.master_url)) : (media.url||media.master_url);
  if (!rawUrl) return null;
  const urlObj = typeof rawUrl==='string' ? new URL(rawUrl) : rawUrl;
  const headers = media.sent_headers || new Headers();
  const clean = (basename||'video').replace(/[^\p{L}\p{N}\p{M}\-\s_.]/gu,'').substring(0,190)||'video';
  let ext='mp4', muxer='mp4', strategy='http_audio_video_one_source', jsfetch=true;
  if (media.type==='m3u8_playlist')  { ext=entry?entry.demuxer:'mp4'; muxer=ext; strategy=entry&&entry.av&&entry.av.audio?'m3u8_audio_video_two_sources':'m3u8_audio_video_one_source'; jsfetch=false; }
  else if (media.type==='m3u8')       { ext='mp4'; muxer='mp4'; strategy='m3u8_audio_video_one_source'; }
  else if (media.type==='mpd_playlist'){ ext='mp4'; muxer='mp4'; strategy='mpd_audio_video_one_source'; }
  else if (media.type==='youtube_format'){ ext=entry?entry.demuxer:'mp4'; muxer=ext; strategy=entry&&entry.av&&entry.av.audio?'youtube_audio_video_two_sources':'youtube_audio_video_one_source'; jsfetch=false; }
  if (/^html?$/i.test(ext)) { ext = 'mp4'; muxer = 'mp4'; }  // Never save as HTML
  return { download_id:'download_'+crypto.randomUUID(), headers, good_basename:clean, subdir:'', save_as:false, will_use_jsfetch:jsfetch, muxer, strategy, url:urlObj, entry:entry?entry.index:undefined, duration:media.duration, extension:ext, is_youtube:!!media.is_youtube, throttle:false, cache:media.cache||'default' };
}

async function sbDownloadAll(state) {
  if (!state || !state.detected || !state.detected.length) return 0;
  let count = 0;

  for (const ep of state.detected) {
    console.log('[SeasonBG] Downloading ep', ep.num, 'mediaSer:', !!ep.mediaSer, 'videoUrls:', ep.videoUrls);
    let launched = false;

    // ── Case A: extension-detected media (best quality, uses full pipeline) ──
    if (ep.mediaSer) {
      try {
        const media = sbXe(ep.mediaSer);
        // Restore URL objects
        if (media.url && typeof media.url === 'string') { try { media.url = new URL(media.url); } catch(e){} }
        if (media.master_url && typeof media.master_url === 'string') { try { media.master_url = new URL(media.master_url); } catch(e){} }
        // Restore headers with referer
        const h = new Headers();
        const ref = ep.embedUrl || ep.epUrl || '';
        if (ref) {
          try { const o = new URL(ref).origin; h.set('Referer', ref); h.set('Origin', o); } catch(e){}
        }
        media.sent_headers = h;

        const args = sbBuildArgs(media, null, ep.basename);
        if (args) {
          // Build meta in the exact format do_download expects (same as season.js)
          const metaSer = {
            __serde_tag: 'object',
            __serde_val: {
              tab_id:         { __serde_tag: 'primitive', __serde_val: 0 },
              url:            { __serde_tag: 'none' },
              favicon_url:    { __serde_tag: 'none' },
              incognito:      { __serde_tag: 'primitive', __serde_val: false },
              default_action: { __serde_tag: 'primitive', __serde_val: 'download_as' }
            }
          };
          await sbSendDl({ name: 'do_download', data: { download_args: sbOe(args), meta: metaSer, media: sbOe(media) } });
          await new Promise(r => setTimeout(r, 2000));
          count++;
          launched = true;
          console.log('[SeasonBG] ep', ep.num, 'launched via do_download (media)');
        }
      } catch(e) { console.warn('[SeasonBG] media dl error ep', ep.num, e.message); }
    }

    // ── Case B: raw video URLs (fallback) ────────────────────────────────────
    if (!launched && ep.videoUrls && ep.videoUrls.length) {
      for (const vUrl of ep.videoUrls) {
        if (!vUrl || typeof vUrl !== 'string') continue;
        // Skip data:/blob:/about: URLs
        if (/^(data:|blob:|about:|javascript:)/i.test(vUrl)) continue;

        const isStreaming = /\.(m3u8|mpd)(\?|$)/i.test(vUrl) || vUrl.includes('/hls/') || vUrl.includes('/dash/');
        const isVideo     = /\.(mp4|webm|mkv|ts|m4v|mov|avi|flv)(\?|$)/i.test(vUrl) || (!vUrl.match(/\.(html|css|js|png|jpg|gif|svg|ico|woff|json|xml)(\?|$)/i) && vUrl.startsWith('http'));

        if (!isVideo && !isStreaming) continue;

        try {
          if (isStreaming) {
            // Use do_download pipeline for streaming URLs
            let mediaType = 'http_playlist';
            if (vUrl.includes('.m3u8')) mediaType = 'm3u8';
            else if (vUrl.includes('.mpd')) mediaType = 'mpd_playlist';
            const urlObj = new URL(vUrl);
            const h = new Headers();
            const ref = ep.embedUrl || ep.epUrl || '';
            if (ref) { try { const o=new URL(ref).origin; h.set('Referer',ref); h.set('Origin',o); } catch(e){} }
            else { h.set('Referer', urlObj.origin+'/'); h.set('Origin', urlObj.origin); }
            const media = { url: urlObj, master_url: urlObj, type: mediaType, duration: 0, sent_headers: h, has_drm: false, is_youtube: false, cache: 'default' };
            const args  = sbBuildArgs(media, null, ep.basename);
            if (args) {
              const metaSer = { __serde_tag:'object', __serde_val:{ tab_id:{__serde_tag:'primitive',__serde_val:0}, url:{__serde_tag:'none'}, favicon_url:{__serde_tag:'none'}, incognito:{__serde_tag:'primitive',__serde_val:false}, default_action:{__serde_tag:'primitive',__serde_val:'download_as'} } };
              await sbSendDl({ name:'do_download', data:{ download_args:sbOe(args), meta:metaSer, media:sbOe(media) } });
              await new Promise(r => setTimeout(r, 2000));
              count++; launched = true;
              console.log('[SeasonBG] ep', ep.num, 'launched via do_download (streaming)');
              break;
            }
          } else {
            // Direct MP4/webm/mkv — use do_download pipeline (not chrome.downloads)
            // so custom headers (Referer/Origin) are sent and progress is tracked
            let ext = 'mp4';
            if (/\.webm/i.test(vUrl)) ext = 'webm';
            else if (/\.mkv/i.test(vUrl)) ext = 'mkv';
            else if (/\.ts/i.test(vUrl)) ext = 'ts';
            else if (/\.m4v/i.test(vUrl)) ext = 'm4v';
            const urlObj = new URL(vUrl);
            const h = new Headers();
            const ref = ep.embedUrl || ep.epUrl || '';
            if (ref) { try { const o=new URL(ref).origin; h.set('Referer',ref); h.set('Origin',o); } catch(e){} }
            else { h.set('Referer', urlObj.origin+'/'); h.set('Origin', urlObj.origin); }
            const media = { url:urlObj, master_url:urlObj, type:'http', duration:0, sent_headers:h, has_drm:false, is_youtube:false, cache:'default', extension:ext };
            const args  = sbBuildArgs(media, null, ep.basename + '.' + ext);
            if (args) {
              const metaSer = { __serde_tag:'object', __serde_val:{ tab_id:{__serde_tag:'primitive',__serde_val:0}, url:{__serde_tag:'none'}, favicon_url:{__serde_tag:'none'}, incognito:{__serde_tag:'primitive',__serde_val:false}, default_action:{__serde_tag:'primitive',__serde_val:'download_as'} } };
              await sbSendDl({ name:'do_download', data:{ download_args:sbOe(args), meta:metaSer, media:sbOe(media) } });
              await new Promise(r => setTimeout(r, 2000));
              count++; launched = true;
              console.log('[SeasonBG] ep', ep.num, 'launched via do_download (direct video)');
              break;
            }
          }
        } catch(e) { console.warn('[SeasonBG] videoUrl dl error ep', ep.num, vUrl, e.message); }
      }
    }

    if (!launched) console.warn('[SeasonBG] ep', ep.num, 'FAILED - no download method worked');
    await new Promise(r => setTimeout(r, 500));
  }

  console.log('[SeasonBG] Download all complete:', count, '/', state.detected.length);
  return count;
}

// ── Main batch loop ───────────────────────────────────────────────────────────
async function sbRunBatch(params) {
  const { pattern, start, end, delay, timeout, autoClose } = params;
  // Build initial state
  const results = [];
  for (let i=start; i<=end; i++) results.push({ num:i, status:'pending', message:'En attente…' });
  const state = { running:true, pattern, start, end, delay:delay||8, timeout:(timeout||20)*1000, autoClose:autoClose!==false, currentEp:start, currentTabId:null, results, detected:[], startTime:Date.now() };
  await sbSet(state);
  await sbBroadcast({ type:'init', state });
  await sbEnsureOffscreen();

  // Pre-register interceptors for all known embed hosts once per batch
  const _registeredHosts = new Set();
  for (const host of EMBED_HOSTS) {
    const id = 'season-icpt-' + host.replace(/[^a-z0-9]/g, '_');
    try { await chrome.scripting.unregisterContentScripts({ ids: [id] }); } catch(e) {}
    try {
      await chrome.scripting.registerContentScripts([{
        id, matches: ['*://' + host + '/*', '*://*.' + host + '/*'],
        js: ['content/season_interceptor.js'], runAt: 'document_start', world: 'MAIN'
      }]);
      _registeredHosts.add(id);
    } catch(e) {}
  }

  for (let i=start; i<=end; i++) {
    const cur = await sbGet(); if (!cur||!cur.running) break;
    const idx    = i - start;
    const epUrl  = pattern.replace('{N}', String(i));
    const nb     = sbSmartName(pattern, i);
    const basename = nb.basename;

    cur.results[idx] = { num:i, status:'loading', message:'Détection…' };
    cur.currentEp    = i;
    await sbSet(cur);
    await sbBroadcast({ type:'ep_start', num:i, idx });

    const result = await sbProcessOne(epUrl, i, cur.timeout);
    const cur2   = await sbGet(); if (!cur2||!cur2.running) break;

    if (result.status==='success' && (result.media||result.videoUrls)) {
      cur2.results[idx] = { num:i, status:'success', message:'Vidéo détectée ✓' };
      cur2.detected.push({
        num:i, basename, epUrl, embedUrl:result.embedUrl||null,
        mediaSer: result.media ? sbOe(result.media) : null,
        metaSer:  result.meta  ? sbOe(result.meta)  : null,
        videoUrls: result.videoUrls||null
      });
    } else if (result.status==='skipped') {
      cur2.results[idx] = { num:i, status:'skipped', message:'Ignoré' };
    } else {
      cur2.results[idx] = { num:i, status:'error', message:'Non détecté' };
    }
    await sbSet(cur2);
    await sbBroadcast({ type:'ep_done', num:i, idx, result:cur2.results[idx], detectedCount:cur2.detected.length });

    if (i < end) await sbSleep((cur2.delay||8)*1000);
  }

  const final = await sbGet();
  if (final) { final.running=false; await sbSet(final); await sbBroadcast({ type:'done', results:final.results, detected:final.detected }); }
  await sbCloseOffscreen();

  // Clean up all interceptor scripts registered for this batch
  for (const id of _registeredHosts) {
    try { await chrome.scripting.unregisterContentScripts({ ids: [id] }); } catch(e) {}
  }
}

async function sbStop() {
  const st = await sbGet();
  if (st) { st.running=false; await sbSet(st); }
  if (st && st.currentTabId) { try { await chrome.tabs.remove(st.currentTabId); } catch(e){} }
  await sbCloseOffscreen();
  await sbBroadcast({ type:'stopped' });
}

// ── Message handler ───────────────────────────────────────────────────────────
chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (!msg || !msg.name) return false;

  if (msg.name === 'season_bg_start') {
    sbRunBatch(msg.params).catch(e => console.error('[SeasonBG]', e));
    sendResponse({ ok:true });
    return false;
  }
  if (msg.name === 'season_bg_stop') {
    sbStop().then(() => sendResponse({ ok:true })).catch(e => sendResponse({ error:e.message }));
    return true;
  }
  if (msg.name === 'season_bg_download') {
    sbGet().then(st => sbDownloadAll(st)).then(n => sendResponse({ ok:true, count:n })).catch(e => sendResponse({ error:e.message }));
    return true;
  }
  if (msg.name === 'season_bg_get') {
    sendResponse({ state: sbState });
    return false;
  }
  if (msg.name === 'season_bg_clear') {
    (async () => {
      try { await sbStop(); } catch(e) {}
      await sbClear();
      sendResponse({ ok:true });
    })().catch(e => sendResponse({ error:e.message }));
    return true;
  }

  if (msg.name === 'season_bg_detect_embed') {
    const { embedUrl, basename, referer } = msg.params;
    (async () => {
      let tabId = null;
      try {
        const t0 = Date.now();
        const tab = await chrome.tabs.create({ url: embedUrl, active: false });
        tabId = tab.id;
        sbStartCapture(tabId);

        // Wait for page load
        await new Promise(resolve => {
          let done = false;
          const finish = () => { if(done)return; done=true; chrome.tabs.onUpdated.removeListener(l); resolve(); };
          const l = (id, info) => { if(id===tabId && info.status==='complete') finish(); };
          chrome.tabs.onUpdated.addListener(l);
          setTimeout(finish, 15000);
        });
        await new Promise(r => setTimeout(r, 2000));

        // Try native detection + webRequest capture
        let found = null, videoUrls = null;
        for (let i = 0; i < 6; i++) {
          found = await sbWaitMedia(tabId, 4000, t0);
          if (found) break;
          const wr = sbGetCaptured(tabId);
          if (wr.length > 0) { videoUrls = wr; break; }
          const m3 = await sbGetM3u8(tabId);
          if (m3.length > 0) { videoUrls = m3; break; }
          await new Promise(r => setTimeout(r, 1500));
        }
        if (!found && !videoUrls) videoUrls = await sbExtractFromPage(tabId);

        // Build and send download
        if (found && found.media) {
          const media = found.media;
          const h = new Headers();
          try { h.set('Referer', embedUrl); h.set('Origin', new URL(embedUrl).origin); } catch(e){}
          media.sent_headers = h;
          const args = sbBuildArgs(media, null, basename);
          if (args) {
            const metaSer = { __serde_tag:'object', __serde_val:{ tab_id:{__serde_tag:'primitive',__serde_val:tabId}, url:{__serde_tag:'none'}, favicon_url:{__serde_tag:'none'}, incognito:{__serde_tag:'primitive',__serde_val:false}, default_action:{__serde_tag:'primitive',__serde_val:'download_as'} } };
            await sbSendDl({ name:'do_download', data:{ download_args:sbOe(args), meta:metaSer, media:sbOe(media) } });
            sendResponse({ ok: true });
            return;
          }
        }
        if (videoUrls && videoUrls.length) {
          const vUrl = videoUrls[0];
          const lv   = vUrl.toLowerCase();
          const isStream = lv.includes('.m3u8') || lv.includes('.mpd');
          if (isStream) {
            const mtype  = lv.includes('.m3u8') ? 'm3u8' : 'mpd_playlist';
            const urlObj = new URL(vUrl);
            const h      = new Headers();
            try { h.set('Referer', embedUrl); h.set('Origin', new URL(embedUrl).origin); } catch(e){}
            const media  = { url:urlObj, master_url:urlObj, type:mtype, duration:0, sent_headers:h, has_drm:false, is_youtube:false, cache:'default' };
            const args   = sbBuildArgs(media, null, basename);
            if (args) {
              const metaSer = { __serde_tag:'object', __serde_val:{ tab_id:{__serde_tag:'primitive',__serde_val:tabId}, url:{__serde_tag:'none'}, favicon_url:{__serde_tag:'none'}, incognito:{__serde_tag:'primitive',__serde_val:false}, default_action:{__serde_tag:'primitive',__serde_val:'download_as'} } };
              await sbSendDl({ name:'do_download', data:{ download_args:sbOe(args), meta:metaSer, media:sbOe(media) } });
              sendResponse({ ok: true });
              return;
            }
          } else {
            // Direct video file — use do_download pipeline (not chrome.downloads)
            // so custom headers (Referer/Origin) are sent and progress is tracked
            let ext = 'mp4';
            if (lv.includes('.webm')) ext = 'webm';
            else if (lv.includes('.mkv')) ext = 'mkv';
            else if (lv.includes('.ts')) ext = 'ts';
            else if (lv.includes('.m4v')) ext = 'm4v';
            const urlObj = new URL(vUrl);
            const h = new Headers();
            try { h.set('Referer', embedUrl); h.set('Origin', new URL(embedUrl).origin); } catch(e){}
            const media = { url:urlObj, master_url:urlObj, type:'http', duration:0, sent_headers:h, has_drm:false, is_youtube:false, cache:'default', extension:ext };
            const args  = sbBuildArgs(media, null, basename + '.' + ext);
            if (args) {
              const metaSer = { __serde_tag:'object', __serde_val:{ tab_id:{__serde_tag:'primitive',__serde_val:tabId}, url:{__serde_tag:'none'}, favicon_url:{__serde_tag:'none'}, incognito:{__serde_tag:'primitive',__serde_val:false}, default_action:{__serde_tag:'primitive',__serde_val:'download_as'} } };
              await sbSendDl({ name:'do_download', data:{ download_args:sbOe(args), meta:metaSer, media:sbOe(media) } });
              sendResponse({ ok: true });
              return;
            }
          }
        }
        sendResponse({ ok: false });
      } catch(e) {
        console.warn('[SeasonBG] detect_embed error:', e.message);
        sendResponse({ ok: false });
      } finally {
        if (tabId) {
          sbStopCapture(tabId);
          try { await chrome.tabs.remove(tabId); } catch(e){}
        }
      }
    })();
    return true;  // async response
  }
  return false;
});

console.log('[SeasonBG] loaded');
