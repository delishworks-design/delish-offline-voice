/* ==========================================================================
   Delish — native bridge
   Detects the Android @JavascriptInterface object ("window.Delish").
   In a browser it silently falls back to the demo simulation, so the same
   UI files work for both preview and the real app.
   ========================================================================== */
(function (global) {
  'use strict';

  var native = global.Delish || null;   // injected by WebView
  var hasNative = !!(native && typeof native.sendMessage === 'function');

  /* event fan-out: UI subscribes with on(evt, fn) */
  var handlers = {};

  function on(evt, fn) {
    (handlers[evt] = handlers[evt] || []).push(fn);
    return function off() {
      handlers[evt] = (handlers[evt] || []).filter(function (f) { return f !== fn; });
    };
  }

  function emit(evt, payload) {
    var list = handlers[evt] || [];
    for (var i = 0; i < list.length; i++) {
      try { list[i](payload); } catch (e) { console.error('[Delish] handler error', evt, e); }
    }
  }

  /* ---- called from Kotlin via evaluateJavascript ---- */
  function onNative(event, payload) {
    var data = payload;
    if (typeof payload === 'string') {
      try { data = JSON.parse(payload); } catch (e) { data = { raw: payload }; }
    }
    emit(event, data);
    emit('*', { event: event, data: data });
  }
  global.DelishNative = { onNative: onNative };

  /* ---- safe native call ----
     Chromium resolves a @JavascriptInterface method by name AND parameter
     count. Calling a zero-arg Kotlin method as native.foo(null) fails with
     "Method not found", so pass no argument at all when we have none. */
  function call(fnName, arg) {
    if (!hasNative) return false;
    try {
      var fn = native[fnName];
      if (typeof fn !== 'function') {
        console.error('[Delish] native method missing: ' + fnName);
        return false;
      }
      if (arg === null || arg === undefined) fn.call(native);
      else fn.call(native, arg);
      return true;
    } catch (e) {
      console.error('[Delish] native call failed', fnName, e);
      return false;
    }
  }

  /* ---- public API used by the UI ---- */
  var api = {
    hasNative: hasNative,
    platform: hasNative ? 'android' : 'web',

    on: on,
    emit: emit,
    onNative: onNative,

    ready: function () { return hasNative ? native.ready() : false; },
    engineInfo: function () {
      return hasNative ? JSON.parse(native.engineInfo()) : { modelReady: false, hasModel: false };
    },

    sendMessage: function (text) {
      if (call('sendMessage', text)) return true;
      return false;                 // caller falls back to the demo reply
    },

    clearChat: function () { call('clearChat', null); },

    startVoiceLoop: function () { return call('startVoiceLoop', null); },
    stopVoiceLoop: function () { return call('stopVoiceLoop', null); },
    isLooping: function () { return hasNative ? native.isLooping() : false; },

    startDictation: function () { return call('startDictation', null); },
    stopDictation: function () { call('stopDictation', null); },

    stopSpeaking: function () { call('stopSpeaking', null); },

    /* interrupt / barge-in */
    interrupt: function () { return call('interrupt', null); },
    isBusy: function () { return hasNative ? native.isBusy() : false; },

    /* voices + storage + samples */
    listVoices: function () {
      if (!hasNative) return [];
      try { return JSON.parse(native.listVoices()); } catch (e) { return []; }
    },
    speakSample: function () { return call('speakSample', null); },
    setGender: function (g) { call('setGender', g === 'female' ? 'female' : 'male'); },
    storageInfo: function () {
      if (!hasNative) return null;
      try { return JSON.parse(native.storageInfo()); } catch (e) { return null; }
    },

    modelInstalled: function () { return hasNative ? native.modelInstalled() : false; },
    modelTier: function () { return hasNative ? native.modelTier() : 'mini'; },
    requestDownload: function (tier) {
      return call('requestDownload', String(tier || 'mini'));
    },
    clearDownloadError: function () { if (hasNative) native.clearDownloadError(); },
    setTier: function (tier) { call('setTier', String(tier || 'mini')); },
    setInstalled: function (v) { call('setInstalled', !!v); },
    /* diagnostics */
    getLogs: function (n) { return hasNative ? native.getLogs(n || 70) : ''; },
    getLogCount: function () { return hasNative ? native.getLogCount() : 0; },
    copyLogs: function () { return hasNative ? native.copyLogs() : false; },
    saveLogs: function () { return hasNative ? native.saveLogs() : ''; },
    clearLogs: function () { if (hasNative) native.clearLogs(); },

    /* speed / verbosity modes */
    speedMode: function () { return hasNative ? native.speedMode() : 'balanced'; },
    speedModeInfo: function () {
      if (!hasNative) return [];
      try { return JSON.parse(native.speedModeInfo()); } catch (e) { return []; }
    },
    setSpeedMode: function (key) { call('setSpeedMode', String(key || 'balanced')); },

    setAutoContinue: function (v) { call('setAutoContinueEnabled', !!v); },
    setLanguage: function (lang) { call('setSpeechLanguage', lang); },
    clearAllData: function () { call('clearAllData', null); },

    recents: function () {
      if (!hasNative) return [];
      try { return JSON.parse(native.recents()); } catch (e) { return []; }
    },
    openSession: function (id) { call('openSession', Number(id)); },
    newChat: function () { call('newChat', null); },
    deleteSession: function (id) { call('deleteSession', Number(id)); },
    currentSessionId: function () { return hasNative ? native.currentSessionId() : 0; },
    currentMessages: function () {
      if (!hasNative) return [];
      try { return JSON.parse(native.currentMessages()); } catch (e) { return []; }
    },
  };

  global.DelishBridge = api;

  /* ---- console banner ---- */
  if (!hasNative) {
    console.log('%cDelish', 'color:#00D6A0;font-weight:700', '— running in demo mode (no native bridge)');
  } else {
    console.log('%cDelish', 'color:#00D6A0;font-weight:700', '— native bridge active');
  }

})(window);