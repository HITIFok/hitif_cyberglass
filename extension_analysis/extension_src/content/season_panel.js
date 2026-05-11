/**
 * season_panel.js — Panneau Saison inline dans le popup/sidebar
 * Délègue tout le traitement au service worker.
 * Survit à la fermeture du popup/sidebar.
 */
'use strict';

(function() {
  if (typeof browser === 'undefined') window.browser = chrome;

  const SP_KEY = 'season_bg_state';

  // DL button restore HTML (used in 3 places — keep as a single source of truth)
  const DL_BTN_HTML  = '<svg viewBox="0 0 24 24" width="15" height="15" fill="currentColor"><path d="M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z"/></svg> Télécharger tout';
  const SPIN_SVG_BTN = '<svg class="sp-ico spin" viewBox="0 0 24 24" style="width:14px;height:14px;fill:white;animation:sp-spin 1s linear infinite"><path d="M12 2a10 10 0 0 1 10 10" fill="none" stroke="white" stroke-width="2.5" stroke-linecap="round"/></svg>';

  // ── Smart naming (côté popup, pour preview) ────────────────────────────────
  const SN_STRIP = ['vostfr','vfra','vfi','vost','vf','va','au','fr','sub','dub','hd','streaming','french','eng','multi'];
  const SN_SKIP  = new Set(['anime-vf','anime-vostfr','anime-vost','anime','voir-series','voir-anime','voir','series','vf','vostfr','streaming','video','episodes']);
  const SN_GENRE = ['drame','action','comedie','romance','fantastique','thriller','horreur','shonen','seinen','shojo','isekai','sport','aventure','historique','policier'];
  const SN_SMALL = new Set(['de','du','la','le','les','un','une','des','et','ou','en','au','aux','ta','ton','sa','son','ma','mon','sur','sous','par','pour','avec','sans','dans']);

  function spSmartName(epUrl, epNum) {
    try {
      const clean = String(epUrl).replace('{N}', String(epNum || 1));
      const u     = new URL(clean);
      const parts = u.pathname.split('/').filter(Boolean);
      let animeName = '', seasonNum = null, ep = epNum || null;

      const isIdSlug = (p) => /^\d+-/.test(p);
      for (const part of parts) {
        const pl = part.toLowerCase();
        const em = pl.match(/^episode-(\d+)(?:\.html?)?$/) || pl.match(/^(\d+)-episode(?:\.html?)?$/);
        if (em) { if (!ep) ep = parseInt(em[1]); continue; }
        const sm = pl.match(/^(\d+)-saison$/) || pl.match(/^saison-(\d+)$/);
        if (sm) { seasonNum = parseInt(sm[1]); continue; }
        if (SN_SKIP.has(pl)) continue;
        if (SN_GENRE.some(g => pl.startsWith(g))) continue;
        // Skip category segments (streamdeouf: "animation-s", "drame-s", "action-s", etc.)
        if (/^[a-z]+-s$/.test(pl)) continue;
        if (part.includes('{N}') || part.includes('{n}')) continue;
        if (/^\d+$/.test(pl)) continue;
        const hasId = isIdSlug(part);
        let slug = part.replace(/^\d+-/, '');
        let prev = '';
        while (slug !== prev) { prev = slug; slug = slug.replace(new RegExp('[-_](' + SN_STRIP.join('|') + ')$','i'),''); }
        if (!slug) continue;
        const words  = slug.split(/[-_]+/);
        const titled = words.map((w, i) => (i===0 || !SN_SMALL.has(w.toLowerCase())) ? w.charAt(0).toUpperCase() + w.slice(1).toLowerCase() : w.toLowerCase());
        const cand = titled.join(' ').trim();
        // ID-prefixed slugs are always the real series name — always prefer them
        if (cand && (hasId || !animeName)) animeName = cand;
      }
      const n    = ep || epNum || 1;
      const name = (animeName || '').replace(/\.(?:html?|php|asp)$/i, '').trim();
      const base = name ? (seasonNum ? (name + ' Saison ' + seasonNum + ' episode ' + n) : (name + ' episode ' + n)) : ('Episode ' + n);
      return { animeName: name, seasonNum, epNum:n, basename:base };
    } catch(e) {
      return { animeName:'', seasonNum:null, epNum:epNum||1, basename:'Episode '+(epNum||1) };
    }
  }

  // Detect URL pattern (replace episode number with {N})
  function spGetPattern(raw) {
    if (!raw || !raw.startsWith('http')) return raw;
    try {
      const u     = new URL(raw);
      const parts = u.pathname.split('/').filter(Boolean);
      const last  = parts[parts.length - 1];
      let p = last.replace(/^(episode-)(\d+)(\.html?)$/i, '$1{N}$3');
      if (p !== last) return u.origin + '/' + parts.slice(0,-1).join('/') + '/' + p;
      p = last.replace(/^(\d+)(-episode\.html?)$/i, '{N}$2');
      if (p !== last) return u.origin + '/' + parts.slice(0,-1).join('/') + '/' + p;
      const m = last.match(/(\d+)/);
      if (m) { p = last.replace(m[1], '{N}'); return u.origin + '/' + parts.slice(0,-1).join('/') + '/' + p; }
    } catch(e) {}
    return raw;
  }

  // ── Icons ──────────────────────────────────────────────────────────────────
  const ICONS = {
    pending:  '<svg class="sp-ico" viewBox="0 0 24 24"><circle cx="12" cy="12" r="10" fill="none" stroke="currentColor" stroke-width="2"/></svg>',
    loading:  '<svg class="sp-ico spin" viewBox="0 0 24 24"><path d="M12 2a10 10 0 0 1 10 10" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"/></svg>',
    success:  '<svg class="sp-ico ok" viewBox="0 0 24 24"><path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z"/></svg>',
    error:    '<svg class="sp-ico err" viewBox="0 0 24 24"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z"/></svg>',
    skipped:  '<svg class="sp-ico skip" viewBox="0 0 24 24"><path d="M6 6h12v12H6z" fill="none" stroke="currentColor" stroke-width="2"/></svg>',
    download: '<svg class="sp-ico ok" viewBox="0 0 24 24"><path d="M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z"/></svg>'
  };

  // ── Panel HTML ─────────────────────────────────────────────────────────────
  const CSS = `
<style>
#sp-wrap{flex:1;display:flex;flex-direction:column;overflow:hidden;font-family:system-ui,sans-serif;font-size:13px;line-height:1.5;background:light-dark(hsl(230 18% 97%),hsl(232 20% 11%));color:light-dark(hsl(220 12% 22%),hsl(220 12% 88%));}
.sp-head{display:flex;align-items:center;gap:8px;padding:9px 12px 8px;border-bottom:1px solid light-dark(hsl(230 10% 88%),hsl(225 22% 15%));background:light-dark(hsl(230 14% 98%),hsl(230 20% 7%));flex-shrink:0;}
.sp-back{display:flex;align-items:center;gap:4px;background:none;border:none;cursor:pointer;color:light-dark(hsl(250 70% 52%),hsl(250 75% 66%));font-size:12px;font-weight:600;padding:3px 8px;border-radius:5px;transition:background 100ms;}
.sp-back:hover{background:light-dark(hsl(250 80% 93%),hsl(250 38% 18%));}
.sp-back svg{fill:currentColor;flex-shrink:0;}
.sp-title{font-size:0.86rem;font-weight:700;}
.sp-sub{font-size:0.67rem;color:light-dark(hsl(220 10% 52%),hsl(220 10% 54%));}
.sp-live{display:flex;align-items:center;gap:4px;font-size:0.64rem;font-weight:700;padding:2px 7px;border-radius:10px;background:hsl(142 65% 36%);color:#fff;margin-left:auto;opacity:0;transition:opacity 250ms;letter-spacing:.03em;white-space:nowrap;}
.sp-live.on{opacity:1;}
.sp-live svg{fill:#fff;}
.sp-scroll{flex:1;overflow-y:auto;padding:10px 12px;display:flex;flex-direction:column;gap:10px;}
.sp-card{background:light-dark(hsl(230 14% 98%),hsl(230 20% 9%));border:1px solid light-dark(hsl(230 10% 89%),hsl(250 65% 50%/0.22));border-radius:10px;padding:12px;}
.sp-card-title{font-size:0.76rem;font-weight:700;margin-bottom:10px;display:flex;align-items:center;gap:5px;opacity:0.8;}
.sp-card-title svg{width:13px;height:13px;fill:currentColor;}
.sp-fg{margin-bottom:8px;}
.sp-lbl{display:block;font-size:0.7rem;font-weight:600;color:light-dark(hsl(220 10% 48%),hsl(220 10% 54%));margin-bottom:3px;}
.sp-inp{width:100%;background:light-dark(hsl(230 10% 95%),hsl(225 18% 10%));border:1.5px solid light-dark(hsl(230 10% 84%),hsl(225 22% 20%));border-radius:6px;color:inherit;padding:6px 9px;font-family:inherit;font-size:0.78rem;outline:none;box-sizing:border-box;transition:border-color 120ms;}
.sp-inp:focus{border-color:hsl(250 80% 58%);}
.sp-inp::placeholder{color:light-dark(hsl(220 8% 60%),hsl(220 8% 35%));}
.sp-inp:disabled,.sp-sel:disabled{opacity:0.45;}
.sp-prev{margin-top:4px;padding:3px 8px;background:light-dark(hsl(250 80% 96%),hsl(230 18% 6%));border:1px solid light-dark(hsl(250 50% 86%),hsl(230 25% 16%));border-radius:4px;font-size:0.67rem;color:light-dark(hsl(250 80% 44%),hsl(250 75% 66%));word-break:break-all;display:none;}
.sp-prev.on{display:block;}
.sp-row{display:flex;gap:8px;}
.sp-row>.sp-fg{flex:1;}
.sp-srow{display:flex;align-items:center;justify-content:space-between;padding:3px 0;font-size:0.74rem;}
.sp-sel{background:light-dark(hsl(230 10% 95%),hsl(225 18% 10%));border:1.5px solid light-dark(hsl(230 10% 84%),hsl(225 22% 20%));border-radius:5px;color:inherit;padding:3px 7px;font-family:inherit;font-size:0.74rem;outline:none;cursor:pointer;}
.sp-chk{display:flex;align-items:center;gap:7px;cursor:pointer;font-size:0.74rem;padding:3px 0;}
.sp-chk input[type=checkbox]{appearance:none;-webkit-appearance:none;width:14px;height:14px;border:1.5px solid light-dark(hsl(230 10% 76%),hsl(225 22% 26%));border-radius:3px;background:light-dark(hsl(230 10% 95%),hsl(225 20% 11%));cursor:pointer;position:relative;flex-shrink:0;transition:all 110ms;}
.sp-chk input:checked{background:hsl(250 80% 58%);border-color:hsl(250 80% 58%);}
.sp-chk input:checked::after{content:'';position:absolute;left:3px;top:0px;width:4px;height:7px;border:solid #fff;border-width:0 2px 2px 0;transform:rotate(45deg);}
.sp-acts{display:flex;gap:6px;justify-content:flex-end;margin-top:10px;}
/* Buttons */
.sp-btn{display:inline-flex;align-items:center;justify-content:center;gap:5px;padding:6px 12px;border-radius:6px;border:1.5px solid light-dark(hsl(230 10% 84%),hsl(225 22% 19%));background:light-dark(hsl(230 10% 95%),hsl(225 20% 11%));color:inherit;font-family:inherit;font-size:0.78rem;font-weight:500;cursor:pointer;transition:all 100ms;white-space:nowrap;}
.sp-btn:hover{background:light-dark(hsl(250 40% 92%),hsl(250 38% 18%));border-color:light-dark(hsl(250 60% 72%),hsl(250 60% 40%/0.4));}
.sp-btn:disabled{opacity:0.32;pointer-events:none;}
.sp-btn svg{width:13px;height:13px;fill:currentColor;flex-shrink:0;}
.sp-btn-primary{background:linear-gradient(135deg,hsl(250 80% 52%),hsl(268 70% 54%));border-color:hsl(250 75% 50%);color:#fff;font-weight:700;}
.sp-btn-primary:hover{background:linear-gradient(135deg,hsl(250 84% 56%),hsl(268 74% 58%));box-shadow:0 2px 10px hsl(250 80% 50%/0.26);}
.sp-btn-dl{background:linear-gradient(135deg,hsl(142 68% 35%),hsl(158 62% 40%));border-color:hsl(142 62% 32%);color:#fff;font-weight:700;font-size:0.84rem;padding:9px 14px;width:100%;border-radius:8px;}
.sp-btn-dl:hover{background:linear-gradient(135deg,hsl(142 72% 39%),hsl(158 66% 44%));box-shadow:0 3px 14px hsl(142 68% 40%/0.32);transform:translateY(-1px);}
.sp-btn-dl:active{transform:none;}
.sp-btn-dl:disabled{opacity:0.32;pointer-events:none;}
.sp-btn-stop{border-color:light-dark(hsl(0 50% 72%),hsl(0 45% 30%));color:hsl(0 72% 52%);}
.sp-btn-stop:hover{background:light-dark(hsl(0 40% 95%),hsl(0 38% 18%));}
.sp-btn-clear{border:none;background:none;color:light-dark(hsl(220 10% 50%),hsl(220 10% 44%));font-size:0.72rem;padding:3px 8px;}
.sp-btn-clear:hover{background:light-dark(hsl(0 40% 95%),hsl(0 38% 16%));color:hsl(0 72% 50%);border-color:transparent;}
/* Progress */
.sp-prog-head{display:flex;align-items:center;justify-content:space-between;margin-bottom:8px;}
.sp-prog-head .sp-card-title{margin-bottom:0;}
.sp-stats{display:flex;gap:8px;font-size:0.7rem;color:light-dark(hsl(220 10% 48%),hsl(220 10% 52%));}
.sp-stats b{color:light-dark(hsl(250 78% 50%),hsl(250 75% 66%));}
.sp-bar-wrap{height:5px;background:light-dark(hsl(230 10% 90%),hsl(225 18% 13%));border-radius:3px;overflow:hidden;margin-bottom:10px;}
.sp-bar{height:100%;background:linear-gradient(90deg,hsl(250 80% 58%),hsl(268 70% 55%));border-radius:3px;transition:width 260ms ease;width:0%;}
.sp-eplist{max-height:none;overflow-y:auto;display:flex;flex-direction:column;gap:2px;}
.sp-ep{display:flex;align-items:center;gap:7px;padding:5px 9px;border-radius:5px;border:1px solid transparent;font-size:0.72rem;background:light-dark(hsl(230 12% 96%),hsl(225 16% 6%));transition:all 140ms;}
.sp-ep.pending{border-color:light-dark(hsl(230 10% 88%),hsl(225 22% 15%));}
.sp-ep.loading{border-color:light-dark(hsl(250 48% 78%),hsl(250 45% 28%));background:light-dark(hsl(250 38% 95%),hsl(250 28% 8%));}
.sp-ep.success{border-color:light-dark(hsl(142 48% 65%),hsl(142 45% 24%));background:light-dark(hsl(142 28% 96%),hsl(142 18% 8%));}
.sp-ep.error  {border-color:light-dark(hsl(0 48% 70%),hsl(0 45% 24%));background:light-dark(hsl(0 28% 97%),hsl(0 18% 8%));}
.sp-ep.skipped{border-color:light-dark(hsl(38 45% 70%),hsl(38 45% 24%));opacity:0.65;}
.sp-ep.download{border-color:light-dark(hsl(142 48% 65%),hsl(142 45% 24%));background:light-dark(hsl(142 28% 96%),hsl(142 18% 8%));}
.sp-ep-num{font-weight:700;min-width:56px;font-size:0.69rem;flex-shrink:0;}
.sp-ep-msg{flex:1;color:light-dark(hsl(220 10% 46%),hsl(220 10% 54%));overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}
.sp-ico{width:12px;height:12px;fill:currentColor;flex-shrink:0;opacity:0.45;}
.sp-ico.ok  {fill:hsl(142 68% 36%);opacity:1;}
.sp-ico.err {fill:hsl(0 72% 50%);opacity:1;}
.sp-ico.skip{fill:hsl(38 88% 42%);opacity:1;}
.sp-ico.spin{fill:hsl(250 78% 58%);opacity:1;animation:sp-spin 1s linear infinite;}
@keyframes sp-spin{to{transform:rotate(360deg);}}
/* Download block */
.sp-dl-block{margin-top:10px;padding-top:10px;border-top:1px solid light-dark(hsl(230 10% 88%),hsl(225 22% 16%));animation:sp-in .22s ease;}
@keyframes sp-in{from{opacity:0;transform:translateY(3px);}to{opacity:1;transform:none;}}
.sp-dl-count{font-size:0.72rem;font-weight:700;text-align:center;margin-bottom:7px;color:light-dark(hsl(142 58% 30%),hsl(142 62% 48%));}
/* Banner */
.sp-banner{display:flex;align-items:center;gap:7px;padding:7px 10px;background:hsl(250 80% 58%/0.09);border:1px solid hsl(250 70% 58%/0.26);border-radius:7px;font-size:0.72rem;color:light-dark(hsl(250 78% 40%),hsl(250 75% 70%));animation:sp-in .18s ease;}
.sp-banner svg{width:12px;height:12px;fill:currentColor;flex-shrink:0;animation:sp-spin 2s linear infinite;}
/* Acts footer */
.sp-footer{display:flex;align-items:center;justify-content:space-between;margin-top:8px;}
/* Toast */
.sp-toast{position:fixed;bottom:12px;left:50%;transform:translateX(-50%);padding:5px 14px;border-radius:6px;font-size:0.74rem;color:#fff;z-index:99999;box-shadow:0 3px 12px rgba(0,0,0,.3);transition:opacity .25s;pointer-events:none;white-space:nowrap;}
.sp-toast.ok {background:hsl(142 68% 36%);}
.sp-toast.err{background:hsl(0 72% 50%);}
.sp-toast.nfo{background:hsl(250 82% 55%);}
/* Resize handle */
.sp-resizer{height:8px;cursor:ns-resize;flex-shrink:0;display:flex;align-items:center;justify-content:center;user-select:none;background:transparent;transition:background 120ms;border-radius:0 0 0 0;}
.sp-resizer::after{content:'';width:36px;height:3px;border-radius:2px;background:light-dark(hsl(230 10% 82%),hsl(225 22% 22%));transition:background 120ms,width 120ms;}
.sp-resizer:hover::after,.sp-resizer.sp-resizing::after{background:hsl(250 80% 58%);width:48px;}
</style>`;

  const PANEL_HTML = CSS + `
<div id="sp-wrap">
  <div class="sp-head">
    <button class="sp-back" id="sp-back">
      <svg width="13" height="13" viewBox="0 0 24 24"><path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"/></svg>
      Retour
    </button>
    <div>
      <div class="sp-title">Télécharger une Saison</div>
      <div class="sp-sub">Traitement en arrière-plan — survit à la fermeture</div>
    </div>
    <div class="sp-live" id="sp-live">
      <svg width="8" height="8" viewBox="0 0 24 24"><circle cx="12" cy="12" r="10"/></svg>
      EN COURS
    </div>
  </div>

  <div class="sp-scroll">

    <div class="sp-banner" id="sp-banner" style="display:none">
      <svg viewBox="0 0 24 24"><path d="M12 2a10 10 0 0 1 10 10" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"/></svg>
      <span id="sp-banner-txt">Traitement en cours en arrière-plan…</span>
    </div>

    <div class="sp-card" id="sp-cfg">
      <div class="sp-card-title">
        <svg viewBox="0 0 24 24"><path d="M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54a.484.484 0 00-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.07.62-.07.94s.02.64.07.94l-2.03 1.58a.49.49 0 00-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"/></svg>
        Configuration
      </div>
      <div class="sp-fg">
        <label class="sp-lbl">URL d'un épisode</label>
        <input type="text" id="sp-url" class="sp-inp" placeholder="Ex: https://site.com/anime/episode-1.html"/>
        <div class="sp-prev" id="sp-prev"></div>
      </div>
      <div class="sp-row">
        <div class="sp-fg">
          <label class="sp-lbl">Épisode début</label>
          <input type="number" id="sp-from" class="sp-inp" value="1" min="0"/>
        </div>
        <div class="sp-fg">
          <label class="sp-lbl">Épisode fin</label>
          <input type="number" id="sp-to" class="sp-inp" value="24" min="0"/>
        </div>
      </div>
      <div class="sp-srow">
        <span>Délai entre épisodes</span>
        <select id="sp-delay" class="sp-sel">
          <option value="5">5 s</option>
          <option value="8" selected>8 s</option>
          <option value="12">12 s</option>
          <option value="20">20 s</option>
          <option value="30">30 s</option>
        </select>
      </div>
      <div class="sp-srow">
        <span>Timeout par épisode</span>
        <select id="sp-timeout" class="sp-sel">
          <option value="15">15 s</option>
          <option value="20" selected>20 s</option>
          <option value="30">30 s</option>
          <option value="45">45 s</option>
        </select>
      </div>
      <label class="sp-chk">
        <input type="checkbox" id="sp-autoclose" checked>
        Fermer l'onglet après détection
      </label>
      <div class="sp-acts">
        <button class="sp-btn" id="sp-test">
          <svg viewBox="0 0 24 24"><path d="M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zM12 17c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z"/></svg>
          Tester URL
        </button>
        <button class="sp-btn sp-btn-primary" id="sp-run">
          <svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg>
          Traitement
        </button>
      </div>
    </div>

    <div class="sp-card" id="sp-prog" style="display:none">
      <div class="sp-prog-head">
        <div class="sp-card-title">
          <svg viewBox="0 0 24 24"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z"/></svg>
          Progression
        </div>
        <div class="sp-stats">
          <span><b id="sp-done">0</b>/<span id="sp-total">0</span></span>
          <span>OK:<b id="sp-ok">0</b></span>
          <span>Err:<b id="sp-err">0</b></span>
        </div>
      </div>
      <div class="sp-bar-wrap"><div class="sp-bar" id="sp-bar"></div></div>
      <div class="sp-eplist" id="sp-eplist"></div>

      <div class="sp-dl-block" id="sp-dl-block" style="display:none">
        <div class="sp-dl-count" id="sp-dl-count"></div>
        <button class="sp-btn sp-btn-dl" id="sp-dl">
          <svg viewBox="0 0 24 24" width="15" height="15" fill="currentColor"><path d="M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z"/></svg>
          Télécharger tout
        </button>
      </div>

      <div class="sp-footer">
        <button class="sp-btn sp-btn-clear" id="sp-clear">
          <svg viewBox="0 0 24 24" width="11" height="11" fill="currentColor" style="margin-right:2px"><path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z"/></svg>
          Effacer
        </button>
        <button class="sp-btn sp-btn-stop" id="sp-stop">
          <svg viewBox="0 0 24 24"><path d="M6 6h12v12H6z"/></svg>
          Arrêter
        </button>
      </div>
    </div>

  </div>
  <div class="sp-resizer" id="sp-resizer" title="Redimensionner le panneau"></div>
</div>`;

  // ── Panel show/hide ────────────────────────────────────────────────────────
  window.showSeasonPanel = function() {
    const main  = document.getElementById('main');
    const panel = document.getElementById('sp-host');
    if (main)  main.hidden = true;
    if (panel) { panel.hidden = false; panel.style.display = 'flex'; }
    spRestoreHeight();
    spLoadState();
    spListen(true);
  };

  window.hideSeasonPanel = function() {
    const main  = document.getElementById('main');
    const panel = document.getElementById('sp-host');
    if (main)  main.hidden = false;
    if (panel) { panel.hidden = true; panel.style.display = 'none'; }
    spListen(false);
  };

  // ── BroadcastChannel listener (panel.js never sees this channel) ─────────
  const SP_CHANNEL = 'season_bg_v1';
  let spBC = null;
  function spListen(on) {
    if (on && !spBC) {
      spBC = new BroadcastChannel(SP_CHANNEL);
      spBC.onmessage = (e) => { if (e.data) spOnProgress(e.data); };
    } else if (!on && spBC) {
      spBC.close();
      spBC = null;
    }
  }

  // ── Init ───────────────────────────────────────────────────────────────────
  function spInit() {
    const host = document.getElementById('sp-host');
    if (!host) return;
    host.innerHTML = PANEL_HTML;
    host.querySelector('#sp-back').addEventListener('click', window.hideSeasonPanel);
    host.querySelector('#sp-run').addEventListener('click', spRun);
    host.querySelector('#sp-test').addEventListener('click', spTest);
    host.querySelector('#sp-stop').addEventListener('click', spStop);
    host.querySelector('#sp-dl').addEventListener('click', spDownload);
    host.querySelector('#sp-clear').addEventListener('click', spClear);
    host.querySelector('#sp-url').addEventListener('input', spPreview);
    host.querySelector('#sp-from').addEventListener('input', spPreview);
    spInitResizer(host);
  }

  // ── Panel resize (drag handle at bottom) ──────────────────────────────────
  const SP_HEIGHT_KEY     = 'season_panel_height';
  const SP_HEIGHT_MIN     = 220;
  const SP_HEIGHT_MAX     = 900;
  const SP_HEIGHT_DEFAULT = 460;

  function spApplyHeight(host, h) {
    host.style.height   = h + 'px';
    host.style.minHeight = h + 'px';
    host.style.maxHeight = h + 'px';
  }

  function spInitResizer(host) {
    const resizer = host.querySelector('#sp-resizer');
    if (!resizer) return;

    // Restore saved height from session storage
    browser.storage.session.get(SP_HEIGHT_KEY).then(r => {
      const saved = r && r[SP_HEIGHT_KEY];
      spApplyHeight(host, (saved >= SP_HEIGHT_MIN && saved <= SP_HEIGHT_MAX) ? saved : SP_HEIGHT_DEFAULT);
    }).catch(() => { spApplyHeight(host, SP_HEIGHT_DEFAULT); });

    let startY = 0, startH = 0, dragging = false;

    resizer.addEventListener('mousedown', e => {
      e.preventDefault();
      dragging = true;
      startY = e.clientY;
      startH = host.offsetHeight || SP_HEIGHT_DEFAULT;
      resizer.classList.add('sp-resizing');
      document.body.style.cursor     = 'ns-resize';
      document.body.style.userSelect = 'none';
    });

    // Use capture so we always receive events even if a child stops propagation
    document.addEventListener('mousemove', e => {
      if (!dragging) return;
      const newH = Math.min(SP_HEIGHT_MAX, Math.max(SP_HEIGHT_MIN, startH + (e.clientY - startY)));
      spApplyHeight(host, newH);
    }, { capture: true });

    document.addEventListener('mouseup', () => {
      if (!dragging) return;
      dragging = false;
      resizer.classList.remove('sp-resizing');
      document.body.style.cursor     = '';
      document.body.style.userSelect = '';
      const finalH = host.offsetHeight;
      browser.storage.session.set({ [SP_HEIGHT_KEY]: finalH }).catch(() => {});
    }, { capture: true });
  }

  // ── Restore height each time the panel becomes visible ────────────────────
  function spRestoreHeight() {
    const host = document.getElementById('sp-host');
    if (!host) return;
    browser.storage.session.get(SP_HEIGHT_KEY).then(r => {
      const saved = r && r[SP_HEIGHT_KEY];
      spApplyHeight(host, (saved >= SP_HEIGHT_MIN && saved <= SP_HEIGHT_MAX) ? saved : SP_HEIGHT_DEFAULT);
    }).catch(() => { spApplyHeight(host, SP_HEIGHT_DEFAULT); });
  }
  function spToast(msg, type) {
    const t = document.createElement('div');
    t.className = 'sp-toast ' + (type || 'nfo');
    t.textContent = msg;
    document.body.appendChild(t);
    setTimeout(() => { t.style.opacity = '0'; }, 1900);
    setTimeout(() => t.remove(), 2200);
  }

  // ── Preview ────────────────────────────────────────────────────────────────
  function spPreview() {
    const url  = document.getElementById('sp-url');
    const prev = document.getElementById('sp-prev');
    const from = document.getElementById('sp-from');
    if (!url || !prev) return;
    const raw = url.value.trim();
    const n   = parseInt(from.value) || 1;
    if (!raw) { prev.classList.remove('on'); return; }
    const nb = spSmartName(spGetPattern(raw), n);
    if (nb.animeName || nb.basename) { prev.textContent = '📁 ' + nb.basename; prev.classList.add('on'); }
    else prev.classList.remove('on');
  }

  // ── Test URL ───────────────────────────────────────────────────────────────
  function spTest() {
    const raw = document.getElementById('sp-url').value.trim();
    const pat = spGetPattern(raw);
    if (!pat || !pat.includes('{N}')) { spToast("Impossible de détecter le pattern. Collez une URL d'épisode.", 'err'); return; }
    const n = parseInt(document.getElementById('sp-from').value) || 1;
    browser.tabs.create({ url: pat.replace('{N}', String(n)), active: true });
    spToast('Épisode ' + n + ' ouvert dans un onglet', 'nfo');
  }

  // ── Load state from storage and rebuild UI ─────────────────────────────────
  async function spLoadState() {
    try {
      // Ask SW for current state
      const r = await browser.runtime.sendMessage({ name:'season_bg_get' });
      if (r && r.state) { spRender(r.state); return; }
    } catch(e) {}
    // SW might have been killed — read from session storage directly
    try {
      const r = await browser.storage.session.get('season_bg_state');
      if (r && r.season_bg_state) spRender(r.season_bg_state);
    } catch(e) {}
  }

  // Partial render: only update stats + controls (called on storage changes)
  function spRenderPartial(st) {
    if (!st || !st.results) return;
    spUpdateStats(st.results);
    const running = !!st.running;
    const run  = document.getElementById('sp-run');
    const test = document.getElementById('sp-test');
    const stop = document.getElementById('sp-stop');
    const live = document.getElementById('sp-live');
    if (run)  run.disabled  = running;
    if (test) test.disabled = running;
    if (stop) stop.disabled = !running;
    if (live) live.classList.toggle('on', running);
    const banner = document.getElementById('sp-banner');
    const banTxt = document.getElementById('sp-banner-txt');
    if (banner) banner.style.display = running ? '' : 'none';
    if (banTxt && running) banTxt.textContent = 'Traitement en arrière-plan — épisode ' + (st.currentEp||st.start) + '/' + st.end + ' en cours…';
    if (st.detected && st.detected.length) {
      const dlBlock = document.getElementById('sp-dl-block');
      const dlCount = document.getElementById('sp-dl-count');
      if (dlBlock) dlBlock.style.display = '';
      if (dlCount) dlCount.textContent = st.detected.length + ' épisode' + (st.detected.length>1?'s':'') + ' prêt' + (st.detected.length>1?'s':'')+' à télécharger';
    }
  }

  function spRender(st) {
    if (!st || !st.results || !st.results.length) return;
    // Show progress card
    const prog = document.getElementById('sp-prog');
    if (prog) prog.style.display = '';

    // Pre-fill URL if empty
    if (st.pattern) {
      const urlEl = document.getElementById('sp-url');
      if (urlEl && !urlEl.value) urlEl.value = st.pattern;
    }

    // Build episode list
    const list = document.getElementById('sp-eplist');
    if (list) {
      list.innerHTML = '';
      for (const ep of st.results) {
        const nb  = spSmartName(st.pattern || '{N}', ep.num);
        const label = nb.animeName ? nb.basename : ('Épisode ' + ep.num);
        const div = document.createElement('div');
        div.id = 'sp-ep-' + ep.num;
        div.className = 'sp-ep ' + ep.status;
        div.innerHTML = '<span class="sp-ep-num">' + label + '</span><span class="sp-ep-msg">' + ep.message + '</span>' + (ICONS[ep.status] || ICONS.pending);
        list.appendChild(div);
      }
    }

    // Stats & bar
    spUpdateStats(st.results);

    // Controls
    const running = !!st.running;
    const run  = document.getElementById('sp-run');
    const test = document.getElementById('sp-test');
    const stop = document.getElementById('sp-stop');
    const live = document.getElementById('sp-live');
    if (run)  run.disabled  = running;
    if (test) test.disabled = running;
    if (stop) stop.disabled = !running;
    if (live) live.classList.toggle('on', running);

    // Banner
    const banner = document.getElementById('sp-banner');
    const banTxt = document.getElementById('sp-banner-txt');
    if (banner) banner.style.display = running ? '' : 'none';
    if (banTxt && running) banTxt.textContent = 'Traitement en arrière-plan — épisode ' + (st.currentEp || st.start) + '/' + st.end + ' en cours…';

    // Download block
    if (st.detected && st.detected.length) {
      const dlBlock = document.getElementById('sp-dl-block');
      const dlCount = document.getElementById('sp-dl-count');
      if (dlBlock) dlBlock.style.display = '';
      if (dlCount) dlCount.textContent = st.detected.length + ' épisode' + (st.detected.length > 1 ? 's' : '') + ' prêt' + (st.detected.length > 1 ? 's' : '') + ' à télécharger';
    }

    // Scroll to current
    const cur = document.getElementById('sp-ep-' + st.currentEp);
    if (cur) cur.scrollIntoView({ behavior:'smooth', block:'nearest' });
  }

  function spUpdateStats(results) {
    const total   = results.length;
    const done    = results.filter(e => e.status !== 'pending' && e.status !== 'loading').length;
    const success = results.filter(e => e.status === 'success' || e.status === 'download').length;
    const error   = results.filter(e => e.status === 'error').length;
    const doneEl  = document.getElementById('sp-done');
    const totEl   = document.getElementById('sp-total');
    const okEl    = document.getElementById('sp-ok');
    const errEl   = document.getElementById('sp-err');
    const barEl   = document.getElementById('sp-bar');
    if (doneEl) doneEl.textContent = String(done);
    if (totEl)  totEl.textContent  = String(total);
    if (okEl)   okEl.textContent   = String(success);
    if (errEl)  errEl.textContent  = String(error);
    if (barEl)  barEl.style.width  = total > 0 ? (done / total * 100) + '%' : '0%';
  }

  // ── Realtime progress from SW ──────────────────────────────────────────────
  function spOnProgress(data) {
    if (!data) return;
    const type = data.type;

    if (type === 'init') { spRender(data.state); return; }

    if (type === 'ep_start') {
      const el = document.getElementById('sp-ep-' + data.num);
      if (el) {
        el.className = 'sp-ep loading';
        el.querySelector('.sp-ep-msg').textContent = 'Détection…';
        const ico = el.querySelector('.sp-ico'); if (ico) ico.remove();
        el.insertAdjacentHTML('beforeend', ICONS.loading);
        el.scrollIntoView({ behavior:'smooth', block:'nearest' });
      }
      const banner = document.getElementById('sp-banner');
      const banTxt = document.getElementById('sp-banner-txt');
      if (banner) banner.style.display = '';
      if (banTxt) banTxt.textContent = 'Traitement en arrière-plan — épisode ' + data.num + ' en cours…';
      const live = document.getElementById('sp-live');
      if (live) live.classList.add('on');
      return;
    }

    if (type === 'ep_done') {
      const ep  = data.result;
      const el  = document.getElementById('sp-ep-' + data.num);
      if (el && ep) {
        el.className = 'sp-ep ' + ep.status;
        el.querySelector('.sp-ep-msg').textContent = ep.message;
        const ico = el.querySelector('.sp-ico'); if (ico) ico.remove();
        el.insertAdjacentHTML('beforeend', ICONS[ep.status] || ICONS.pending);
      }
      if (data.detectedCount > 0) {
        const dlBlock = document.getElementById('sp-dl-block');
        const dlCount = document.getElementById('sp-dl-count');
        if (dlBlock) dlBlock.style.display = '';
        if (dlCount) dlCount.textContent = data.detectedCount + ' épisode' + (data.detectedCount > 1 ? 's' : '') + ' prêt' + (data.detectedCount > 1 ? 's' : '') + ' à télécharger';
      }
      spLoadState().catch(() => {});
      return;
    }

    if (type === 'done') {
      spResetRunningState();
      const ok = (data.results||[]).filter(e => e.status==='success'||e.status==='download').length;
      const er = (data.results||[]).filter(e => e.status==='error').length;
      spToast('Terminé — ' + ok + ' détecté' + (ok>1?'s':'') + ', ' + er + ' erreur' + (er>1?'s':''), ok>0?'ok':'err');
      spLoadState().catch(() => {});
      return;
    }

    if (type === 'stopped') {
      spResetRunningState();
      spToast('Arrêté', 'nfo');
    }
  }

  // ── Reset controls to idle state (called on done + stopped) ───────────────
  function spResetRunningState() {
    const live = document.getElementById('sp-live');
    if (live) live.classList.remove('on');
    const banner = document.getElementById('sp-banner');
    if (banner) banner.style.display = 'none';
    const run  = document.getElementById('sp-run');
    const test = document.getElementById('sp-test');
    const stop = document.getElementById('sp-stop');
    if (run)  run.disabled  = false;
    if (test) test.disabled = false;
    if (stop) stop.disabled = true;
  }

  // ── Start ──────────────────────────────────────────────────────────────────
  async function spRun() {
    const raw  = document.getElementById('sp-url').value.trim();
    const pat  = spGetPattern(raw);
    if (!pat || !pat.includes('{N}')) { spToast("Impossible de détecter le pattern d'URL.", 'err'); return; }
    const from     = parseInt(document.getElementById('sp-from').value) || 1;
    const to       = parseInt(document.getElementById('sp-to').value) || 1;
    const delay    = parseInt(document.getElementById('sp-delay').value) || 8;
    const timeout  = parseInt(document.getElementById('sp-timeout').value) || 20;
    const autoClose= document.getElementById('sp-autoclose').checked;
    if (to < from) { spToast("L'épisode de fin doit être ≥ au début", 'err'); return; }
    if (to - from > 200) { spToast('Maximum 200 épisodes à la fois', 'err'); return; }

    // Init UI immediately
    document.getElementById('sp-prog').style.display = '';
    document.getElementById('sp-dl-block').style.display = 'none';
    document.getElementById('sp-run').disabled  = true;
    document.getElementById('sp-test').disabled = true;
    document.getElementById('sp-stop').disabled = false;
    document.getElementById('sp-live').classList.add('on');

    const list = document.getElementById('sp-eplist');
    list.innerHTML = '';
    for (let i=from; i<=to; i++) {
      const nb  = spSmartName(pat, i);
      const label = nb.animeName ? nb.basename : ('Épisode ' + i);
      const div = document.createElement('div');
      div.id = 'sp-ep-' + i;
      div.className = 'sp-ep pending';
      div.innerHTML = '<span class="sp-ep-num">' + label + '</span><span class="sp-ep-msg">En attente…</span>' + ICONS.pending;
      list.appendChild(div);
    }
    spUpdateStats(Array.from({length:to-from+1}, (_,j)=>({num:from+j,status:'pending'})));

    const banner = document.getElementById('sp-banner');
    const banTxt = document.getElementById('sp-banner-txt');
    banner.style.display = '';
    banTxt.textContent = 'Démarrage — vous pouvez fermer ce panneau sans interrompre le traitement';

    try {
      await browser.runtime.sendMessage({ name:'season_bg_start', params:{ pattern:pat, start:from, end:to, delay, timeout, autoClose } });
      spToast('Traitement lancé (' + (to-from+1) + ' épisodes) — continue en arrière-plan', 'ok');
    } catch(e) {
      spToast('Erreur au lancement — réessayez', 'err');
      document.getElementById('sp-run').disabled = false;
      document.getElementById('sp-live').classList.remove('on');
    }
  }

  // ── Stop ───────────────────────────────────────────────────────────────────
  function spStop() {
    browser.runtime.sendMessage({ name:'season_bg_stop' }).catch(() => {});
    document.getElementById('sp-stop').disabled = true;
    spToast('Arrêt en cours…', 'nfo');
  }


  // ── Serde helpers (identiques à season.js) ──────────────────────────────
  function spXe(e) {
    if (!e || typeof e !== 'object') return e;
    if (e.__serde_tag === 'primitive') return e.__serde_val;
    if (e.__serde_tag === 'object')  { const t={}; for (const [k,v] of Object.entries(e.__serde_val)) t[k]=spXe(v); return t; }
    if (e.__serde_tag === 'map')     return new Map(e.__serde_val.map(([k,v])=>[spXe(k),spXe(v)]));
    if (e.__serde_tag === 'set')     return new Set(e.__serde_val.map(v=>spXe(v)));
    if (e.__serde_tag === 'url')     try { return new URL(e.__serde_val); } catch(e2) { return e.__serde_val; }
    if (e.__serde_tag === 'array')   return e.__serde_val.map(v=>spXe(v));
    if (e.__serde_tag === 'headers') return new Headers(e.__serde_val);
    if (e.__serde_tag === 'regex')   return new RegExp(e.__serde_val[0], e.__serde_val[1]);
    if (e.__serde_tag === 'some')    { const L=function(v){this.value=v;}; L.prototype.isSome=()=>true; L.prototype.isNone=()=>false; L.prototype.unwrapOr=function(d){return this.value;}; return new L(spXe(e.__serde_val)); }
    if (e.__serde_tag === 'none')    return { isSome:()=>false, isNone:()=>true, unwrapOr:(d)=>d };
    if (e.__serde_tag === 'ok')      return { isOk:()=>true,  isErr:()=>false, value:spXe(e.__serde_val) };
    if (e.__serde_tag === 'err')     return { isOk:()=>false, isErr:()=>true,  error:spXe(e.__serde_val) };
    return e;
  }
  function spOe(e) {
    if (typeof e==='string'||typeof e==='number'||typeof e==='boolean'||e===undefined||e===null) return { __serde_tag:'primitive', __serde_val:e };
    if (Array.isArray(e))    return { __serde_tag:'array',   __serde_val:e.map(spOe) };
    if (e instanceof URL)    return { __serde_tag:'url',     __serde_val:e.href };
    if (e instanceof Headers){ const o=[]; e.forEach((v,k)=>o.push([k,v])); return { __serde_tag:'headers', __serde_val:o }; }
    if (e instanceof Set)    return { __serde_tag:'set',     __serde_val:[...e].map(spOe) };
    if (e instanceof Map)    return { __serde_tag:'map',     __serde_val:[...e.entries()].map(([k,v])=>[spOe(k),spOe(v)]) };
    if (e instanceof RegExp) return { __serde_tag:'regex',   __serde_val:[e.source,e.flags] };
    if (e && typeof e.isSome==='function') return e.isSome() ? { __serde_tag:'some', __serde_val:spOe(e.value) } : { __serde_tag:'none' };
    if (e && typeof e.isOk==='function')  return e.isOk()   ? { __serde_tag:'ok',   __serde_val:spOe(e.value) } : { __serde_tag:'err', __serde_val:spOe(e.error) };
    if (typeof e==='object') { const o={}; for (const [k,v] of Object.entries(e)) o[k]=spOe(v); return { __serde_tag:'object', __serde_val:o }; }
    return { __serde_tag:'primitive', __serde_val:String(e) };
  }

  // ── buildDownloadArgs (identique à season.js) ────────────────────────────
  function spBuildArgs(media, basename) {
    const entry  = media.playlist ? media.playlist[0] : null;
    const rawUrl = entry ? (entry.av ? (entry.av.video||entry.av.audio||entry.av) : (media.url||media.master_url)) : (media.url||media.master_url);
    if (!rawUrl) return null;
    const urlObj  = typeof rawUrl === 'string' ? new URL(rawUrl) : rawUrl;
    const headers = media.sent_headers || new Headers();
    // Strip .html/.htm suffix (in case a URL leaked in), then sanitize for filesystem
    const clean = String(basename||'video').replace(/\.html?$/i,'').trim()
                    .replace(/[^\p{L}\p{N}\p{M}\-\s_.]/gu,'').substring(0,190) || 'video';
    let ext='mp4', muxer='mp4', strategy='http_audio_video_one_source', jsfetch=true;
    if (media.type==='m3u8_playlist')   { ext=entry?entry.demuxer:'mp4'; muxer=ext; strategy=entry&&entry.av&&entry.av.audio?'m3u8_audio_video_two_sources':'m3u8_audio_video_one_source'; jsfetch=false; }
    else if (media.type==='m3u8')        { ext='mp4'; muxer='mp4'; strategy='m3u8_audio_video_one_source'; }
    else if (media.type==='mpd_playlist'){ ext='mp4'; muxer='mp4'; strategy='mpd_audio_video_one_source'; }
    else if (media.type==='http_playlist'){ if(entry){ext=media.extension||entry.demuxer||'mp4';muxer=ext;} strategy='http_audio_video_one_source'; }
    if (/^html?$/i.test(ext)) { ext='mp4'; muxer='mp4'; }  // Never download as HTML
    else if (media.type==='youtube_format'){ ext=entry?entry.demuxer:'mp4'; muxer=ext; strategy=entry&&entry.av&&entry.av.audio?'youtube_audio_video_two_sources':'youtube_audio_video_one_source'; jsfetch=false; }
    return { download_id:'download_'+crypto.randomUUID(), headers, good_basename:clean, subdir:'', save_as:false, will_use_jsfetch:jsfetch, muxer, strategy, url:urlObj, entry:entry?entry.index:undefined, duration:media.duration, extension:ext, is_youtube:!!media.is_youtube, throttle:false, cache:media.cache||'default' };
  }

  // ── Send do_download with a real tab_id (avoids smartnaming_rule crash) ──
  // Opens about:blank, gets real tab_id → main.js can resolve smartnaming_rule
  async function spDoDownloadWithTab(args, meta, mediaSer) {
    let tabId = null;
    try {
      const tab = await browser.tabs.create({ url: 'about:blank', active: false });
      tabId = tab.id;
      // Small wait for tab to be registered
      await new Promise(r => setTimeout(r, 150));
      // Build meta with real tab_id
      const realMeta = {
        __serde_tag: 'object',
        __serde_val: {
          tab_id:         { __serde_tag:'primitive', __serde_val: tabId },
          url:            { __serde_tag:'none' },
          favicon_url:    { __serde_tag:'none' },
          incognito:      { __serde_tag:'primitive', __serde_val:false },
          default_action: { __serde_tag:'primitive', __serde_val:'download_as' }
        }
      };
      await browser.runtime.sendMessage({ msg:{ name:'do_download', data:{ download_args:args, meta:realMeta, media:mediaSer } }, channel:1 });
      // Wait briefly for main.js to register the download, then close the tab
      await new Promise(r => setTimeout(r, 800));
    } finally {
      if (tabId !== null) { try { await browser.tabs.remove(tabId); } catch(e){} }
    }
  }


  // ── Convert video detail page URLs to embed URLs ──────────────────────────
  function spNormalizeEmbed(url) {
    if (!url) return url;
    try {
      const sibDet = url.match(/video\.sibnet\.ru\/video(\d+)/i);
      if (sibDet) return 'https://video.sibnet.ru/shell.php?videoid=' + sibDet[1];
      const sibV   = url.match(/video\.sibnet\.ru\/v\/([a-zA-Z0-9_-]+)/i);
      if (sibV)   return 'https://video.sibnet.ru/shell.php?videoid=' + sibV[1];
      const doodV  = url.match(/dood\.[a-z]+\/v\/([a-z0-9]+)/i);
      if (doodV)  return url.replace('/v/', '/e/');
      const stape  = url.match(/streamtape\.[a-z]+\/v\/([^?\s]+)/i);
      if (stape)  return url.replace('/v/', '/e/');
    } catch(e) {}
    return url;
  }

  // ── Download one episode ──────────────────────────────────────────────────
  async function spDownloadOne(ep) {
    const ref = ep.embedUrl || ep.epUrl || '';

    // A: Extension-detected media object
    if (ep.mediaSer) {
      try {
        const media = spXe(ep.mediaSer);
        const fixUrl = v => { if (v && typeof v==='string') { try { return new URL(v); } catch(e){} } return v; };
        media.url        = fixUrl(media.url);
        media.master_url = fixUrl(media.master_url);
        if (Array.isArray(media.playlist)) {
          for (const ent of media.playlist) {
            if (ent && ent.av) {
              if (typeof ent.av === 'string') ent.av = fixUrl(ent.av);
              else { ent.av.video = fixUrl(ent.av.video); ent.av.audio = fixUrl(ent.av.audio); }
            }
          }
        }
        const h = new Headers();
        if (ref) { try { h.set('Referer',ref); h.set('Origin',new URL(ref).origin); } catch(e){} }
        media.sent_headers = h;
        const args = spBuildArgs(media, ep.basename);
        if (args) {
          await spDoDownloadWithTab(spOe(args), null, spOe(media));
          console.log('[SeasonPanel] ep', ep.num, '→ do_download (media)');
          return true;
        }
      } catch(e) { console.warn('[SeasonPanel] ep', ep.num, 'media error:', e.message); }
    }

    // B: Raw video/stream URLs
    if (ep.videoUrls && ep.videoUrls.length) {
      for (let vUrl of ep.videoUrls) {
        if (!vUrl || typeof vUrl !== 'string' || !vUrl.startsWith('http')) continue;
        // Convert detail pages to embed URLs before processing
        vUrl = spNormalizeEmbed(vUrl);
        const lv      = vUrl.toLowerCase();
        const isM3u8  = lv.includes('.m3u8');
        const isMpd   = lv.includes('.mpd');
        const isHls   = lv.includes('/hls/') || lv.includes('/dash/');
        const isMp4   = /\.(mp4|webm|mkv|ts|m4v|mov)(\?|$)/.test(lv);
        const isStream= isM3u8 || isMpd || isHls;
        // Skip obvious webpage URLs in Case B — they go to Case C (openAndDetect)
        const isWebpage = !isStream && !isMp4 && (
          lv.endsWith('/') || lv.includes('.html') || lv.includes('.php') ||
          !lv.match(/\.(m3u8|mpd|mp4|webm|mkv|ts|m4v|mov|avi|flv)(\?|$)/i)
        );
        if (isWebpage) continue;
        try {
          if (isStream) {
            const mtype  = isM3u8 ? 'm3u8' : isMpd ? 'mpd_playlist' : 'http_playlist';
            const urlObj = new URL(vUrl);
            const h      = new Headers();
            if (ref) { try { h.set('Referer',ref); h.set('Origin',new URL(ref).origin); } catch(e){} }
            else     { h.set('Referer',urlObj.origin+'/'); h.set('Origin',urlObj.origin); }
            const media = { url:urlObj, master_url:urlObj, type:mtype, duration:0, sent_headers:h, has_drm:false, is_youtube:false, cache:'default' };
            const args  = spBuildArgs(media, ep.basename);
            if (args) {
              await spDoDownloadWithTab(spOe(args), null, spOe(media));
              console.log('[SeasonPanel] ep', ep.num, '→ do_download (stream)');
              return true;
            }
          } else if (isMp4) {
            // Direct video file — use do_download pipeline (not chrome.downloads)
            // so custom headers (Referer/Origin) are sent and progress is tracked
            let ext = 'mp4';
            if (/\.webm/i.test(lv)) ext = 'webm';
            else if (/\.mkv/i.test(lv)) ext = 'mkv';
            else if (/\.ts/i.test(lv)) ext = 'ts';
            else if (/\.m4v/i.test(lv)) ext = 'm4v';
            const urlObj = new URL(vUrl);
            const h = new Headers();
            if (ref) { try { h.set('Referer',ref); h.set('Origin',new URL(ref).origin); } catch(e){} }
            else { h.set('Referer',urlObj.origin+'/'); h.set('Origin',urlObj.origin); }
            const media = { url:urlObj, master_url:urlObj, type:'http', duration:0, sent_headers:h, has_drm:false, is_youtube:false, cache:'default', extension:ext };
            const args  = spBuildArgs(media, ep.basename + '.' + ext);
            if (args) {
              await spDoDownloadWithTab(spOe(args), null, spOe(media));
              console.log('[SeasonPanel] ep', ep.num, '→ do_download (direct video)');
              return true;
            }
          }
        } catch(e) { console.warn('[SeasonPanel] ep', ep.num, vUrl, e.message); }
      }
    }

    // C: videoUrls are embed pages (not direct video) — open and let extension detect
    const allUrls = ep.videoUrls || [];
    // Normalize all URLs (convert detail pages to embed URLs) then collect unique embed URLs
    const embedPageUrls = [...new Set(allUrls
      .map(u => spNormalizeEmbed(u))
      .filter(u => {
        if (!u || !u.startsWith('http')) return false;
        const lv = u.toLowerCase();
        // Skip direct video files — handled in Case B
        if (/\.(m3u8|mpd|mp4|webm|mkv|ts|m4v|mov)(\?|$)/.test(lv)) return false;
        if (lv.includes('/hls/') || lv.includes('/dash/')) return false;
        return true; // Everything else is an embed page to try
      })
    )];

    // Also try the embedUrl stored during detection (normalized)
    const normEmbedUrl = spNormalizeEmbed(ep.embedUrl);
    if (normEmbedUrl && !embedPageUrls.includes(normEmbedUrl)) {
      embedPageUrls.unshift(normEmbedUrl);
    }

    for (const embedPage of embedPageUrls.slice(0, 3)) {
      console.log('[SeasonPanel] ep', ep.num, '→ opening embed page:', embedPage.substring(0, 80));
      try {
        const result = await spOpenAndDetect(embedPage, ep.basename, ep.epUrl || '');
        if (result) {
          console.log('[SeasonPanel] ep', ep.num, '→ embed page detection succeeded');
          return true;
        }
      } catch(e) { console.warn('[SeasonPanel] ep', ep.num, 'embed page error:', e.message); }
    }

    console.error('[SeasonPanel] ep', ep.num, 'FAILED — mediaSer:', !!ep.mediaSer, '| videoUrls:', ep.videoUrls, '| embedUrls tried:', embedPageUrls.length);
    return false;
  }

  // ── Open embed page via SW, detect & download ──────────────────────────────
  async function spOpenAndDetect(embedUrl, basename, referer) {
    try {
      // Ask SW to process this embed URL and return detected media
      const r = await browser.runtime.sendMessage({
        name: 'season_bg_detect_embed',
        params: { embedUrl, basename, referer }
      });
      if (r && r.ok) return true;
      return false;
    } catch(e) {
      console.warn('[SeasonPanel] spOpenAndDetect error:', e.message);
      return false;
    }
  }
  // ── Download all ───────────────────────────────────────────────────────────
  async function spDownload() {
    const btn = document.getElementById('sp-dl');
    btn.disabled = true;
    btn.innerHTML = SPIN_SVG_BTN + ' Téléchargement…';

    try {
      let st = null;
      try {
        const r = await browser.runtime.sendMessage({ name:'season_bg_get' });
        st = r && r.state;
      } catch(e) {}
      // Fallback: read from session storage if SW was restarted
      if (!st || !st.detected || !st.detected.length) {
        try {
          const r2 = await browser.storage.session.get('season_bg_state');
          if (r2 && r2.season_bg_state && r2.season_bg_state.detected && r2.season_bg_state.detected.length) {
            st = r2.season_bg_state;
          }
        } catch(e) {}
      }
      if (!st || !st.detected || !st.detected.length) {
        spToast('Aucun épisode à télécharger', 'err');
        btn.disabled = false;
        btn.innerHTML = DL_BTN_HTML;
        return;
      }

      let count = 0;
      const total = st.detected.length;
      for (let i = 0; i < st.detected.length; i++) {
        const ep = st.detected[i];
        btn.innerHTML = SPIN_SVG_BTN + ' ' + (i+1) + '/' + total + '…';
        const ok = await spDownloadOne(ep);
        if (ok) {
          count++;
          const el = document.getElementById('sp-ep-' + ep.num);
          if (el) {
            el.className = 'sp-ep download';
            el.querySelector('.sp-ep-msg').textContent = 'Téléchargement lancé !';
            const ico = el.querySelector('.sp-ico'); if (ico) ico.remove();
            el.insertAdjacentHTML('beforeend', ICONS.download);
          }
        }
        // Small delay between each to avoid overwhelming the SW
        await new Promise(res => setTimeout(res, 1200));
      }
      spToast(count + '/' + total + ' téléchargement' + (count>1?'s':'') + ' lancé' + (count>1?'s':''), count>0?'ok':'err');
    } catch(e) {
      console.error('[SeasonPanel] spDownload error:', e);
      spToast('Erreur lors du téléchargement', 'err');
    }

    btn.disabled = false;
    btn.innerHTML = DL_BTN_HTML;
  }

  // ── Clear ──────────────────────────────────────────────────────────────────
  async function spClear() {
    if (!confirm('Effacer tous les résultats et recommencer avec une nouvelle saison ?')) return;
    browser.runtime.sendMessage({ name:'season_bg_clear' }).catch(() => {});
    // Reset UI
    document.getElementById('sp-prog').style.display    = 'none';
    document.getElementById('sp-dl-block').style.display = 'none';
    document.getElementById('sp-banner').style.display  = 'none';
    document.getElementById('sp-live').classList.remove('on');
    document.getElementById('sp-run').disabled  = false;
    document.getElementById('sp-test').disabled = false;
    document.getElementById('sp-stop').disabled = true;
    document.getElementById('sp-eplist').innerHTML = '';
    document.getElementById('sp-url').value = '';
    document.getElementById('sp-prev').classList.remove('on');
    spUpdateStats([]);
    spToast('Effacé — prêt pour une nouvelle saison', 'ok');
  }

  // ── Bootstrap ─────────────────────────────────────────────────────────────
  function spBootstrap() {
    const host = document.getElementById('sp-host');
    if (host) { spInit(); return; }
    // Not yet in DOM — wait for it
    const obs = new MutationObserver(() => {
      const h = document.getElementById('sp-host');
      if (h) { obs.disconnect(); spInit(); }
    });
    obs.observe(document.body || document.documentElement, { childList:true, subtree:true });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', spBootstrap);
  } else {
    spBootstrap();
  }

})();
