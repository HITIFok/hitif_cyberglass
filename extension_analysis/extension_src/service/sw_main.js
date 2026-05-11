/**
 * sw_main.js — Service Worker wrapper
 * Importe le SW original puis le module saison arrière-plan.
 */

// Guard: prevent "Cannot access contents of url about:blank" errors
// Monkey-patches chrome.scripting.executeScript to skip invalid tabs
try{
  const _origExec=chrome.scripting.executeScript.bind(chrome.scripting);
  chrome.scripting.executeScript=async function(inj){
    try{
      const tab=await chrome.tabs.get(inj.target.tabId);
      const url=tab?.url||tab?.pendingUrl||"";
      if(!url||url==="about:blank"||url.startsWith("about:")||url.startsWith("chrome://")||url.startsWith("chrome-extension://"))return[];
    }catch(_e){return[]}
    return _origExec(inj);
  };
  if(typeof browser!=="undefined"&&browser.scripting&&browser.scripting!==chrome.scripting){
    browser.scripting.executeScript=chrome.scripting.executeScript;
  }
}catch(_patch_err){}

import './main.js';
import './sw_season.js';
