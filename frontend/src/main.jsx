import React from 'react';
import { createRoot } from 'react-dom/client';
import App from './App.jsx';
import './themes.css';

createRoot(document.getElementById('root')).render(<App />);

// Production only: lets the app open without a network (see the vchat-sw plugin in vite.config.js)
if (import.meta.env.PROD && 'serviceWorker' in navigator) {
  window.addEventListener('load', () => navigator.serviceWorker.register('/sw.js').catch(() => {}));
}
