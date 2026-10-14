import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In dev, proxy API/WebSocket/OAuth/local files to Spring Boot so everything is same-origin (as behind CloudFront).
// VCHAT_API overrides the backend address, e.g. VCHAT_API=http://localhost:8081
const api = process.env.VCHAT_API ?? 'http://localhost:8080';

/**
 * Content-Security-Policy for the production build: the main defence against XSS. Even if an attacker manages to
 * inject markup, the browser refuses to run any script that isn't one of our bundles or an allow-listed SDK.
 * Not applied in dev because Vite's hot reload injects inline scripts.
 */
const CSP = [
  "default-src 'self'",
  // our bundles + Live2D Cubism core + Google Picker / Identity + OneDrive picker (cloud sticker import)
  "script-src 'self' https://cubism.live2d.com https://apis.google.com https://accounts.google.com https://www.gstatic.com https://js.live.net",
  "style-src 'self' 'unsafe-inline' https://accounts.google.com", // React style={{...}} attributes
  "img-src 'self' data: blob: https:",           // stickers/avatars from the media CDN, OAuth profile pictures
  "media-src 'self' data: blob: https:",         // voice notes, TTS audio
  "font-src 'self' data:",
  "connect-src 'self' https: wss:",              // API + WebSocket (same origin), media CDN, Google Drive/OneDrive downloads
  "frame-src 'self' https:",                     // sandboxed Unity iframe on the media domain, Google/OneDrive pickers
  "worker-src 'self' blob:",
  "object-src 'none'",
  "base-uri 'self'",
  "form-action 'self'",
].join('; ');

const cspPlugin = {
  name: 'vchat-csp',
  apply: 'build',
  transformIndexHtml: () => [{ tag: 'meta', attrs: { 'http-equiv': 'Content-Security-Policy', content: CSP }, injectTo: 'head-prepend' }],
};

const proxy = {
  '/api': api,
  '/files': api,
  // Google/Facebook sign-in: the redirect + callback must go through the same origin as the app
  '/oauth2': api,
  '/login/oauth2': api,
  '/ws': { target: api.replace(/^http/, 'ws'), ws: true },
};

export default defineConfig({
  plugins: [react(), cspPlugin],
  server: { port: Number(process.env.PORT ?? 5173), proxy },
  // `npm run preview` serves the production build (with the CSP) through the same proxy
  preview: { port: Number(process.env.PORT ?? 4173), proxy },
});
