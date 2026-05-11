'use strict';
chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (msg && msg.name === 'season_timer') {
    setTimeout(() => sendResponse({ done: true }), msg.delay || 1000);
    return true;
  }
  if (msg && msg.name === 'season_ping') {
    sendResponse({ alive: true });
  }
});
