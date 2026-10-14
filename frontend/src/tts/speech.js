import { api } from '../api.js';

/** Shared lip-sync signal (0..1) read every frame by whichever avatar renderer is mounted. */
export const lipsync = { level: 0 };

export const BROWSER_LANGS = { vi: 'vi-VN', en: 'en-US', zh: 'zh-CN', ja: 'ja-JP', ko: 'ko-KR', es: 'es-ES', ru: 'ru-RU' };

let audioCtx;
let stopCurrent = () => {};

export function stopSpeaking() {
  stopCurrent();
  stopCurrent = () => {};
  lipsync.level = 0;
}

/**
 * Speak a bot line. BROWSER uses the free Web Speech API (voices depend on the OS; Windows/macOS/Android ship
 * vi/en/zh/ja/ko/es/ru voices). Other providers are synthesized by the backend and analysed for lip-sync.
 */
export function speak({ text, language = 'en', ttsProvider = 'BROWSER', voiceId }) {
  stopSpeaking();
  if (!text) return Promise.resolve();
  return ttsProvider === 'BROWSER' || !ttsProvider
    ? speakBrowser(text, language, voiceId)
    : speakServer(text, language, ttsProvider, voiceId).catch((e) => {
      console.warn('Server TTS failed, falling back to browser voice', e);
      return speakBrowser(text, language);
    });
}

function speakBrowser(text, language, voiceName) {
  return new Promise((resolve) => {
    if (!('speechSynthesis' in window)) return resolve();
    const u = new SpeechSynthesisUtterance(text);
    const lang = BROWSER_LANGS[language] || language;
    u.lang = lang;
    const voices = speechSynthesis.getVoices();
    u.voice = voices.find((v) => v.name === voiceName)
      || voices.find((v) => v.lang === lang)
      || voices.find((v) => v.lang.startsWith(lang.slice(0, 2))) || null;
    u.pitch = 1.15;

    // No audio stream to analyse, so fake a mouth: open on each word boundary, flutter while speaking.
    let raf; let target = 0; let speaking = true;
    u.onboundary = () => { target = 1; };
    const tick = (t) => {
      const flutter = 0.35 + 0.25 * Math.sin(t / 55);
      target *= 0.9;
      lipsync.level = speaking ? Math.min(1, Math.max(flutter * 0.6, target)) : 0;
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    const done = () => { speaking = false; cancelAnimationFrame(raf); lipsync.level = 0; resolve(); };
    u.onend = done; u.onerror = done;
    stopCurrent = () => { speechSynthesis.cancel(); done(); };
    speechSynthesis.speak(u);
  });
}

async function speakServer(text, language, provider, voiceId) {
  const blob = await api.tts({ text, language, provider, voiceId });
  audioCtx ??= new AudioContext();
  if (audioCtx.state === 'suspended') await audioCtx.resume();
  const buffer = await audioCtx.decodeAudioData(await blob.arrayBuffer());
  const src = audioCtx.createBufferSource();
  src.buffer = buffer;
  const analyser = audioCtx.createAnalyser();
  analyser.fftSize = 1024;
  src.connect(analyser);
  analyser.connect(audioCtx.destination);
  const data = new Float32Array(analyser.fftSize);

  return new Promise((resolve) => {
    let raf;
    const tick = () => {
      analyser.getFloatTimeDomainData(data);
      let sum = 0;
      for (const v of data) sum += v * v;
      const rms = Math.sqrt(sum / data.length);
      lipsync.level = lipsync.level * 0.5 + Math.min(1, rms * 6) * 0.5;
      raf = requestAnimationFrame(tick);
    };
    const done = () => { cancelAnimationFrame(raf); lipsync.level = 0; resolve(); };
    src.onended = done;
    stopCurrent = () => { try { src.stop(); } catch { /* already stopped */ } done(); };
    src.start();
    tick();
  });
}
