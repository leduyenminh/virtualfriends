import React from 'react';

const PALETTE = ['#25a58a', '#5b8def', '#e5737d', '#f0a53a', '#9c6ade', '#3fb6c6', '#d16aa6', '#6c9b3d'];

function colorFor(name = '') {
  let h = 0;
  for (const ch of name) h = (h * 31 + ch.codePointAt(0)) >>> 0;
  return PALETTE[h % PALETTE.length];
}

export default function Avatar({ name, url, size }) {
  const cls = `avatar-dot ${size === 'sm' ? 'sm' : ''}`;
  if (url) return <div className={cls}><img src={url} alt="" referrerPolicy="no-referrer" /></div>;
  const initials = (name || '?').trim().split(/\s+/).slice(0, 2).map((w) => w[0]).join('').toUpperCase();
  return <div className={cls} style={{ background: colorFor(name) }} aria-hidden="true">{initials}</div>;
}
