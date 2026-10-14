import React from 'react';
import { useStore } from '../store.js';

export const THEMES = [
  { id: 'light', label: '☀️ Light' },
  { id: 'dark', label: '🌙 Dark' },
  { id: 'win95', label: '🖥️ Windows 95' },
  { id: 'aero', label: '🫧 Frutiger Aero' },
];

export default function ThemePicker() {
  const { theme, setTheme } = useStore();
  return (
    <select className="input" style={{ width: 'auto' }} value={theme} onChange={(e) => setTheme(e.target.value)} aria-label="Theme">
      {THEMES.map((t) => <option key={t.id} value={t.id}>{t.label}</option>)}
    </select>
  );
}
