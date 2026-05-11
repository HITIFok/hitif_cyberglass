/**
 * Season Download M3U8 Interceptor
 * Runs in MAIN world at document_start on video host domains.
 * Captures m3u8/mpd URLs from fetch/XHR before the player converts them to blob: URLs.
 * Also hooks URL.createObjectURL to track blob creation.
 */
(function() {
  'use strict';
  window.__seasonCapturedM3u8 = window.__seasonCapturedM3u8 || [];

  // Hook fetch() to capture m3u8/mpd URLs
  const origFetch = window.fetch;
  window.fetch = function(...args) {
    try {
      let url = '';
      if (typeof args[0] === 'string') {
        url = args[0];
      } else if (args[0] && typeof args[0] === 'object') {
        url = args[0].url || '';
      }
      if (url && (url.includes('.m3u8') || url.includes('.mpd'))) {
        // Only keep master playlists, not segment playlists
        if (!url.match(/index-v[0-9]/i)) {
          if (!window.__seasonCapturedM3u8.includes(url) && window.__seasonCapturedM3u8.length < 50) {
            window.__seasonCapturedM3u8.push(url);
          }
        }
      }
    } catch(e) {}
    return origFetch.apply(this, args);
  };

  // Hook XMLHttpRequest.open to capture m3u8/mpd URLs
  const origXhrOpen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function(method, url) {
    try {
      if (url && (url.includes('.m3u8') || url.includes('.mpd'))) {
        if (!url.match(/index-v[0-9]/i)) {
          if (!window.__seasonCapturedM3u8.includes(url) && window.__seasonCapturedM3u8.length < 50) {
            window.__seasonCapturedM3u8.push(url);
          }
        }
      }
    } catch(e) {}
    return origXhrOpen.apply(this, arguments);
  };

  // Hook URL.createObjectURL to track blob URLs vs their sources
  const origCreateObjectURL = URL.createObjectURL.bind(URL);
  URL.createObjectURL = function(blob) {
    const url = origCreateObjectURL(blob);
    try {
      window.__seasonBlobCreated = window.__seasonBlobCreated || [];
      // Cap at 50 entries to avoid unbounded memory growth
      if (window.__seasonBlobCreated.length < 50) {
        window.__seasonBlobCreated.push(url);
      }
    } catch(e) {}
    return url;
  };
})();
