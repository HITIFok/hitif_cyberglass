package com.hitif.videodownloader.network

/**
 * JavaScript injected into every page to capture media that can't be caught
 * via shouldInterceptRequest (e.g. blob: URLs, fetch() calls, MSE sources).
 *
 * Mirrors the HITIF extension content-script approach.
 */
object JsBridge {

    const val INTERFACE_NAME = "HITIFAndroid"

    /**
     * JS to inject after page load. Calls window.HITIFAndroid.onMedia(url, type)
     * for every media URL it discovers.
     */
    val INJECT_SCRIPT = """
(function() {
  if (window.__hitif_injected) return;
  window.__hitif_injected = true;

  var bridge = window.$INTERFACE_NAME;
  if (!bridge) return;

  function report(url, type) {
    try {
      if (!url || url.length < 8) return;
      bridge.onMedia(url, type || 'unknown');
    } catch(e) {}
  }

  // ── 1. Scan existing <video> and <audio> elements ──────────────────────
  function scanExisting() {
    document.querySelectorAll('video, audio').forEach(function(el) {
      var src = el.src || el.currentSrc || '';
      if (src) report(src, el.tagName.toLowerCase());
      el.querySelectorAll('source').forEach(function(s) {
        if (s.src) report(s.src, el.tagName.toLowerCase());
      });
    });
  }
  scanExisting();
  setTimeout(scanExisting, 2000);

  // ── 2. MutationObserver for dynamically added elements ────────────────
  var obs = new MutationObserver(function(mutations) {
    mutations.forEach(function(m) {
      m.addedNodes.forEach(function(n) {
        if (n.nodeType !== 1) return;
        var els = [];
        if (n.tagName === 'VIDEO' || n.tagName === 'AUDIO') els.push(n);
        els = els.concat(Array.from(n.querySelectorAll ? n.querySelectorAll('video, audio') : []));
        els.forEach(function(el) {
          var src = el.src || el.currentSrc || '';
          if (src) report(src, el.tagName.toLowerCase());
          el.querySelectorAll('source').forEach(function(s) { if(s.src) report(s.src,'source'); });
        });
      });
    });
  });
  obs.observe(document.documentElement, { childList: true, subtree: true });

  // ── 3. Intercept MediaSource (MSE) ────────────────────────────────────
  if (window.MediaSource) {
    var origOpen = MediaSource.prototype.addSourceBuffer;
    if (origOpen) {
      MediaSource.prototype.addSourceBuffer = function(mime) {
        report('mse:' + location.href, 'mse');
        return origOpen.apply(this, arguments);
      };
    }
  }

  // ── 4. Intercept HTMLMediaElement.src setter ──────────────────────────
  var mediaTags = ['HTMLVideoElement', 'HTMLAudioElement'];
  mediaTags.forEach(function(tag) {
    if (!window[tag]) return;
    var proto = window[tag].prototype;
    var desc = Object.getOwnPropertyDescriptor(proto, 'src');
    if (!desc || !desc.set) return;
    var origSet = desc.set;
    Object.defineProperty(proto, 'src', {
      set: function(val) {
        if (val && !val.startsWith('blob:')) report(val, tag.toLowerCase().replace('html','').replace('element',''));
        return origSet.call(this, val);
      },
      get: desc.get,
      configurable: true
    });
  });

  // ── 5. XHR monitoring for m3u8 / mpd ─────────────────────────────────
  var origXhrOpen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function(method, url) {
    if (typeof url === 'string') {
      var lower = url.toLowerCase();
      if (lower.includes('.m3u8') || lower.includes('.mpd') || lower.includes('/hls/') || lower.includes('/dash/')) {
        report(url, 'xhr');
      }
    }
    return origXhrOpen.apply(this, arguments);
  };

  // ── 6. Fetch monitoring ───────────────────────────────────────────────
  var origFetch = window.fetch;
  if (origFetch) {
    window.fetch = function(input, init) {
      var url = (typeof input === 'string') ? input : (input && input.url) || '';
      if (url) {
        var lower = url.toLowerCase();
        if (lower.includes('.m3u8') || lower.includes('.mpd') || lower.includes('.mp4') ||
            lower.includes('.webm') || lower.includes('/stream/') || lower.includes('/hls/')) {
          report(url, 'fetch');
        }
      }
      return origFetch.apply(this, arguments);
    };
  }

})();
""".trimIndent()
}
