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
 *  11. YouTube format extraction (parseYouTubeFormats with forceRefresh)
 *  12. YouTube title extraction (__hitif_get_youtube_title)
 *  13. YouTube URL refresh (__hitif_refresh_youtube)
 *  14. Stale googlevideo.com URL cleanup
 */
object JsBridge {

    const val INTERFACE_NAME = "HITIFAndroid"

    /**
     * JS to inject after page load. Calls window.HITIFAndroid.onMedia(url, type, title)
     * and window.HITIFAndroid.onXhrResponse(url, body, status) for every media URL
     * and response body it discovers.
     */
    val INJECT_SCRIPT = """
(function() {
  if (window.__hitif_injected) return;
  window.__hitif_injected = true;

  var bridge = window.$INTERFACE_NAME;
  if (!bridge) return;

  // Dedup set — externalised so forceRefresh can clear it
  if (!window.__hitif_seenItags) window.__hitif_seenItags = {};

  function report(url, type, title) {
    try {
      if (!url || url.length < 8) return;
      // Block non-http URLs (file://, blob:, data:, etc.)
      var urlLower = url.toLowerCase();
      if (urlLower.indexOf('file://') === 0 || urlLower.indexOf('content://') === 0 ||
          urlLower.indexOf('blob:') === 0 || urlLower.indexOf('data:') === 0 ||
          urlLower.indexOf('javascript:') === 0) return;
      // Block internal asset filenames from being reported as media
      var fname = url.split('/').pop().split('?')[0].toLowerCase();
      if (fname === 'success.mp3' || fname === 'open.mp3' || fname === 'no_input.mp3' ||
          fname === 'notification.mp3' || fname === 'error.mp3' || fname === 'click.mp3') return;
      // 3-arg call: report with title for YouTube formats
      if (title !== undefined) {
        bridge.onMedia(url, type || 'unknown', title || '');
      } else {
        bridge.onMedia(url, type || 'unknown');
      }
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

  // ── YouTube itag quality labels ─────────────────────────────────────
  var YT_ITAG_LABELS = {
    '5': '240p', '6': '270p', '13': '144p', '17': '144p', '18': '360p',
    '22': '720p', '34': '360p', '35': '480p', '36': '240p', '37': '1080p',
    '38': '3072p', '43': '360p', '44': '480p', '45': '720p', '46': '1080p',
    '59': '480p', '78': '480p', '82': '360p', '83': '480p', '84': '720p',
    '85': '1080p', '91': '720p', '92': '1080p', '93': '1080p', '94': '1080p',
    '95': '1080p', '96': '1080p', '100': '360p', '101': '480p', '102': '720p',
    '132': '240p', '133': '240p', '134': '360p', '135': '480p', '136': '720p',
    '137': '1080p', '138': '2160p', '139': '48kbps', '140': '128kbps',
    '141': '256kbps', '160': '144p', '167': '360p', '168': '480p',
    '169': '720p', '170': '1080p', '171': '128kbps', '172': '256kbps',
    '218': '480p', '219': '480p', '242': '240p', '243': '360p',
    '244': '480p', '247': '720p', '248': '1080p', '249': '48kbps',
    '250': '64kbps', '251': '128kbps', '256': '1080p', '258': '1440p',
    '264': '1440p', '266': '2160p', '271': '1440p', '272': '4320p',
    '278': '144p', '298': '720p', '299': '1080p', '302': '720p',
    '303': '1080p', '308': '1440p', '313': '2160p', '315': '2160p',
    '330': '144p', '331': '240p', '332': '360p', '333': '480p',
    '334': '720p', '335': '1080p', '336': '2160p', '337': '4320p'
  };

  // Audio-only itags
  var YT_AUDIO_ITAGS = {'139':1,'140':1,'141':1,'171':1,'172':1,'249':1,'250':1,'251':1};

  // Video-only itags (DASH adaptive — no audio)
  var YT_VIDEO_ONLY_ITAGS = {
    '133':1,'134':1,'135':1,'136':1,'137':1,'138':1,'160':1,
    '167':1,'168':1,'169':1,'170':1,'218':1,'219':1,
    '242':1,'243':1,'244':1,'247':1,'248':1,'256':1,
    '258':1,'264':1,'266':1,'271':1,'272':1,'278':1,
    '298':1,'299':1,'302':1,'303':1,'308':1,'313':1,
    '315':1,'330':1,'331':1,'332':1,'333':1,'334':1,
    '335':1,'336':1,'337':1
  };

  // ── YouTube format extraction ───────────────────────────────────────
  function parseYouTubeFormats(forceRefresh) {
    try {
      // Clear dedup on force refresh so we re-report ALL formats
      if (forceRefresh) window.__hitif_seenItags = {};

      var ytData = null;
      var ytTitle = '';

      // Method 1: ytInitialPlayerResponse (fastest, most reliable)
      try {
        if (typeof ytInitialPlayerResponse !== 'undefined' && ytInitialPlayerResponse) {
          ytData = ytInitialPlayerResponse;
        }
      } catch(e) {}

      // Method 2: ytcfg.setPlayerResponse (some embeds)
      if (!ytData) {
        try {
          var ytcfg = document.querySelector('#ytcfg');
          if (ytcfg && ytcfg.textContent) {
            var cfg = JSON.parse(ytcfg.textContent);
            if (cfg && cfg.args && cfg.args.player_response) {
              ytData = JSON.parse(cfg.args.player_response);
            }
          }
        } catch(e) {}
      }

      // Method 3: Embedded in page script (ytInitialData or player_response)
      if (!ytData) {
        try {
          var scripts = document.querySelectorAll('script');
          for (var si = 0; si < scripts.length; si++) {
            var text = scripts[si].textContent || '';
            // Match ytInitialPlayerResponse = {...} or var ytInitialPlayerResponse = {...}
            var match = text.match(/ytInitialPlayerResponse\s*=\s*(\{.+?\});/s);
            if (match) {
              ytData = JSON.parse(match[1]);
              break;
            }
          }
        } catch(e) {}
      }

      // Method 4: Parse from <script> tags containing player_response in JSON
      if (!ytData) {
        try {
          var allScripts = document.querySelectorAll('script');
          for (var si4 = 0; si4 < allScripts.length; si4++) {
            var sText = allScripts[si4].textContent || '';
            // Look for "player_response":"{...}" pattern
            var prMatch = sText.match(/"player_response"\s*:\s*"(\{.+?\})"/s);
            if (prMatch) {
              var decoded = prMatch[1].replace(/\\\\"/g, '"').replace(/\\\\n/g, '\\n').replace(/\\\\/g, '\\\\');
              ytData = JSON.parse(decoded);
              break;
            }
            // Also try: "playerResponse":{...} (unquoted object)
            var prMatch2 = sText.match(/"playerResponse"\s*:\s*(\{[^;]+\})\s*[,}]/s);
            if (prMatch2) {
              ytData = JSON.parse(prMatch2[1]);
              break;
            }
          }
        } catch(e) {}
      }

      if (!ytData) return 0;

      // Extract title from videoDetails
      if (ytData.videoDetails && ytData.videoDetails.title) {
        ytTitle = ytData.videoDetails.title;
      }

      // Extract streamingData
      var streamingData = ytData.streamingData;
      if (!streamingData) {
        // Some responses have it under player_response.streamingData
        if (ytData.player_response && ytData.player_response.streamingData) {
          streamingData = ytData.player_response.streamingData;
        }
      }
      if (!streamingData) return 0;

      var count = 0;
      var allFormats = [];

      // Formats array (progressive + some adaptive)
      var formats = streamingData.formats || [];
      for (var i = 0; i < formats.length; i++) {
        allFormats.push(formats[i]);
      }

      // Adaptive formats (DASH)
      var adaptive = streamingData.adaptiveFormats || [];
      for (var j = 0; j < adaptive.length; j++) {
        allFormats.push(adaptive[j]);
      }

      // Collect best audio for combo reporting
      var bestAudio = null;
      var bestAudioBitrate = 0;

      for (var fi = 0; fi < allFormats.length; fi++) {
        var fmt = allFormats[fi];
        var url = fmt.url;
        if (!url && fmt.cipher) {
          // Decode cipher URL (base64 + swap params)
          try {
            var parts = fmt.cipher.split('&');
            var params = {};
            for (var pi = 0; pi < parts.length; pi++) {
              var kv = parts[pi].split('=');
              params[kv[0]] = kv[1];
            }
            if (params.s && params.sp) {
              var decodedS = decodeURIComponent(escape(atob(params.s)));
              url = url || (params.url || '');
              url += '&' + params.sp + '=' + encodeURIComponent(decodedS);
            }
            if (params.url) url = params.url + '&' + (params.sp || 'sig') + '=' +
              encodeURIComponent(decodeURIComponent(escape(atob(params.s || ''))));
          } catch(ciphErr) {
            // If cipher decode fails, try signatureCipher
            try {
              var sc = fmt.signatureCipher;
              if (sc) {
                var scParts = sc.split('&');
                var scParams = {};
                for (var sci = 0; sci < scParts.length; sci++) {
                  var scKv = scParts[sci].split('=');
                  scParams[scKv[0]] = scKv[1];
                }
                url = scParams.url || '';
                if (scParams.s) {
                  var scDecoded = decodeURIComponent(escape(atob(scParams.s)));
                  url += '&' + (scParams.sp || 'sig') + '=' + encodeURIComponent(scDecoded);
                }
              }
            } catch(scErr) {}
          }
        } else if (!url && fmt.signatureCipher) {
          try {
            var sc2 = fmt.signatureCipher;
            var sc2Parts = sc2.split('&');
            var sc2Params = {};
            for (var sc2i = 0; sc2i < sc2Parts.length; sc2i++) {
              var sc2Kv = sc2Parts[sc2i].split('=');
              sc2Params[sc2Kv[0]] = sc2Kv[1];
            }
            url = sc2Params.url || '';
            if (sc2Params.s) {
              var sc2Decoded = decodeURIComponent(escape(atob(sc2Params.s)));
              url += '&' + (sc2Params.sp || 'sig') + '=' + encodeURIComponent(sc2Decoded);
            }
          } catch(sc2Err) {}
        }

        if (!url || url.length < 20) continue;

        var itag = fmt.itag || '';
        var label = YT_ITAG_LABELS[itag] || (itag + 'p');
        var mimeType = fmt.mimeType || '';
        var isAudio = !!YT_AUDIO_ITAGS[itag];
        var isVideoOnly = !!YT_VIDEO_ONLY_ITAGS[itag];

        // Dedup by itag
        if (window.__hitif_seenItags[itag] && !forceRefresh) continue;
        window.__hitif_seenItags[itag] = true;

        // Build type label
        var typeLabel = 'video';
        if (isAudio) {
          typeLabel = 'audio-only';
        } else if (isVideoOnly) {
          typeLabel = 'video-only';
        } else if (mimeType.indexOf('video/mp4') !== -1 || mimeType.indexOf('video/webm') !== -1) {
          typeLabel = 'combo';
        }

        var displayLabel = label;
        if (isAudio) displayLabel = '[audio-only] ' + label;
        else if (isVideoOnly) displayLabel = '[video-only] ' + label;
        else displayLabel = '[' + typeLabel + '] ' + label;

        // Report with title
        report(url, 'youtube_' + displayLabel, ytTitle);
        count++;

        // Track best audio for combo info
        if (isAudio && fmt.bitrate && fmt.bitrate > bestAudioBitrate) {
          bestAudioBitrate = fmt.bitrate;
          bestAudio = {url: url, label: label, itag: itag};
        }
      }

      return count;
    } catch(e) {
      console.log('HITIF parseYouTubeFormats error:', e);
      return 0;
    }
  }

  // ── YouTube title extraction (called from native) ──────────────────
  window.__hitif_get_youtube_title = function() {
    try {
      // Method 1: ytInitialPlayerResponse
      if (typeof ytInitialPlayerResponse !== 'undefined' && ytInitialPlayerResponse &&
          ytInitialPlayerResponse.videoDetails && ytInitialPlayerResponse.videoDetails.title) {
        return ytInitialPlayerResponse.videoDetails.title;
      }

      // Method 2: Parse from <script> tags
      var scripts = document.querySelectorAll('script');
      for (var i = 0; i < scripts.length; i++) {
        var text = scripts[i].textContent || '';
        var match = text.match(/ytInitialPlayerResponse\s*=\s*(\{.+?\});/s);
        if (match) {
          try {
            var data = JSON.parse(match[1]);
            if (data.videoDetails && data.videoDetails.title) return data.videoDetails.title;
          } catch(e) {}
        }
      }

      // Method 3: <h1> tag inside #title/ytd-watch-metadata (modern YouTube UI)
      var h1El = document.querySelector('h1.ytd-watch-metadata yt-formatted-string, h1.ytd-video-primary-info-renderer yt-formatted-string, #info-contents h1');
      if (h1El && h1El.textContent && h1El.textContent.trim().length > 2) {
        return h1El.textContent.trim();
      }

      // Method 4: <title> tag (fallback)
      var titleEl = document.querySelector('title');
      if (titleEl && titleEl.textContent) {
        var t = titleEl.textContent;
        // Strip " - YouTube" suffix
        t = t.replace(/\s*-\s*YouTube\s*$/i, '').trim();
        // Also strip "(123)" view count suffix
        t = t.replace(/\s*\(\d+\s*views?\)\s*$/i, '').trim();
        return t;
      }
    } catch(e) {}
    return '';
  };

  // ── YouTube URL refresh (called before opening media panel) ────────
  window.__hitif_refresh_youtube = function() {
    return parseYouTubeFormats(true);
  };

  // ── Remove stale googlevideo.com URLs from media panel ─────────────
  window.__hitif_remove_stale_youtube = function() {
    try {
      if (typeof bridge !== 'undefined' && bridge.removeStaleYoutubeUrls) {
        bridge.removeStaleYoutubeUrls();
      }
    } catch(e) {}
  };

  // ── 1. Scan existing <video> and <audio> elements ──────────────────
  function scanExisting() {
    try {
      document.querySelectorAll('video, audio').forEach(function(el) {
        var src = el.src || el.currentSrc || '';
        if (src) report(src, el.tagName.toLowerCase());
        el.querySelectorAll('source').forEach(function(s) {
          if (s.src) report(s.src, el.tagName.toLowerCase());
        });
      });
    } catch(e) {}
  }
  scanExisting();
  setTimeout(scanExisting, 2000);
  setTimeout(scanExisting, 5000);

  // ── 2. MutationObserver for dynamically added elements ────────────
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

  // ── 3. Intercept MediaSource (MSE) ────────────────────────────────
  if (window.MediaSource) {
    var origOpen = MediaSource.prototype.addSourceBuffer;
    if (origOpen) {
      MediaSource.prototype.addSourceBuffer = function(mime) {
        report('mse:' + location.href, 'mse');
        return origOpen.apply(this, arguments);
      };
    }
  }

  // ── 4. Intercept HTMLMediaElement.src setter ──────────────────────
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

  // ── 5. XHR monitoring for m3u8 / mpd ─────────────────────────────
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

  // ── 6. XHR response body interception ─────────────────────────────
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

  // ── 7. Fetch monitoring + response body interception ──────────────
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

  // ── 8. Performance API resource scanning ─────────────────────────
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

  // ── 9. YouTube auto-detection on page load ────────────────────────
  setTimeout(function() {
    if (location.hostname.indexOf('youtube.com') !== -1 ||
        location.hostname.indexOf('youtu.be') !== -1) {
      parseYouTubeFormats(false);
    }
  }, 3000);

  // ── 10. Periodic re-scan for late-rendered media ─────────────────
  setInterval(function() {
    try { scanExisting(); } catch(e) {}
  }, 8000);

})();
""".trimIndent()
}
