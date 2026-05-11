/**
 * Season Download - Video Download by HITIF
 * Batch download entire seasons of anime/series
 */

// ===== Chrome compatibility (browser namespace is Firefox-only) =====
if (typeof browser === 'undefined') {
  window.browser = chrome;
}

// ===== Deserializer (matches service worker's xe function) =====
// The service worker serializes all data using ie() before storing in session storage.
// Maps become {__serde_tag:"map",__serde_val:[[k,v],...]}
// Sets become {__serde_tag:"set",__serde_val:[v,...]}
// URL becomes {__serde_tag:"url",__serde_val:"https://..."}
// None becomes {__serde_tag:"none"}
// Some(x) becomes {__serde_tag:"some",__serde_val:x}
// Ok(x) becomes {__serde_tag:"ok",__serde_val:x}
// primitive becomes {__serde_tag:"primitive",__serde_val:value}
function L(val) {
  const obj = function(v) { if (!(this instanceof obj)) return new obj(v); this.value = v; };
  obj.prototype.isSome = function() { return true; };
  obj.prototype.isNone = function() { return false; };
  obj.prototype.unwrapOr = function(def) { return this.value; };
  obj.prototype.map = function(fn) { return L(fn(this.value)); };
  return new obj(val);
}
const z = {
  isSome: function() { return false; },
  isNone: function() { return true; },
  unwrapOr: function(def) { return def; },
  map: function(fn) { return this; },
  value: undefined
};

function xe(e) {
  if (!e || typeof e !== 'object') return e;
  if (e.__serde_tag === 'primitive') return e.__serde_val;
  if (e.__serde_tag === 'object') {
    let t = {};
    for (let [n, i] of Object.entries(e.__serde_val)) { t[n] = xe(i); }
    return t;
  }
  if (e.__serde_tag === 'map') return new Map(e.__serde_val.map(([k, v]) => [xe(k), xe(v)]));
  if (e.__serde_tag === 'set') return new Set(e.__serde_val.map(v => xe(v)));
  if (e.__serde_tag === 'url') return new URL(e.__serde_val);
  if (e.__serde_tag === 'array') return e.__serde_val.map(v => xe(v));
  if (e.__serde_tag === 'headers') return new Headers(e.__serde_val);
  if (e.__serde_tag === 'regex') return new RegExp(e.__serde_val[0], e.__serde_val[1]);
  if (e.__serde_tag === 'some') return L(xe(e.__serde_val));
  if (e.__serde_tag === 'none') return z;
  if (e.__serde_tag === 'ok') return { isOk: () => true, isErr: () => false, value: xe(e.__serde_val) };
  if (e.__serde_tag === 'err') return { isOk: () => false, isErr: () => true, error: xe(e.__serde_val) };
  return e;
}

// ===== State =====
let isRunning = false;
let shouldStop = false;
let currentTabId = null;  // Track current episode tab for cleanup on stop
let episodeResults = []; // {num, status, message}

// ===== DOM =====
const urlPattern = document.getElementById('url-pattern');
const startEpisode = document.getElementById('start-episode');
const endEpisode = document.getElementById('end-episode');
const delaySelect = document.getElementById('delay');
const timeoutSelect = document.getElementById('timeout');
const autoClose = document.getElementById('auto-close');
const autoDownload = document.getElementById('auto-download');
const btnStart = document.getElementById('btn-start');
const btnTest = document.getElementById('btn-test');
const btnStop = document.getElementById('btn-stop');
const progressCard = document.getElementById('progress-card');
const episodeList = document.getElementById('episode-list');
const progressBar = document.getElementById('progress-bar');
const statCurrent = document.getElementById('stat-current');
const statTotal = document.getElementById('stat-total');
const statSuccess = document.getElementById('stat-success');
const statError = document.getElementById('stat-error');
const urlPreview = document.getElementById('url-preview');

// ===== URL Preview =====
urlPattern.addEventListener('input', updatePreview);
startEpisode.addEventListener('input', updatePreview);
endEpisode.addEventListener('input', updatePreview);

function updatePreview() {
  const pattern = urlPattern.value.trim();
  const start = parseInt(startEpisode.value) || 1;
  if (pattern.includes('{N}')) {
    urlPreview.textContent = pattern.replace('{N}', start);
    urlPreview.classList.add('visible');
  } else {
    urlPreview.classList.remove('visible');
  }
}

// ===== Serialization (matches panel.js oe() function) =====
function oe(e) {
  if (typeof e === "string") return { __serde_tag: "primitive", __serde_val: e };
  if (typeof e === "number") return { __serde_tag: "primitive", __serde_val: e };
  if (typeof e === "boolean") return { __serde_tag: "primitive", __serde_val: e };
  if (typeof e > "u") return { __serde_tag: "primitive", __serde_val: e };
  if (e === null) return { __serde_tag: "primitive", __serde_val: e };
  if (Array.isArray(e)) return { __serde_tag: "array", __serde_val: e.map(o => oe(o)) };
  if (e instanceof URL) return { __serde_tag: "url", __serde_val: e.href };
  if (e instanceof Headers) {
    let o = [];
    e.forEach((r, i) => { o.push([i, r]); });
    return { __serde_tag: "headers", __serde_val: o };
  }
  if (e instanceof Set) return { __serde_tag: "set", __serde_val: [...e.values()].map(oe) };
  if (e instanceof Map) return { __serde_tag: "map", __serde_val: [...e.entries()].map(([o, r]) => [oe(o), oe(r)]) };
  if (e instanceof RegExp) return { __serde_tag: "regex", __serde_val: [e.source, e.flags] };
  // Option/Result types from the extension - check for isSome/isOk
  if (e && typeof e.isSome === 'function') {
    return e.isSome() ? { __serde_tag: "some", __serde_val: oe(e.value) } : { __serde_tag: "none" };
  }
  if (e && typeof e.isOk === 'function') {
    return e.isOk() ? { __serde_tag: "ok", __serde_val: oe(e.value) } : { __serde_tag: "err", __serde_val: oe(e.error) };
  }
  if (typeof e === "object") {
    let o = {};
    for (let [r, i] of Object.entries(e)) o[r] = oe(i);
    return { __serde_tag: "object", __serde_val: o };
  }
  throw new Error("oe: Unreachable for " + typeof e);
}

// ===== Messaging (matches panel.js I() function) =====
async function sendToService(msg) {
  return browser.runtime.sendMessage({ msg: msg, channel: 1 }); // FromContentToService = 1
}

// Listen for messages from service worker
let onMediaCallback = null;
browser.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message.channel === 6 && onMediaCallback) { // FromServiceToContent = 6
    onMediaCallback(message.msg);
  }
});

// ===== Toast =====
function showToast(message, type = 'info') {
  const toast = document.createElement('div');
  toast.className = `toast ${type}`;
  toast.textContent = message;
  document.body.appendChild(toast);
  setTimeout(() => { toast.style.opacity = '0'; }, 1800);
  setTimeout(() => toast.remove(), 2200);
}

// ===== SVG Icons =====
const SVG = {
  pending: '<svg class="episode-icon" viewBox="0 0 24 24"><circle cx="12" cy="12" r="10" fill="none" stroke="currentColor" stroke-width="2"/></svg>',
  loading: '<svg class="episode-icon" viewBox="0 0 24 24"><path d="M12 2a10 10 0 0 1 10 10" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>',
  success: '<svg class="episode-icon" viewBox="0 0 24 24"><path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z"/></svg>',
  error: '<svg class="episode-icon" viewBox="0 0 24 24"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z"/></svg>',
  skipped: '<svg class="episode-icon" viewBox="0 0 24 24"><path d="M6 6h12v12H6z" fill="none" stroke="currentColor" stroke-width="2"/></svg>',
  download: '<svg class="episode-icon" viewBox="0 0 24 24"><path d="M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z"/></svg>',
};

// ===== UI Updates =====
function createEpisodeElement(num, animeName) {
  const div = document.createElement('div');
  div.className = 'episode-item pending';
  div.id = `ep-${num}`;
  const label = animeName ? `${animeName} episode ${num}` : `Episode ${num}`;
  div.innerHTML = `
    <span class="episode-number">${label}</span>
    <span class="episode-status">En attente...</span>
    ${SVG.pending}
  `;
  return div;
}

function updateEpisode(num, status, message) {
  const el = document.getElementById(`ep-${num}`);
  if (!el) return;
  
  el.className = `episode-item ${status}`;
  el.querySelector('.episode-status').textContent = message;
  
  // Replace icon
  const iconEl = el.querySelector('.episode-icon');
  if (iconEl) iconEl.remove();
  el.insertAdjacentHTML('beforeend', SVG[status] || SVG.pending);
  
  // Scroll into view
  el.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

function updateStats() {
  const total = episodeResults.length;
  const done = episodeResults.filter(e => e.status !== 'pending' && e.status !== 'loading').length;
  const success = episodeResults.filter(e => e.status === 'success' || e.status === 'download').length;
  const error = episodeResults.filter(e => e.status === 'error').length;
  
  statCurrent.textContent = done;
  statTotal.textContent = total;
  statSuccess.textContent = success;
  statError.textContent = error;
  
  const pct = total > 0 ? (done / total * 100) : 0;
  progressBar.style.width = `${pct}%`;
}

// ===== Anime Name Extraction =====
// Extracts a clean anime/series name from the URL pattern.
// Example: "https://ww.animesultra.org/anime-vf/2701-jujutsu-kaisen-saison-3-vf-au/episode-{N}.html"
//        -> "Jujutsu kaisen saison 3 episode 1"
function extractAnimeName(pattern) {
  try {
    // Replace {N} placeholder with a number so the URL is valid for parsing
    const cleanPattern = pattern.replace('{N}', '1');
    const u = new URL(cleanPattern);
    const pathParts = u.pathname.split('/').filter(Boolean);

    // Detect streamdeouf URL structure:
    //   /voir-series/{genre}/{id}-{anime-name}/{N}-saison/{N}-episode.html
    // Examples of genre segments: animation-s, drame-s, action-s, comedie-s, etc.
    // The anime name is always the segment matching /^\d+-(.+)/ right before the saison segment.

    // Step 1: Find the anime slug by looking for the {ID}-{name} pattern
    // that appears BEFORE a saison/season directory segment
    let animeSlug = '';
    let saisonNum = '';

    for (let p = 0; p < pathParts.length; p++) {
      const part = pathParts[p];
      // Detect saison/season directory: matches "1-saison", "2-saison", "1-season", etc.
      const saisonMatch = part.match(/^(\d+)-(?:saison|season)$/i);
      if (saisonMatch) {
        saisonNum = saisonMatch[1];
        // The anime name is in the segment just before this one
        if (p > 0) {
          const prev = pathParts[p - 1];
          // Remove leading ID: "14493-the-most-heretical..." -> "the-most-heretical..."
          const cleaned = prev.replace(/^\d+[-_]/, '');
          if (cleaned && cleaned.length > 1) {
            animeSlug = cleaned;
          }
        }
        break;
      }
    }

    // Step 2: If no saison directory found, fall back to the generic approach
    if (!animeSlug) {
      for (let p = 0; p < pathParts.length; p++) {
        const part = pathParts[p];
        // Skip if it looks like episode/season indicator or generic path
        if (part.startsWith('episode') || part.startsWith('ep') || 
            part.startsWith('saison') || part.startsWith('season') ||
            part.match(/^\d+$/) || part === 'anime-vf' || part === 'anime-vost' ||
            part === 'anime' || part === 'vf' || part === 'vost') {
          continue;
        }
        // Skip genre/category segments (streamdeouf pattern: "animation-s", "drame-s", etc.)
        if (part.match(/^[a-z]+-s$/i)) continue;
        // Skip generic path segments like "voir-series", "voir-films", etc.
        if (part.match(/^voir-/i)) continue;
        // Skip the last part if it contains {N} (it's the episode URL template)
        if (part.includes('{N}')) continue;
        // Prefer segments that look like {ID}-{name} (actual anime slug)
        if (part.match(/^\d+-/)) {
          animeSlug = part.replace(/^\d+[-_]/, '');
          break;
        }
        // This is likely the anime name slug
        animeSlug = part;
      }
    }

    if (!animeSlug && pathParts.length >= 2) {
      // Fallback: use the segment just before the episode part
      animeSlug = pathParts[pathParts.length - 2] || pathParts[pathParts.length - 1];
      animeSlug = animeSlug.replace(/^\d+[-_]/, '');
    }

    if (!animeSlug) return '';

    // Remove common suffixes: -vf, -vost, -vfra, -vfi, -au, -episode, -streaming
    // Repeat to handle compound suffixes like "-vf-au"
    let prevSlug = '';
    while (animeSlug !== prevSlug) {
      prevSlug = animeSlug;
      animeSlug = animeSlug.replace(/[-_](vf|vost|vfra|vfi|va|au|episode|streaming|french|eng|sub)$/i, '');
    }

    // Convert slug to readable text: replace - and _ with spaces, title case
    let name = animeSlug.replace(/[-_]+/g, ' ').trim();
    
    // Title case each word
    name = name.replace(/\b\w+/g, word => 
      word.charAt(0).toUpperCase() + word.slice(1).toLowerCase()
    );

    // Append season number if detected
    if (saisonNum) {
      name = name + ' Saison ' + saisonNum;
    }

    return name;
  } catch (e) {
    return '';
  }
}

// Build the full episode filename from anime name + episode number
function buildEpisodeFilename(animeName, epNum) {
  if (animeName) {
    return animeName + ' episode ' + epNum;
  }
  return 'Episode_' + String(epNum).padStart(2, '0');
}


// ===== Embed URL Extraction (Phase 2: Cross-origin iframe detection) =====
// Many streaming sites embed video players in cross-origin iframes.
// The iframe is NOT loaded until the user clicks a server button (Vidmoly, VOE, etc.).
// This module extracts embed URLs from the episode page HTML and navigates
// directly to the best video player to detect the video source.

// Known non-video iframe domains to skip (social widgets, ads, analytics)
const NON_VIDEO_IFRAME_DOMAINS = [
  'yastatic.net', 'yandex.', 'ya.', 'yandex.ru',
  'facebook.', 'fb.', 'fbcdn.',
  'twitter.', 'x.com', 't.co',
  'google.', 'googlesyndication.', 'googleadservices.', 'doubleclick.',
  'youtube.', 'ytimg.',
  'instagram.',
  'linkedin.',
  'pinterest.',
  'disqus.',
  'gravity.',
  'addthis.', 'addtoany.',
  'sharethis.', 'sharing.',
  'quantserve.', 'scorecardresearch.',
  'hotjar.', 'crazyegg.',
  'cloudflare.', 'hcaptcha.', 'recaptcha.',
  'paypal.',
  'stripe.',
  'mailchimp.',
  'pushnotifications.',
  'onesignal.',
  'gtm.', 'googletagmanager.',
];

// Check if a URL belongs to a non-video domain (social widgets, ads, etc.)
function isNonVideoDomain(url) {
  if (!url || typeof url !== 'string') return false;
  const l = url.toLowerCase();
  for (const domain of NON_VIDEO_IFRAME_DOMAINS) {
    if (l.includes(domain)) return true;
  }
  return false;
}


// Known video hosting servers - ordered by quality priority
const SERVER_PRIORITY = [
  'doodstream', 'dood.',
  'voe',
  'vidoza', 'netu',
  'vidmoly', 'vidstream',
  'filemoon', 'moonplayer',
  'streamwish', 'wishembed',
  'mp4upload',
  'streamtape',
  'sibnet',
  'servero', 'ultracdn',
  'upstream',
  'unknown'
];

// Regex patterns for known video hosting domains (in page source HTML)
const EMBED_HOST_PATTERNS = [
  { name: 'vidmoly',     regex: /https?:\/\/[^\s"'<>]*(?:vidmoly\.[a-z]+|vidstream\.pro)[^\s"'<>]*/gi },
  { name: 'voe',         regex: /https?:\/\/[^\s"'<>]*voe\.[a-z]+[^\s"'<>]*/gi },
  { name: 'vidoza',      regex: /https?:\/\/[^\s"'<>]*vidoza\.[a-z]+[^\s"'<>]*/gi },
  { name: 'netu',        regex: /https?:\/\/[^\s"'<>]*(?:netu\.[a-z]+|waaw\.[a-z]+|hentai)[^\s"'<>]*/gi },
  { name: 'doodstream',  regex: /https?:\/\/[^\s"'<>]*(?:doodstream|dood\.[a-z]+|ds2play)[^\s"'<>]*/gi },
  { name: 'streamtape',  regex: /https?:\/\/[^\s"'<>]*(?:streamtape|stp\.)[^\s"'<>]*/gi },
  { name: 'streamwish',  regex: /https?:\/\/[^\s"'<>]*(?:streamwish|wishembed|awsat)[^\s"'<>]*/gi },
  { name: 'sibnet',      regex: /https?:\/\/[^\s"'<>]*sibnet[^\s"'<>]*/gi },
  { name: 'mp4upload',   regex: /https?:\/\/[^\s"'<>]*mp4upload[^\s"'<>]*/gi },
  { name: 'filemoon',    regex: /https?:\/\/[^\s"'<>]*(?:filemoon|moonplayer)[^\s"'<>]*/gi },
  { name: 'servero',     regex: /https?:\/\/[^\s"'<>]*(?:servero|srvoir)[^\s"'<>]*/gi },
  { name: 'upstream',    regex: /https?:\/\/[^\s"'<>]*(?:upstream|uptostream)[^\s"'<>]*/gi },
  { name: 'ultracdn',    regex: /https?:\/\/[^\s"'<>]*ultracdn[^\s"'<>]*/gi },
];

/**
 * Extract all potential video embed URLs from an episode page.
 * This handles the common patterns used by streaming sites:
 * - data-embed attributes (animesultra.org pattern)
 * - iframe[src] attributes
 * - Server buttons with onclick handlers (streamdeouf.net pattern)
 * - Known video hosting URLs in page source
 *
 * Returns: Array<{url: string, server: string}>
 */
async function extractEmbedUrls(tabId) {
  try {
    const frameResults = await browser.scripting.executeScript({
      target: { tabId: tabId },
      func: () => {
        // Local NON_VIDEO domain check (must be inside func - inaccessible from outside)
        const _NON_VIDEO = [
          'yastatic.net','yandex.','facebook.','fb.','twitter.',
          'google.','googlesyndication.','doubleclick.','youtube.',
          'instagram.','linkedin.','pinterest.','disqus.','addthis.',
          'cloudflare.','hcaptcha.','recaptcha.','paypal.','stripe.'
        ];
        function _isNonVideo(u) {
          if (!u) return true;
          const l = u.toLowerCase();
          return _NON_VIDEO.some(d => l.includes(d));
        }
        const urls = [];
        const seen = new Set();

        function add(url, server) {
          if (url && !seen.has(url) && url.startsWith('http')) {
            // Skip obviously non-embed URLs (images, CSS, etc.)
            if (url.match(/\.(png|jpg|jpeg|gif|webp|ico|css|woff|ttf)(\?|$)/i)) return;
            seen.add(url);
            urls.push({ url: url, server: server || 'unknown' });
          }
        }

        // 1) data-embed attributes (animesultra.org pattern)
        // <div class="server-item" data-embed="https://vidstream.pro/e/xxx"><a class="btn active">Vidmoly</a></div>
        document.querySelectorAll('[data-embed]').forEach(el => {
          const embedUrl = el.getAttribute('data-embed');
          if (embedUrl) {
            const btn = el.querySelector('a, button, .btn');
            const serverName = btn ? btn.textContent.trim() : (el.className || '');
            add(embedUrl, serverName);
          }
        });

        // 2) iframe[src] attributes (some sites load iframe immediately)
        // Skip non-video iframes (social widgets, ads, analytics)
        document.querySelectorAll('iframe').forEach(iframe => {
          const src = iframe.src || iframe.getAttribute('src') || '';
          if (src.startsWith('http') && !_isNonVideo(src)) add(src, 'iframe');
        });

        // 3) Server links with onclick handlers (streamdeouf.net pattern)
        // <div onclick="playEpisode(this, '6382', 'voe_vf')"><span class="serv">VOE</span></div>
        // Also handle: <div class="lien" onclick="playEpisode(this, '6382', 'doodstream_vf')">
        document.querySelectorAll('[onclick*="playEpisode"], [onclick*="playVideo"], [onclick*="loadVideo"], [onclick*="changeServer"]').forEach(el => {
          const onclick = el.getAttribute('onclick') || '';
          // Try to extract URL directly from onclick
          const urlMatch = onclick.match(/['"](https?:\/\/[^'"]+)['"]/);
          if (urlMatch) {
            const typeMatch = onclick.match(/['"]([^'"]*(?:vf|vost|va|eng|sub))['"]/i);
            const servEl = el.querySelector('.serv, .server-name');
            add(urlMatch[1], typeMatch ? typeMatch[1] : (servEl ? servEl.textContent.trim() : 'onclick'));
          }
          // Store server type info for potential later use
          const typeMatch = onclick.match(/['"]([^'"]*(?:vf|vost|va|eng|sub|voe|dood|vidmoly|sibnet|stream))['"]/i);
          if (typeMatch) {
            el._seasonServerType = typeMatch[1];
          }
        });

        // 4) Server icon classes (detect server type even without URL)
        // <i class="server player-voe"></i><span class="serv">VOE</span>
        document.querySelectorAll('.lien, .server-item, [class*="server"]').forEach(el => {
          const iconEl = el.querySelector('[class*="player-"]');
          const servEl = el.querySelector('.serv, .server-name, .server');
          if (iconEl) {
            const cls = iconEl.className || '';
            const serverType = cls.replace(/.*player-/, '').trim();
            el._seasonDetectedServer = serverType || (servEl ? servEl.textContent.trim() : '');
          }
        });

        // 5) data-src / data-url / data-iframe on player containers
        // Skip non-video domains
        document.querySelectorAll('[data-src], [data-url], [data-iframe], [data-video]').forEach(el => {
          for (const attr of ['data-src', 'data-url', 'data-iframe', 'data-video']) {
            const val = el.getAttribute(attr);
            if (val && val.startsWith('http') && !_isNonVideo(val)) add(val, 'data-attr');
          }
        });

        // 6) Scan page source for known video hosting URLs
        // Only match URLs that look like embeds (have /e/, /embed/, /d/, /v/ paths)
        const html = document.documentElement.innerHTML;
        const patterns = [
          /https?:\/\/[^\s"'<>]*(?:vidmoly\.[a-z]+|vidstream\.pro)\/e\/[^\s"'<>]*/gi,
          /https?:\/\/[^\s"'<>]*voe\.[a-z]+\/e\/[^\s"'<>]*/gi,
          /https?:\/\/[^\s"'<>]*(?:doodstream|dood\.(?:to|watch|so|pm|la|ws|yi|sh|re|cx|wf))\/e\/[^\s"'<>]*/gi,
          /https?:\/\/[^\s"'<>]*(?:doodstream|dood\.(?:to|watch|so|pm|la|ws|yi|sh|re|cx|wf))\/d\/[^\s"'<>]*/gi,
          /https?:\/\/[^\s"'<>]*(?:streamtape|stp\.)[^\s"'<>]*\/[a-z]\/[a-z][^\s"'<>]*/gi,
          /https?:\/\/[^\s"'<>]*(?:streamwish|wishembed)\/[^\s"'<>]*/gi,
          /https?:\/\/[^\s"'<>]*video\.sibnet[^\s"'<>]*\/[a-z]\/[a-z][^\s"'<>]*/gi,
          /https?:\/\/[^\s"'<>]*mp4upload[^\s"'<>]*\/[^\s"'<>]*/gi,
          /https?:\/\/[^\s"'<>]*(?:filemoon|moonplayer)\/[^\s"'<>]*/gi,
          /https?:\/\/[^\s"'<>]*vidoza\.[a-z]+\/[^\s"'<>]*/gi,
          /https?:\/\/[^\s"'<>]*(?:netu\.[a-z]+|waaw\.[a-z]+)\/[^\s"'<>]*/gi,
          /https?:\/\/[^\s"'<>]*(?:upstream|uptostream)\/[^\s"'<>]*/gi,
          /https?:\/\/[^\s"'<>]*(?:servero|srvoir)\/[^\s"'<>]*/gi,
          /https?:\/\/[^\s"'<>]*ultracdn\/[^\s"'<>]*/gi,
        ];
        for (const pattern of patterns) {
          let match;
          while ((match = pattern.exec(html)) !== null) {
            const url = match[0];
            if (!_isNonVideo(url)) add(url, 'html-source');
          }
        }

        // 7) Check for JavaScript variables that might contain embed URLs
        // Some sites store embed URLs in JS variables like var embedUrl = "https://..."
        const scriptEls = document.querySelectorAll('script');
        for (const s of scriptEls) {
          const text = s.textContent || '';
          const varPatterns = [
            /(?:var|let|const|embed|player|video)(?:Url|Src|Source|Link)\s*=\s*['"](https?:\/\/[^'"]+)['"]/gi,
            /(?:file|source|src)\s*:\s*['"](https?:\/\/[^'"]+)['"]/gi,
          ];
          for (const pattern of varPatterns) {
            let match;
            while ((match = pattern.exec(text)) !== null) {
              add(match[1], 'js-var');
            }
          }
        }

        return urls;
      }
    });

    if (!frameResults || !frameResults[0] || !frameResults[0].result) return [];
    return frameResults[0].result || [];
  } catch (e) {
    console.warn('[Season] extractEmbedUrls error:', e.message);
    return [];
  }
}

/**
 * Pick the best embed URL from a list of found URLs.
 * Prioritizes known high-quality servers (Vidmoly, VOE, doodstream, etc.)
 */
function pickBestEmbed(embedUrls) {
  if (embedUrls.length === 0) return null;

  // Filter out non-video domains first
  const videoEmbeds = embedUrls.filter(e => !isNonVideoDomain(e.url));

  // Also filter out URLs that don't look like video embeds
  // Real embed URLs typically have /e/, /embed/, /d/, /v/, /player paths
  const realEmbeds = videoEmbeds.filter(e => {
    const l = e.url.toLowerCase();
    return l.includes('/e/') || l.includes('/embed') || l.includes('/d/') ||
           l.includes('/v/') || l.includes('/player') || l.includes('/video') ||
           l.includes('/watch') || e.server === 'iframe' ||
           e.server === 'data-attr' || e.server === 'js-var';
  });

  const candidates = realEmbeds.length > 0 ? realEmbeds : videoEmbeds;
  if (candidates.length === 0) return null;

  for (const server of SERVER_PRIORITY) {
    const match = candidates.find(e =>
      e.server.toLowerCase().includes(server) ||
      e.url.toLowerCase().includes(server)
    );
    if (match) return match;
  }
  return candidates[0]; // fallback to first found
}

/**
 * Try to click server buttons programmatically to trigger iframe loading.
 * Some sites (like streamdeouf.net) don't have embed URLs in HTML -
 * they use JavaScript functions (playEpisode, loadVideo, etc.) to load
 * iframes on button click.
 *
 * Strategy: Collect ALL server buttons, try them in priority order
 * (Vidmoly > VOE > doodstream > others), return first iframe found.
 *
 * Returns: Array<{url: string, server: string}> of iframe URLs found after clicking
 */
async function tryClickServerButtons(tabId) {
  try {
    const results = await browser.scripting.executeScript({
      target: { tabId: tabId },
      func: () => {
        return new Promise((resolve) => {
          const NON_VIDEO = [
            'yastatic', 'yandex', 'facebook', 'twitter', 'google',
            'disqus', 'addthis', 'sharethis', 'cloudflare'
          ];

          function isBad(url) {
            if (!url) return true;
            const l = url.toLowerCase();
            return NON_VIDEO.some(d => l.includes(d));
          }

          function getServerName(el) {
            // Use specific selectors first (.serv, .server-name), NOT broad 'span'
            // because streamdeouf has many spans like <span class="pl-1">Lien 1:</span>
            const serv = el.querySelector('.serv, .server-name, .name');
            const text = serv ? serv.textContent.trim() : '';
            if (text) return text;
            // Try icon class like player-voe, player-doodstream
            const iconEl = el.querySelector('[class*="player-"]');
            if (iconEl) {
              const cls = iconEl.className || '';
              const m = cls.match(/player-([a-z]+)/i);
              if (m) return m[1].charAt(0).toUpperCase() + m[1].slice(1);
            }
            // Fallback: try specific button/link inside the element
            const btn = el.querySelector('a, button');
            if (btn) return btn.textContent.trim().substring(0, 20);
            // Last resort: first meaningful word from text content
            const fullText = el.textContent.trim();
            const words = fullText.split(/\s+/).filter(w => w.length > 1 && w.length < 20);
            return words[0] || fullText.substring(0, 20);
          }

          function getServerPriority(name) {
            const n = name.toLowerCase();
            if (n.includes('dood') || n.includes('doodstream')) return 0;
            if (n.includes('voe')) return 1;
            if (n.includes('vidoza')) return 2;
            if (n.includes('netu')) return 3;
            if (n.includes('vidmoly') || n.includes('vidstream')) return 4;
            if (n.includes('filemoon') || n.includes('moon')) return 5;
            if (n.includes('streamwish') || n.includes('wish')) return 6;
            if (n.includes('mp4upload')) return 7;
            if (n.includes('streamtape') || n.includes('stp')) return 8;
            if (n.includes('sibnet')) return 9;
            if (n.includes('servero') || n.includes('ultracdn')) return 10;
            if (n.includes('upstream') || n.includes('uptostream')) return 11;
            return 99;
          }

          // Collect ALL clickable server/lien elements
          const candidates = [];

          // .lien elements (streamdeouf.net pattern)
          document.querySelectorAll('.lien').forEach(el => {
            const name = getServerName(el);
            candidates.push({ el, name, priority: getServerPriority(name) });
          });

          // [onclick*="playEpisode"] or similar (streamdeouf.net alt pattern)
          document.querySelectorAll('[onclick*="playEpisode"], [onclick*="playVideo"], [onclick*="loadVideo"], [onclick*="changeServer"], [onclick*="setServer"], [onclick*="switchServer"]').forEach(el => {
            if (!el.classList.contains('lien')) {
              const name = getServerName(el);
              candidates.push({ el, name, priority: getServerPriority(name) });
            }
          });

          // .server-item a or .btn (animesultra.org pattern)
          document.querySelectorAll('.server-item a, .server-item .btn, .server-item button').forEach(el => {
            const name = getServerName(el);
            candidates.push({ el, name, priority: getServerPriority(name) });
          });

          // General server buttons
          document.querySelectorAll('[class*="server"] a, [class*="player"] a, [class*="tab-server"] a, [class*="server-tab"]').forEach(el => {
            if (!el.classList.contains('lien')) {
              const name = getServerName(el);
              candidates.push({ el, name, priority: getServerPriority(name) });
            }
          });

          // Also try .episode-server, .ep-server, .hoster, .hosting-item
          document.querySelectorAll('.episode-server, .ep-server, .hoster, .hosting-item, .mirror, .mirror-item').forEach(el => {
            if (!el.classList.contains('lien')) {
              const name = getServerName(el);
              candidates.push({ el, name, priority: getServerPriority(name) });
            }
          });

          if (candidates.length === 0) {
            console.log('[Season] tryClickServerButtons: No server buttons found on page');
            resolve([]);
            return;
          }

          // Sort by priority (best server first)
          candidates.sort((a, b) => a.priority - b.priority);

          // Remove duplicates by name
          const seen = new Set();
          const unique = candidates.filter(c => {
            const key = c.name.toLowerCase();
            if (seen.has(key)) return false;
            seen.add(key);
            return true;
          });

          console.log('[Season] tryClickServerButtons: found', unique.length, 'candidates:',
            unique.map(c => c.name + '(p' + c.priority + ')').join(', '));

          // Try clicking candidates one by one, checking for new iframe each time
          let clickIndex = 0;
          const maxClicks = Math.min(unique.length, 5); // Try at most 5 servers

          function tryNext() {
            if (clickIndex >= maxClicks) {
              resolve([]);
              return;
            }

            const target = unique[clickIndex].el;
            const targetName = unique[clickIndex].name;
            clickIndex++;

            console.log('[Season] Clicking server:', targetName);

            // Record existing iframes before clicking
            const existingIframes = new Set();
            document.querySelectorAll('iframe').forEach(iframe => {
              const src = iframe.src || iframe.getAttribute('src') || '';
              if (src) existingIframes.add(src);
            });

            target.click();

            // Wait for new iframe to appear (up to 4 seconds)
            setTimeout(() => {
              const newUrls = [];
              document.querySelectorAll('iframe').forEach(iframe => {
                const src = iframe.src || iframe.getAttribute('src') || '';
                if (src && src.startsWith('http') && !existingIframes.has(src) && !isBad(src)) {
                  newUrls.push({ url: src, server: targetName });
                }
              });

              if (newUrls.length > 0) {
                console.log('[Season] Found iframe after clicking', targetName, ':', newUrls[0].url.substring(0, 60));
                resolve(newUrls);
              } else {
                // No new iframe - try next server
                tryNext();
              }
            }, 4000);
          }

          tryNext();
        });
      }
    });

    if (!results || !results[0] || !results[0].result) return [];
    return results[0].result || [];
  } catch (e) {
    // "Frame with ID X was removed" means the tab was closed/navigated while script was running
    if (e.message && e.message.includes('Frame with ID')) {
      console.warn('[Season] tryClickServerButtons: tab frame removed (tab closed or navigated)');
    } else {
      console.warn('[Season] tryClickServerButtons error:', e.message);
    }
    return [];
  }
}

// ===== Core Logic =====

function sleep(ms) {
  return new Promise(resolve => {
    const interval = 500;
    let elapsed = 0;
    const timer = setInterval(() => {
      elapsed += interval;
      if (shouldStop || elapsed >= ms) {
        clearInterval(timer);
        resolve();
      }
    }, interval);
  });
}

function waitForTabComplete(tabId) {
  return new Promise((resolve) => {
    let resolved = false;
    function done() {
      if (resolved) return;
      resolved = true;
      browser.tabs.onUpdated.removeListener(listener);
      clearInterval(stopChecker);
      resolve();
    }
    function listener(updatedTabId, changeInfo) {
      if (updatedTabId === tabId && changeInfo.status === 'complete') done();
    }
    browser.tabs.onUpdated.addListener(listener);
    // Check shouldStop every 500ms
    const stopChecker = setInterval(() => {
      if (shouldStop) done();
    }, 500);
    // Timeout fallback (30s)
    setTimeout(done, 30000);
  });
}

/**
 * Wait for the extension's service worker to detect media on a tab.
 * The service worker stores detected media in browser.storage.session
 * under key "global_session_state" using its own ie() serializer.
 * We use xe() to properly deserialize Maps, Options, URLs, etc.
 *
 * The state structure is:
 *   {discovered: Map<tabId, {meta: metaObj, media: Map<hash, mediaObj>}>}
 */
async function waitForMediaDetection(tabId, timeoutMs, minTimestampMs) {
  const start = Date.now();
  const pollInterval = 1500;

  while (Date.now() - start < timeoutMs) {
    try {
      const result = await browser.storage.session.get('global_session_state');
      if (result.global_session_state) {
        // The state is serialized with ie(), must deserialize with xe()
        const state = xe(result.global_session_state);

        if (state && state.discovered) {
          // discovered is a Map<tabId, {meta, media: Map<hash, mediaObj>}>
          const tabEntry = state.discovered.get(tabId);
          if (tabEntry && tabEntry.media) {
            // media is a Map<hash, mediaObj>
            const mediaValues = tabEntry.media instanceof Map
              ? [...tabEntry.media.values()]
              : Object.values(tabEntry.media);

            if (mediaValues.length > 0) {
              // Guard against stale data from previous episodes.
              // Chrome may reuse tab IDs after closing, so session state
              // might still contain media from a closed tab with the same ID.
              // Only accept media discovered AFTER our episode page was opened.
              if (minTimestampMs) {
                const ts = mediaValues[0].discovery_timestamp_ms;
                if (ts && ts < minTimestampMs) {
                  console.log('[Season] Skipping stale media (detected at', ts, '< started at', minTimestampMs, ')');
                  await sleep(pollInterval);
                  continue;
                }
              }
              console.log('[Season] Extension detected', mediaValues.length, 'media(s) for tab', tabId,
                '- type:', mediaValues[0].type || 'unknown',
                '- url:', (mediaValues[0].url || mediaValues[0].master_url || '').substring(0, 60));
              return { media: mediaValues[0], meta: tabEntry.meta };
            }
          }
        }
      }
    } catch (e) {
      // Storage not accessible or parse error - ignore
    }
    await sleep(pollInterval);
  }
  return null;
}

/**
 * Build simplified download args for the detected media.
 * This mirrors the logic from panel.js buildArgs() / Gs()
 */
function buildDownloadArgs(media, meta) {
  const entry = media.playlist ? media.playlist[0] : null;
  const url = entry ? (entry.av ? (entry.av.video || entry.av.audio || entry.av) : media.url || media.master_url) : (media.url || media.master_url);
  
  if (!url) return null;
  
  const urlObj = typeof url === 'string' ? new URL(url) : url;
  const headers = media.sent_headers || new Headers();
  const basename = (meta && meta.smartnaming_rule) 
    ? (meta.smartnaming_rule.template || 'video').replace(/%title/g, (meta.title || 'episode'))
    : 'video';
  
  const cleanName = basename.replace(/[^\p{L}\p{N}\p{M}\-\s_.]/gu, '').substring(0, 190) || 'video';
  
  let extension = 'mp4';
  let muxer = 'mp4';
  let strategy = 'http_audio_video_one_source';
  let willUseJsFetch = true;
  
  if (media.type === 'm3u8_playlist') {
    extension = entry ? entry.demuxer : 'mp4';
    muxer = extension;
    strategy = entry && entry.av && entry.av.audio ? 'm3u8_audio_video_two_sources' : 'm3u8_audio_video_one_source';
    willUseJsFetch = false;
  } else if (media.type === 'm3u8') {
    extension = 'mp4';
    muxer = 'mp4';
    strategy = 'm3u8_audio_video_one_source';
    willUseJsFetch = true;
  } else if (media.type === 'mpd_playlist') {
    extension = 'mp4';
    muxer = 'mp4';
    strategy = 'mpd_audio_video_one_source';
    willUseJsFetch = true;
  } else if (media.type === 'http_playlist') {
    if (entry) {
      extension = media.extension || entry.demuxer || 'mp4';
      muxer = extension;
    }
    strategy = 'http_audio_video_one_source';
    willUseJsFetch = true;
  } else if (media.type === 'youtube_format') {
    extension = entry ? entry.demuxer : 'mp4';
    muxer = extension;
    strategy = entry && entry.av && entry.av.audio ? 'youtube_audio_video_two_sources' : 'youtube_audio_video_one_source';
    willUseJsFetch = false;
  }
  
  return {
    download_id: 'download_' + crypto.randomUUID(),
    headers: headers,
    good_basename: cleanName,
    subdir: '',
    save_as: false,
    will_use_jsfetch: willUseJsFetch,
    muxer: muxer,
    strategy: strategy,
    url: urlObj,
    entry: entry ? entry.index : undefined,
    duration: media.duration,
    extension: extension,
    is_youtube: !!media.is_youtube,
    throttle: false,
    cache: media.cache || 'default'
  };
}


// ===== URL Validation =====
// Rejects definitely non-media URLs (HTML, CSS, images, fonts, etc.).
// Uses BLACKLIST approach: reject known bad, allow everything else.
// This is important because anime sites use many different CDN URL patterns.
function isVideoUrl(url) {
  if (!url || typeof url !== 'string') return false;
  const l = url.toLowerCase();

  // Skip non-http protocols and blob: URLs (blob is local to page, can't be downloaded externally)
  if (l.startsWith('data:') || l.startsWith('javascript:') || l.startsWith('about:') || l.startsWith('blob:')) return false;

  // Fast accept: known video/streaming extensions
  if (l.match(/\.(m3u8|mpd|mp4|webm|mkv|avi|flv|ts|m4v|mov|wmv|ogv|3gp|ism)(\?|$|#)/i)) return true;
  // Fast accept: known audio extensions
  if (l.match(/\.(mp3|m4a|aac|ogg|flac|opus|wav|wma)(\?|$|#)/i)) return true;

  // REJECT .ts HLS segments (comma-based naming like _,n,l,.urlset/)
  if (l.includes('.ts') && (l.includes(',') || l.match(/\/seg[\-_]/i))) return false;

  // Definitely REJECT known non-media file extensions
  const rejectExts = [
    'html','htm','xhtml','css','js','mjs','json','xml','svg','xsl',
    'png','jpg','jpeg','gif','webp','ico','bmp','tiff','tif','avif',
    'woff','woff2','ttf','eot','otf',
    'pdf','doc','docx','xls','xlsx','ppt','pptx','odt','ods',
    'zip','rar','gz','bz2','7z','tar','xz',
    'map','txt','md','yaml','yml','toml','ini','cfg','conf',
    'php','asp','aspx','jsp','cgi','pl','py','rb','go','rs',
    'rss','atom','xml','manifest','appcache','swf','wasm',
    'exe','dll','so','dylib','bin','iso','dmg'
  ];
  for (const ext of rejectExts) {
    if (l.match(new RegExp('\\.' + ext + '(\\?|$|#)', 'i'))) return false;
  }

  // Reject URLs that look like page navigations (no extension, no real query params)
  try {
    const u = new URL(url);
    const path = u.pathname.toLowerCase();
    const pathParts = path.split('/').filter(Boolean);
    const lastPart = pathParts[pathParts.length - 1] || '';

    // If path has no extension, no query, and is a short path -> likely a page
    if (u.search === '' && !lastPart.includes('.') && pathParts.length <= 2) {
      // Exception: known API/streaming path patterns
      if (!path.includes('/video/') && !path.includes('/stream/') &&
          !path.includes('/hls/') && !path.includes('/media/') &&
          !path.includes('/dash/') && !path.includes('/embed/')) {
        return false;
      }
    }
  } catch(e) {}

  // Accept URLs with video-related patterns in path or query
  if (l.includes('mime=video') || l.includes('mime=audio') ||
      l.includes('contenttype=video') || l.includes('type=video') ||
      l.includes('format=mp4') || l.includes('format=m3u8')) return true;

  // Accept known CDN hostnames
  try {
    const u = new URL(url);
    const host = u.hostname.toLowerCase();
    const cdnHosts = ['googlevideo', 'cloudfront', 'akamaized', 'fastly',
      'cloudflare', 'gcore', 'vimeocdn', 'vidyard', 'wistia',
      'azureedge', 'amazonaws', 'googleapis'];
    for (const cdn of cdnHosts) {
      if (host.includes(cdn)) return true;
    }
    // Accept URLs with significant query params (likely CDN URLs with auth tokens)
    if (u.search.length > 10) return true;
    // Accept URLs that have some file extension (unknown type but a resource file)
    if (u.pathname.includes('.')) return true;
  } catch(e) {}

  return false;
}

/**
 * Extract video URLs from ALL frames of the page (including cross-origin iframes).
 * This is the key: anime sites embed video players in iframes, so we must
 * inject the detection script into every frame to find the actual video sources.
 */
async function extractVideoFromPage(tabId) {
  const allUrls = new Set();

  try {
    // Run in ALL frames - this is critical for anime sites where the video
    // player lives inside a cross-origin iframe
    const frameResults = await browser.scripting.executeScript({
      target: { tabId: tabId, allFrames: true },
      func: () => {
        const sources = [];
        const seen = new Set();

        function add(url) {
          if (url && !seen.has(url) && !url.startsWith('data:') && !url.startsWith('about:') && !url.startsWith('blob:')) {
            seen.add(url);
            sources.push(url);
          }
        }

        // 1) <video> elements - the most reliable source
        for (const v of document.querySelectorAll('video')) {
          add(v.currentSrc || v.src);
          for (const s of v.querySelectorAll('source')) {
            if (s.src) add(s.src);
          }
        }
        // Also check video.poster attribute for indirect URL hints
        for (const v of document.querySelectorAll('video')) {
          if (v.src) add(v.src);
        }

        // 2) <source> elements (standalone or inside video)
        for (const s of document.querySelectorAll('source')) {
          if (s.src) add(s.src);
        }

        // 3) <audio> elements (some anime sites use audio-only)
        for (const a of document.querySelectorAll('audio')) {
          add(a.currentSrc || a.src);
          for (const s of a.querySelectorAll('source')) {
            if (s.src) add(s.src);
          }
        }

        // 4) Performance entries - network requests for video resources
        try {
          const entries = performance.getEntriesByType('resource');
          for (const e of entries) {
            if (e.initiatorType === 'navigation') continue;
            // Skip tiny HLS/DASH segments (.ts, .m4s, init.mp4)
            if (url.match(/\.ts(\\?|$)/i) && url.includes(',')) continue;
            if (url.match(/\/init\.mp4(\\?|$)/i)) continue;
            // Skip segment playlists, keep only master m3u8
            if (url.match(/index-v[0-9]/i) && url.includes('.m3u8')) continue;
            const url = e.name;
            if (url.includes('.m3u8') || url.includes('.mpd') ||
                url.match(/\.(mp4|webm|mkv|avi|flv|ts|m4v|mov)(\?|$)/i) ||
                url.includes('mime=video') || url.includes('mime=audio') ||
                url.includes('/video/') || url.includes('/stream/') ||
                url.includes('/hls/') || url.includes('/dash/')) {
              add(url);
            }
          }
        } catch(pe) {}

        // 5) HLS.js instances (very common on anime sites)
        try {
          if (window.Hls && window.Hls.instances) {
            for (const hls of Object.values(window.Hls.instances)) {
              if (hls && hls.url) add(hls.url);
              if (hls && hls.levels) {
                for (const lvl of hls.levels) {
                  if (lvl && lvl.url) {
                    for (const u of (Array.isArray(lvl.url) ? lvl.url : [lvl.url])) add(u);
                  }
                }
              }
            }
          }
          // Also check for direct Hls player (not via .instances)
          if (window.hlsPlayer && window.hlsPlayer.url) {
            add(window.hlsPlayer.url);
          }
        } catch(hlsErr) {}

        // 6) video.js / jwplayer / flowplayer / plyr instances
        try {
          if (window.player && window.player.sources) {
            for (const src of (Array.isArray(window.player.sources) ? window.player.sources : [window.player.sources])) {
              if (src.file) add(src.file);
              if (src.src) add(src.src);
            }
          }
          if (window.jwplayer) {
            try {
              const pl = window.jwplayer();
              if (pl && pl.getPlaylist) {
                for (const item of pl.getPlaylist()) {
                  if (item.file) add(item.file);
                  if (item.sources) {
                    for (const s of item.sources) add(s.file || s.src);
                  }
                }
              }
            } catch(jwErr) {}
          }
          // video.js
          if (window.videojs) {
            for (const v of document.querySelectorAll('.video-js')) {
              if (v.player && v.player.src_) add(v.player.src_);
            }
          }
          // plyr
          if (window.Plyr) {
            for (const p of document.querySelectorAll('.plyr')) {
              if (p.media && p.media.querySelector('source')) {
                const src = p.media.querySelector('source');
                if (src && src.src) add(src.src);
              }
            }
          }
        } catch(pvErr) {}

        // 7) Data attributes on custom player elements
        try {
          for (const el of document.querySelectorAll('[data-src],[data-video-url],[data-source],[data-file],[data-url]')) {
            const attrs = ['data-src', 'data-video-url', 'data-source', 'data-file', 'data-url'];
            for (const attr of attrs) {
              const val = el.getAttribute(attr);
              if (val && (val.includes('.m3u8') || val.includes('.mp4') ||
                  val.includes('.webm') || val.includes('.mkv') || val.includes('video'))) {
                add(val);
              }
            }
          }
        } catch(dErr) {}

        return sources;
      }
    });

    // Merge results from ALL frames
    if (frameResults) {
      for (const fr of frameResults) {
        if (fr.result && Array.isArray(fr.result)) {
          for (const url of fr.result) {
            allUrls.add(url);
          }
        }
      }
    }
  } catch (e) {
    console.warn('[Season] extractVideoFromPage error:', e.message);
  }

  if (allUrls.size === 0) return null;

  // Convert to array and sort by priority: m3u8/mpd first, then mp4/webm/mkv, then others
  const sorted = [...allUrls];
  sorted.sort((a, b) => {
    const al = a.toLowerCase();
    const bl = b.toLowerCase();
    const pri = (u) => {
      if (u.includes('.m3u8')) return 0;
      if (u.includes('.mpd')) return 1;
      if (u.match(/\.(mp4|webm|mkv|ts|m4v|mov)(\?|$)/i)) return 2;
      return 3;
    };
    return pri(al) - pri(bl);
  });

  return sorted;
}

/**
 * Attempt to download a video URL via Chrome downloads API.
 * This is the most reliable method for direct video URLs (mp4, webm, mkv).
 * Includes Referer header for hotlink protection on anime sites.
 */
async function tryDirectDownload(videoUrl, epNum, referrerUrl, filename) {
  try {
    // m3u8/mpd are playlist files, NOT downloadable videos.
    // They require HLS/DASH muxing via the service worker.
    // Never download them directly - it would just save the playlist text.
    if (isStreamingUrl(videoUrl)) {
      console.warn('[Season] Skipping streaming URL (m3u8/mpd) - requires service worker:', videoUrl.substring(0, 80));
      return false;
    }

    let ext = '.mp4';
    if (videoUrl.includes('.ts')) ext = '.ts';
    else if (videoUrl.includes('.webm')) ext = '.webm';
    else if (videoUrl.includes('.mkv')) ext = '.mkv';
    else if (videoUrl.includes('.avi')) ext = '.avi';
    else if (videoUrl.includes('.flv')) ext = '.flv';

    // Reject non-video URLs to avoid downloading HTML pages
    if (!isVideoUrl(videoUrl)) {
      console.warn('[Season] Skipping non-video URL:', videoUrl.substring(0, 80));
      return false;
    }

    const options = {
      url: videoUrl,
      filename: (filename || 'Episode_' + String(epNum).padStart(2, '0')) + ext,
      saveAs: false
    };

    // Note: Chrome blocks 'Referer' in downloads API headers (unsafe header name).
    // The browser sends its own Referer based on the initiating context.

    const downloadId = await browser.downloads.download(options);
    console.log('[Season] Direct download started:', downloadId, 'for episode', epNum, videoUrl.substring(0, 80));
    return true;
  } catch (e) {
    console.warn('[Season] Direct download failed for episode', epNum, ':', e.message);
    return false;
  }
}

// Returns true if URL is a streaming format that needs the service worker (muxing).
// Direct video files (mp4, webm, mkv) work fine with browser.downloads.download().
function isStreamingUrl(url) {
  if (!url) return false;
  const l = url.toLowerCase();
  return l.includes('.m3u8') || l.includes('.mpd') ||
    l.includes('/hls/') || l.includes('/dash/');
}

/**
 * Download CDN-protected video using DNR header modification + new tab navigation.
 *
 * Key insight: 
 * - DNR CAN modify request headers (Referer) for main_frame navigations
 * - DNR CAN modify response headers (Content-Disposition) to force download
 * - Opening the CDN URL in a NEW tab triggers a main_frame navigation
 * - DNR intercepts and adds Referer: embed page URL + Content-Disposition: attachment
 * - Chrome sees Content-Disposition: attachment -> downloads the file instead of playing
 *
 * We open a NEW tab (not reuse embed tab) to avoid panel.js crash.
 */
async function downloadViaDNR(videoUrl, epNum, filename, refererUrl) {
  try {
    if (isStreamingUrl(videoUrl)) return false;
    if (!isVideoUrl(videoUrl)) return false;

    let ext = '.mp4';
    if (videoUrl.includes('.ts')) ext = '.ts';
    else if (videoUrl.includes('.webm')) ext = '.webm';
    else if (videoUrl.includes('.mkv')) ext = '.mkv';
    const fullFilename = (filename || 'Episode_' + String(epNum).padStart(2, '0')) + ext;

    const cdnHostname = new URL(videoUrl).hostname;
    const ruleId = (Date.now() % 100000) + 1;

    // Set DNR rule: 
    // - Request: add Referer header (so CDN accepts the request)
    // - Response: add Content-Disposition: attachment (so Chrome downloads instead of plays)
    await browser.declarativeNetRequest.updateDynamicRules({
      addRules: [{
        id: ruleId,
        priority: 1,
        action: {
          type: 'modifyHeaders',
          requestHeaders: [
            { header: 'Referer', operation: 'set', value: refererUrl || '' }
          ],
          responseHeaders: [
            { header: 'Content-Disposition', operation: 'set', value: 'attachment; filename="' + fullFilename + '"' }
          ]
        },
        condition: {
          urlFilter: '||' + cdnHostname + '/',
          resourceTypes: ['main_frame', 'sub_frame']
        }
      }]
    });

    console.log('[Season] DNR rule', ruleId, '- Referer:', refererUrl.substring(0, 50), '- filename:', fullFilename);

    try {
      // Open CDN URL in a NEW background tab
      // This triggers a main_frame navigation which DNR intercepts
      const dlTab = await browser.tabs.create({ url: videoUrl, active: false });
      console.log('[Season] Opened CDN URL in new tab', dlTab.id);

      // Wait for the download/navigation to process
      await new Promise(r => setTimeout(r, 5000));

      // Check if a download was triggered
      try {
        const recentDl = await browser.downloads.search({ limit: 3, orderBy: ['-startTime'] });
        const match = recentDl.find(d => d.url && d.url.includes(cdnHostname));
        if (match) {
          console.log('[Season] Download confirmed:', match.id, match.state, match.filename);
          return true;
        }
      } catch(e) {}

      // Close the tab if no download started (Chrome probably played the video)
      try { await browser.tabs.remove(dlTab.id); } catch(e) {}

      return false;
    } finally {
      // Always clean up DNR rule
      try {
        await browser.declarativeNetRequest.updateDynamicRules({ removeRuleIds: [ruleId] });
      } catch(e) {}
    }
  } catch (e) {
    console.warn('[Season] downloadViaDNR error:', e.message);
    return false;
  }
}


/**
 * Register a dynamic content script that intercepts fetch/XHR on video host pages.
 * This captures m3u8/mpd URLs BEFORE the HLS player converts them to blob: URLs.
 * The interceptor runs in MAIN world at document_start.
 */
const INTERCEPTOR_ID = 'season-m3u8-interceptor';

async function registerM3u8Interceptor(embedUrl) {
  try {
    const u = new URL(embedUrl);
    const matchPattern = u.protocol + '//' + u.hostname + '/*';

    // Unregister first in case it's already registered
    try { await browser.scripting.unregisterContentScripts({ ids: [INTERCEPTOR_ID] }); } catch(e) {}

    await browser.scripting.registerContentScripts([{
      id: INTERCEPTOR_ID,
      matches: [matchPattern],
      js: ['content/season_interceptor.js'],
      runAt: 'document_start',
      world: 'MAIN'
    }]);
    console.log('[Season] Registered m3u8 interceptor for', u.hostname);
    return true;
  } catch(e) {
    console.warn('[Season] Could not register m3u8 interceptor:', e.message);
    return false;
  }
}

async function unregisterM3u8Interceptor() {
  try {
    await browser.scripting.unregisterContentScripts({ ids: [INTERCEPTOR_ID] });
  } catch(e) {}
}

/**
 * Read captured m3u8 URLs from the MAIN world of a tab.
 */
async function getCapturedM3u8(tabId) {
  try {
    const results = await browser.scripting.executeScript({
      target: { tabId },
      world: 'MAIN',
      func: () => {
        return window.__seasonCapturedM3u8 || [];
      }
    });
    if (results && results[0] && Array.isArray(results[0].result) && results[0].result.length > 0) {
      return results[0].result;
    }
  } catch(e) {}
  return [];
}

/**
 * Try to download via the extension's own pipeline (do_download).
 * This is the same as clicking "Télécharger" in the extension panel.
 * do_download is fire-and-forget: the service worker may return null
 * but the download still starts in the background.
 */
async function tryServiceWorkerDownload(videoUrl, epNum, referrerUrl, episodeTabId, basename) {
  try {
    let mediaType = 'http_playlist';
    if (videoUrl.includes('.m3u8')) mediaType = 'm3u8';
    else if (videoUrl.includes('.mpd')) mediaType = 'mpd_playlist';

    const urlObj = new URL(videoUrl);
    const headers = new Headers();
    if (referrerUrl) {
      try {
        const refOrigin = new URL(referrerUrl).origin;
        headers.set('Referer', referrerUrl);
        headers.set('Origin', refOrigin);
      } catch(e) {}
    } else {
      try {
        headers.set('Referer', urlObj.origin + '/');
        headers.set('Origin', urlObj.origin);
      } catch(e) {}
    }

    const media = {
      url: videoUrl, master_url: videoUrl, type: mediaType,
      duration: 0, sent_headers: headers,
      has_drm: false, is_youtube: false, cache: 'default'
    };

    const downloadArgs = buildDownloadArgs(media, null);
    if (!downloadArgs) return false;

    downloadArgs.good_basename = basename || ('Episode_' + String(epNum).padStart(2, '0'));

    const safeTabId = episodeTabId || 0;
    const metaSerialized = {
      __serde_tag: 'object',
      __serde_val: {
        tab_id: { __serde_tag: 'primitive', __serde_val: safeTabId },
        url: { __serde_tag: 'none' },
        favicon_url: { __serde_tag: 'none' },
        incognito: { __serde_tag: 'primitive', __serde_val: false },
        default_action: { __serde_tag: 'primitive', __serde_val: 'download_as' }
      }
    };

    await sendToService({
      name: 'do_download',
      data: {
        download_args: oe(downloadArgs),
        meta: metaSerialized,
        media: oe(media)
      }
    });

    // do_download is fire-and-forget. Wait and check if a download actually started.
    console.log('[Season] do_download sent for episode', epNum);
    await new Promise(r => setTimeout(r, 2000));
    try {
      const recentDl = await browser.downloads.search({ limit: 1, orderBy: ['-startTime'] });
      if (recentDl.length > 0 && recentDl[0].startTime) {
        console.log('[Season] Download confirmed for episode', epNum);
        return true;
      }
    } catch(e) {}

    // Even if we can't confirm, assume it started (fire-and-forget)
    console.log('[Season] do_download sent (unable to confirm, assuming started) for episode', epNum);
    return true;
  } catch (e) {
    console.warn('[Season] tryServiceWorkerDownload error:', e.message);
    return false;
  }
}

// ===== Main Functions =====

function testUrl() {
  const pattern = getPattern();
  if (!pattern.includes('{N}')) {
    showToast("Impossible de detecter le pattern. Collez une URL d'episode.", 'error');
    return;
  }

  const start = parseInt(startEpisode.value) || 1;
  const testUrlStr = pattern.replace('{N}', start);

  updatePreview();

  // Open the test URL
  browser.tabs.create({ url: testUrlStr, active: true });
  showToast(`Episode ${start} ouvert dans un nouvel onglet`, 'info');
}

// ===== URL Pattern Auto-Detection =====
// Detects the URL pattern from a single episode URL.
// Supports many streaming site formats:
//   animesultra:  .../2701-anime-name/episode-{N}.html
//   streamdeouf:  .../1308-greys-anatomy/1-saison/1-episode.html
//   anime-sama:   .../episode-{N}-vostfr
//   generic:      .../season-1/episode-{N}  etc.
function detectUrlPattern(episodeUrl) {
  try {
    const u = new URL(episodeUrl);
    const path = u.pathname;
    const parts = path.split('/').filter(Boolean);
    if (parts.length < 2) return null;

    const lastPart = parts[parts.length - 1];
    const secondLast = parts[parts.length - 2];

    // Pattern A: .../{N}-episode.html  (streamdeouf.net)
    // URL: /voir-series/drame-s/1308-greys-anatomy/1-saison/1-episode.html
    const epMatch = lastPart.match(/^(\d+)-episode\.(html|htm)$/i);
    const saisonMatch = secondLast && secondLast.match(/^(\d+)-saison$/i);
    if (epMatch && saisonMatch) {
      const pattern = u.origin + '/' + parts.slice(0, -2).join('/') + '/' + saisonMatch[0] + '/{N}-episode.' + epMatch[2];
      let animeSlug = '';
      let detectedSaisonNum = saisonMatch[1];
      for (let i = 0; i < parts.length - 2; i++) {
        const p = parts[i];
        // Skip generic path segments (voir-series, voir-films, etc.)
        if (p.match(/^voir-/i)) continue;
        // Skip genre/category segments (animation-s, drame-s, action-s, comedie-s, etc.)
        if (p.match(/^[a-z]+-s$/i)) continue;
        // Skip known generic paths
        if (p === 'anime-vf' || p === 'anime-vost' || p === 'anime') continue;
        // The anime slug starts with a number: "14493-the-most-heretical..."
        if (p.match(/^\d+-/)) {
          animeSlug = p.replace(/^\d+-/, '');
          break;
        }
      }
      if (animeSlug) {
        let name = animeSlug.replace(/[-_]+/g, ' ').trim();
        name = name.replace(/\b\w+/g, w => w.charAt(0).toUpperCase() + w.slice(1).toLowerCase());
        // Append season number for clearer naming
        if (detectedSaisonNum) {
          name = name + ' Saison ' + detectedSaisonNum;
        }
        return { pattern, animeName: name };
      }
      return { pattern, animeName: '' };
    }

    // Pattern B: .../episode-{N}.html  (animesultra.org)
    const epOnlyMatch = lastPart.match(/^episode-(\d+)\.(html|htm)$/i);
    if (epOnlyMatch) {
      const pattern = u.origin + '/' + parts.slice(0, -1).join('/') + '/episode-{N}.' + epOnlyMatch[2];
      return { pattern, animeName: '' };
    }

    // Pattern C: .../episode-{N}-vostfr or .../episode-{N}-vf
    const epLangMatch = lastPart.match(/^episode-(\d+)-(vostfr|vf|vfra|va)\.?(html|htm)?$/i);
    if (epLangMatch) {
      const ext = epLangMatch[3] || 'html';
      const pattern = u.origin + '/' + parts.slice(0, -1).join('/') + '/episode-{N}-' + epLangMatch[2] + '.' + ext;
      return { pattern, animeName: '' };
    }

    // Pattern D: .../saison-{N}/ with episode number in filename
    const saisonDirMatch = secondLast && secondLast.match(/^(\d+)-(?:saison|season)$/i);
    if (saisonDirMatch) {
      const m = lastPart.match(/^(?:episode-)?(\d+)/i);
      if (m) {
        const newLast = lastPart.replace(m[1], '{N}');
        const pattern = u.origin + '/' + parts.slice(0, -1).join('/') + '/' + newLast;
        return { pattern, animeName: '' };
      }
    }

    // Pattern E: generic numeric pattern in filename
    const numFileMatch = lastPart.match(/^(?:ep-?|episode-?)?(\d+)(?:-v[of]+(?:st)?(?:fr|a)?)?(?:\.(html|htm))?$/i);
    if (numFileMatch) {
      const newLast = lastPart.replace(numFileMatch[1], '{N}');
      const pattern = u.origin + '/' + parts.slice(0, -1).join('/') + '/' + newLast;
      return { pattern, animeName: '' };
    }

    // Pattern F: last segment starts with a number
    const simpleNum = lastPart.match(/^(\d+)/);
    if (simpleNum) {
      const newLast = lastPart.replace(simpleNum[1], '{N}');
      const pattern = u.origin + '/' + parts.slice(0, -1).join('/') + '/' + newLast;
      return { pattern, animeName: '' };
    }

    return null;
  } catch (e) {
    return null;
  }
}

function getPattern() {
  const raw = urlPattern.value.trim();
  if (raw.includes('{N}')) return raw;
  if (raw.startsWith('http')) {
    const detected = detectUrlPattern(raw);
    if (detected) return detected.pattern;
  }
  return raw;
}

function getAutoAnimeName() {
  const raw = urlPattern.value.trim();
  if (raw.startsWith('http') && !raw.includes('{N}')) {
    const detected = detectUrlPattern(raw);
    if (detected && detected.animeName) return detected.animeName;
  }
  return '';
}

async function startBatch() {
  const pattern = getPattern();
  if (!pattern.includes('{N}')) {
    showToast("Impossible de detecter le pattern d'URL. Collez une URL d'episode.", 'error');
    return;
  }
  
  const start = parseInt(startEpisode.value) || 1;
  const end = parseInt(endEpisode.value) || 1;
  const delay = parseInt(delaySelect.value) || 8;
  const timeout = (parseInt(timeoutSelect.value) || 20) * 1000;
  
  if (end < start) {
    showToast("L'episode de fin doit etre superieur au debut", 'error');
    return;
  }
  
  if (end - start > 200) {
    showToast("Maximum 200 episodes a la fois", 'error');
    return;
  }
  
  isRunning = true;
  shouldStop = false;
  episodeResults = [];
  
  // Extract anime name from URL pattern for smart file naming
  let animeName = getAutoAnimeName() || extractAnimeName(pattern);
  const filePrefix = animeName ? animeName : 'Episode';
  
  // Show progress card
  progressCard.classList.add('active');
  btnStart.disabled = true;
  btnTest.disabled = true;
  btnStop.disabled = false;
  
  // Create episode list items
  episodeList.innerHTML = '';
  for (let i = start; i <= end; i++) {
    episodeResults.push({ num: i, status: 'pending', message: 'En attente...' });
    episodeList.appendChild(createEpisodeElement(i, animeName));
  }
  
  if (animeName) {
    console.log('[Season] Anime name detected:', animeName);
    showToast(`Telechargement: ${animeName} (${end - start + 1} episodes)`, 'info');
  }
  
  updateStats();
  
  showToast(`Lancement du telechargement de ${end - start + 1} episodes...`, 'info');
  
  for (let i = start; i <= end; i++) {
    if (shouldStop) break;
    
    const epUrl = pattern.replace('{N}', i);
    const epIdx = i - start;
    
    // Update status to loading
    episodeResults[epIdx].status = 'loading';
    episodeResults[epIdx].message = 'Ouverture de l\'onglet...';
    updateEpisode(i, 'loading', 'Ouverture de l\'onglet...');
    updateStats();
    
    let tabId = null;
    const episodeStartTime = Date.now(); // Record BEFORE opening tab to detect stale media
    
    try {
      // Open tab
      const tab = await browser.tabs.create({ url: epUrl, active: false });
      tabId = tab.id;
      currentTabId = tabId;
      
      // Wait for page to load
      episodeResults[epIdx].message = 'Chargement de la page...';
      updateEpisode(i, 'loading', 'Chargement de la page...');
      
      await waitForTabComplete(tabId);
      
      // Small delay to let lazy scripts initialize
      await sleep(2000);
      
      let videoUrls = null;
      let detectedMedia = null;
      let currentEmbedUrl = null; // Track embed URL for proper Referer header

      // === PHASE 1: Direct detection on episode page ===
      // Single attempt with timestamp guard against stale data.
      console.log('[Season] Phase 1: Starting detection on episode page...');
      episodeResults[epIdx].message = 'Detection de la video...';
      updateEpisode(i, 'loading', 'Detection de la video...');

      // Try extension's own detection first (with stale-data guard)
      detectedMedia = await waitForMediaDetection(tabId, 3000, episodeStartTime);

      if (detectedMedia) {
        console.log('[Season] Phase 1: Media detected by extension:', 
          detectedMedia.media?.type, 
          (detectedMedia.media?.url || detectedMedia.media?.master_url || '').substring(0, 60));
      } else {
        // Fallback: extract from all frames
        videoUrls = await extractVideoFromPage(tabId);
        if (videoUrls && videoUrls.length > 0) {
          console.log('[Season] Phase 1: extractVideoFromPage found', videoUrls.length, 'URL(s):',
            videoUrls.slice(0, 3).map(u => u.substring(0, 60)).join(', '));
        }
      }

      console.log('[Season] Phase 1 result:',
        detectedMedia ? 'extension detected (' + detectedMedia.media?.type + ')' :
        videoUrls ? videoUrls.length + ' video URL(s) found' :
        'nothing found');

      // === PHASE 2: Click server buttons if Phase 1 found nothing ===
      // Many streaming sites (streamdeouf, animesultra) require clicking a server
      // button (VOE, doodstream, Vidmoly) to load the video player.
      if (!detectedMedia && (!videoUrls || videoUrls.length === 0) && !shouldStop) {
        console.log('[Season] Phase 2: Clicking server buttons...');
        episodeResults[epIdx].message = 'Chargement du lecteur video...';
        updateEpisode(i, 'loading', 'Chargement du lecteur video...');

        // Try clicking server buttons to trigger iframe loading
        const clickedEmbeds = await tryClickServerButtons(tabId);

        if (clickedEmbeds && clickedEmbeds.length > 0) {
          console.log('[Season] Found iframe after clicking:', clickedEmbeds[0].url.substring(0, 60));
          currentEmbedUrl = clickedEmbeds[0].url; // Remember embed URL for Referer

          // Navigate to the embed URL to let the video player load
          try {
            await browser.tabs.update(tabId, { url: clickedEmbeds[0].url });
            await waitForTabComplete(tabId);
            await sleep(3000); // Wait for video player scripts to initialize

            // Detect media on the embed page (with stale-data guard)
            console.log('[Season] Detecting media on embed page...');
            for (let retry = 0; retry < 3; retry++) {
              if (shouldStop) break;

              detectedMedia = await waitForMediaDetection(tabId, 5000, episodeStartTime);
              if (detectedMedia) {
                console.log('[Season] Media detected on embed page:', detectedMedia.media?.type);
                break;
              }
              await sleep(2000);
            }

            // Fallback: extract from page
            if (!detectedMedia) {
              videoUrls = await extractVideoFromPage(tabId);
              if (videoUrls && videoUrls.length > 0) {
                videoUrls = videoUrls.filter(u => !u.startsWith('blob:'));
                console.log('[Season] extractVideoFromPage found on embed:', videoUrls.length);
              }
            }
          } catch (navErr) {
            console.warn('[Season] Error navigating to embed:', navErr.message);
          }
        } else {
          console.log('[Season] No server buttons found or no iframe loaded');
        }
      }

      // === DOWNLOAD ===
      let downloadSuccess = false;

      if (detectedMedia && detectedMedia.media && autoDownload.checked) {
        // --- Case A: Extension detected the video (best case) ---
        // Use the extension's own pipeline (do_download), same as clicking "Télécharger".
        const media = detectedMedia.media;
        const meta = detectedMedia.meta;
        const videoUrl = media.url || media.master_url;

        episodeResults[epIdx].message = 'Video detectee - telechargement...';
        updateEpisode(i, 'loading', 'Lancement du telechargement...');

        try {
          if (videoUrl) {
            const downloadArgs = buildDownloadArgs(media, meta);
            if (downloadArgs) {
              downloadArgs.good_basename = buildEpisodeFilename(animeName, i);

              // Serialize meta using oe() - same as panel.js does.
              // The meta comes from xe() deserialization, so oe() round-trips correctly.
              const metaForService = meta ? oe(meta) : null;
              if (!metaForService) {
                console.warn('[Season] No meta available for episode', i, '- skipping do_download');
              } else {
                // Record downloads before sending do_download to compare after
                let preDownloads = [];
                try {
                  preDownloads = await browser.downloads.search({ limit: 5, orderBy: ['-startTime'] });
                } catch(e) {}

                console.log('[Season] Sending do_download for episode', i, '- type:', media.type);
                await sendToService({
                  name: 'do_download',
                  data: { download_args: oe(downloadArgs), meta: metaForService, media: oe(media) }
                });

                // Wait for download to start, then verify it actually started
                await new Promise(r => setTimeout(r, 3000));
                try {
                  const postDownloads = await browser.downloads.search({ limit: 5, orderBy: ['-startTime'] });
                  // Check if a NEW download appeared (not in preDownloads)
                  const preIds = new Set(preDownloads.map(d => d.id));
                  const newDl = postDownloads.find(d => !preIds.has(d.id));
                  if (newDl) {
                    console.log('[Season] Download confirmed:', newDl.id, newDl.filename);
                    downloadSuccess = true;
                  } else {
                    console.warn('[Season] do_download did NOT start a download for episode', i);
                    // Fall through to Case B instead of assuming success
                  }
                } catch(e) {
                  console.warn('[Season] Could not verify download:', e.message);
                }
              }
            }
          }
        } catch (pipeErr) {
          console.warn('[Season] Extension pipeline error:', pipeErr.message);
        }
      }

      // --- Case B: Video URL found by extraction (no extension detection) ---
      if (!downloadSuccess && videoUrls && videoUrls.length > 0 && autoDownload.checked) {
        console.log('[Season] Using extracted video URLs for episode', i);

        for (const vUrl of videoUrls) {
          if (vUrl.match(/index-v[0-9]/i) && vUrl.includes('.m3u8')) continue;
          if (vUrl.includes('.ts') && vUrl.includes(',')) continue;

          episodeResults[epIdx].message = 'Tentative: ' + (vUrl.length > 60 ? vUrl.substring(0, 60) + '...' : vUrl);
          updateEpisode(i, 'loading', 'Tentative de telechargement...');

          // On embed page: use DNR to add Referer + force download via Content-Disposition
          if (currentEmbedUrl) {
            console.log('[Season] Using DNR download for episode', i);
            downloadSuccess = await downloadViaDNR(vUrl, i, buildEpisodeFilename(animeName, i), currentEmbedUrl);
          }
          // Fallback: direct download
          if (!downloadSuccess) {
            downloadSuccess = await tryDirectDownload(vUrl, i, epUrl, buildEpisodeFilename(animeName, i));
          }

          if (downloadSuccess) break;
        }
      }

      if (!downloadSuccess && detectedMedia && !autoDownload.checked) {
        episodeResults[epIdx].status = 'success';
        episodeResults[epIdx].message = 'Video detectee (auto-download desactive)';
        updateEpisode(i, 'success', 'Video detectee');
      } else if (!downloadSuccess && videoUrls && videoUrls.length > 0 && !autoDownload.checked) {
        episodeResults[epIdx].status = 'success';
        episodeResults[epIdx].message = videoUrls.length + ' video(s) trouvee(s)';
        updateEpisode(i, 'success', videoUrls.length + ' video(s) trouvee(s)');
      } else if (downloadSuccess) {
        episodeResults[epIdx].status = 'download';
        episodeResults[epIdx].message = 'Telechargement lance !';
        updateEpisode(i, 'download', 'Telechargement lance !');
      } else if (autoDownload.checked) {
        episodeResults[epIdx].status = 'error';
        episodeResults[epIdx].message = 'Aucune video detectee sur cette page';
        updateEpisode(i, 'error', 'Aucune video detectee');
      } else {
        episodeResults[epIdx].status = 'skipped';
        episodeResults[epIdx].message = 'Aucune video detectee';
        updateEpisode(i, 'skipped', 'Aucune video detectee');
      }
      
    } catch (err) {
      console.error(`Error processing episode ${i}:`, err);
      episodeResults[epIdx].status = 'error';
      episodeResults[epIdx].message = 'Erreur: ' + (err.message || err);
      updateEpisode(i, 'error', 'Erreur: ' + (err.message || String(err)).substring(0, 60));
    }
    
    // Close tab if enabled (or if stopped)
    if (tabId && (autoClose.checked || shouldStop)) {
      try {
        await browser.tabs.remove(tabId);
      } catch (e) {
        // Tab may already be closed
      }
    }
    currentTabId = null;
    
    updateStats();
    
    // Delay between episodes
    if (i < end && !shouldStop) {
      await sleep(delay * 1000);
    }
  }
  
  // Done
  isRunning = false;
  shouldStop = false;
  btnStart.disabled = false;
  btnTest.disabled = false;
  btnStop.disabled = false;
  
  const successCount = episodeResults.filter(e => e.status === 'success' || e.status === 'download').length;
  const downloadCount = episodeResults.filter(e => e.status === 'download').length;
  const errorCount = episodeResults.filter(e => e.status === 'error').length;
  const skippedCount = episodeResults.filter(e => e.status === 'skipped').length;
  
  const animeLabel = animeName || 'Saison';
  showToast(
    `${animeLabel} terminee ! ${downloadCount} telecharges, ${successCount - downloadCount} detectes, ${errorCount} erreurs, ${skippedCount} ignores`,
    downloadCount > 0 ? 'success' : (successCount > 0 ? 'info' : 'error')
  );
}

function stopBatch() {
  shouldStop = true;
  isRunning = false;
  btnStop.disabled = true;
  btnStart.disabled = false;
  btnTest.disabled = false;
  showToast('Arret en cours...', 'info');
  console.log('[Season] Stop requested by user');
  // Immediately close the currently open episode tab to speed up stopping
  if (currentTabId) {
    try {
      browser.tabs.remove(currentTabId);
      console.log('[Season] Closed current episode tab', currentTabId);
    } catch (e) {
      // Tab may already be closed
    }
  }
}

// ===== Button event listeners (no inline handlers - CSP compliant) =====
btnStart.addEventListener('click', startBatch);
btnTest.addEventListener('click', testUrl);
btnStop.addEventListener('click', stopBatch);

// ===== Init =====
updatePreview();
