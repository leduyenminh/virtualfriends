import React, { useEffect, useRef } from 'react';
import { lipsync } from '../../tts/speech.js';

/**
 * Unity WebGL avatar, isolated in a sandboxed iframe (opaque origin: it cannot read the app's token or DOM).
 * The build must use the template in /unity/WebGLTemplates/VChatBridge, which forwards these postMessages to the
 * AvatarBridge GameObject via SendMessage.
 */
export default function UnityAvatar({ url, emotion }) {
  const frame = useRef(null);
  const post = (method, value) => frame.current?.contentWindow?.postMessage({ vchat: true, method, value }, '*');

  useEffect(() => { post('SetEmotion', emotion); }, [emotion]);

  useEffect(() => {
    let raf; let last = -1;
    const tick = () => {
      const v = Math.round(lipsync.level * 20) / 20; // quantize to avoid spamming SendMessage
      if (v !== last) { post('SetMouth', v); last = v; }
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, []);

  return <iframe ref={frame} title="Unity avatar" src={`${url.replace(/\/$/, '')}/index.html`} sandbox="allow-scripts" />;
}
