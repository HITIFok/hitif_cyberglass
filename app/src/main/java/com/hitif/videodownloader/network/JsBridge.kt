package com.hitif.videodownloader.network

/**
 * JavaScript injected into every page to capture media that can't be caught
 * via shouldInterceptRequest (e.g. blob: URLs, fetch() calls, MSE sources,
 * XHR response bodies containing M3U8 or video URLs).
 *
 * Features:
 *   1. Scans existing <video>/<audio> elements
 *   2. MutationObserver for dynamically added elements
 *   3. MSE source buffer interception
 *   4. HTMLMediaElement.src setter hook
 *   5. XHR.open monitoring (URL-only, lightweight)
 *   6. Fetch monitoring (URL-only, lightweight)
 *   7. XHR response body interception (M3U8, JSON, video content)
 *   8. Fetch response body interception (M3U8, JSON, video content)
 *   9. Performance API resource scanning (late detection)
 *  10. Periodic page source re-scan (catches delayed rendering)
 */
object JsBridge {

    const val INTERFACE_NAME = "HITIFAndroid"

    /**
     * JS to inject after page load. Calls window.HITIFAndroid.onMedia(url, type)
     * and window.HITIFAndroid.onXhrResponse(url, body, status) for every media URL
     * and response body it discovers.
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

  function reportResponse(url, body, status) {
    try {
      if (!url || !body || body.length < 10) return;
      bridge.onXhrResponse(url, body, status || 0);
    } catch(e) {}
  }

  // Check if content looks like media/M3U8/JSON with video URLs
  function isMediaResponse(ct, url) {
    var lower = (ct || '').toLowerCase();
    return lower.indexOf('mpegurl') !== -1 ||
           lower.indexOf('video/') !== -1 ||
           lower.indexOf('audio/') !== -1 ||
           lower.indexOf('octet-stream') !== -1 ||
           lower.indexOf('dash+xml') !== -1 ||
           url.indexOf('.m3u8') !== -1 ||
           url.indexOf('.mpd') !== -1 ||
           url.indexOf('.mp4') !== -1 ||
           url.indexOf('.webm') !== -1;
  }

  // ── 1. Scan existing <video> and <audio> elements ──────────────────────
  function scanExisting() {
    try {
      document.querySelectorAll('video, audio').forEach(function(el) {
        var src = el.src || el.currentSrc || '';
        if (src) report(src, el.tagName.toLowerCase());
        el.querySelectorAll('source').forEach(function(s) {
          if (s.src) report(s.src, el.tagName.toLowerCase());
        });
        // Also check <track> for subtitle URLs (skip)
      });
    } catch(e) {}
  }
  scanExisting();
  setTimeout(scanExisting, 2000);
  setTimeout(scanExisting, 5000);

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
  var xhrUrls = new WeakMap();
  XMLHttpRequest.prototype.open = function(method, url) {
    if (typeof url === 'string') {
      var lower = url.toLowerCase();
      if (lower.indexOf('.m3u8') !== -1 || lower.indexOf('.mpd') !== -1 ||
          lower.indexOf('/hls/') !== -1 || lower.indexOf('/dash/') !== -1) {
        report(url, 'xhr');
      }
      xhrUrls.set(this, url);
    }
    return origXhrOpen.apply(this, arguments);
  };

  // ── 6. XHR response body interception ─────────────────────────────────
  var origXhrSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.send = function(body) {
    var self = this;
    var _url = xhrUrls.get(self) || '';
    if (_url) {
      this.addEventListener('load', function() {
        try {
          if (self.status >= 200 && self.status < 400) {
            var ct = (self.getResponseHeader('Content-Type') || '').toLowerCase();
            var respUrl = self.responseURL || _url;
            if (isMediaResponse(ct, respUrl)) {
              var text = self.responseText || '';
              if (text.length >= 10) reportResponse(respUrl, text, self.status);
            }
          }
        } catch(e) {}
      });
    }
    return origXhrSend.apply(this, arguments);
  };

  // ── 7. Fetch monitoring + response body interception ──────────────────
  var origFetch = window.fetch;
  if (origFetch) {
    window.fetch = function(input, init) {
      var url = (typeof input === 'string') ? input : (input && input.url) || '';
      if (url) {
        var lower = url.toLowerCase();
        if (lower.indexOf('.m3u8') !== -1 || lower.indexOf('.mpd') !== -1 ||
            lower.indexOf('.mp4') !== -1 || lower.indexOf('.webm') !== -1 ||
            lower.indexOf('/stream/') !== -1 || lower.indexOf('/hls/') !== -1) {
          report(url, 'fetch');
        }
      }
      var p = origFetch.apply(this, arguments);
      try {
        p.then(function(response) {
          try {
            var ct = (response.headers && response.headers.get('Content-Type')) || '';
            if (isMediaResponse(ct, response.url || url)) {
              response.clone().text().then(function(text) {
                if (text && text.length >= 10) {
                  reportResponse(response.url || url, text, response.status);
                }
              }).catch(function(){});
            }
          } catch(e) {}
        }).catch(function(){});
      } catch(e) {}
      return p;
    };
  }

  // ── 8. Performance API resource scanning ─────────────────────────────
  setTimeout(function() {
    try {
      if (performance && performance.getEntriesByType) {
        var entries = performance.getEntriesByType('resource');
        entries.forEach(function(entry) {
          var name = entry.name || '';
          var lower = name.toLowerCase();
          if (lower.indexOf('.m3u8') !== -1 || lower.indexOf('.mpd') !== -1 ||
              lower.indexOf('/hls/') !== -1 || lower.indexOf('/dash/') !== -1 ||
              (entry.initiatorType === 'xmlhttprequest' &&
               (lower.indexOf('.mp4') !== -1 || lower.indexOf('video') !== -1 || lower.indexOf('stream') !== -1))) {
            report(name, 'performance');
          }
        });
      }
    } catch(e) {}
  }, 5000);

  // ── 9. Periodic re-scan for late-rendered media ──────────────────────
  setInterval(function() {
    try { scanExisting(); } catch(e) {}
  }, 8000);

})();
""".trimIndent()
}
