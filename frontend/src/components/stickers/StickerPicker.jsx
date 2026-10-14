import React, { useState } from 'react';
import { useStore } from '../../store.js';

export default function StickerPicker({ onPick }) {
  const { packs } = useStore();
  const [tab, setTab] = useState(packs[0]?.id);
  const pack = packs.find((p) => p.id === tab) ?? packs[0];
  return (
    <div className="popover">
      <div className="tabs">
        {packs.map((p) => <button key={p.id} type="button" className={p.id === pack?.id ? 'on' : ''} onClick={() => setTab(p.id)}>{p.name}</button>)}
      </div>
      {pack?.stickers.length ? (
        <div className="sticker-grid">
          {pack.stickers.map((s) => <img key={s.id} src={s.url} alt={s.name} title={s.name} onClick={() => onPick(s)} />)}
        </div>
      ) : (
        <div className="muted" style={{ padding: 16 }}>No stickers yet. Open “Stickers” in the sidebar to import or create some.</div>
      )}
    </div>
  );
}
