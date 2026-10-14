import React, { useState } from 'react';
import DOMPurify from 'dompurify';
import { api } from '../../api.js';
import { useStore } from '../../store.js';
import Modal from '../Modal.jsx';
import StickerMaker from './StickerMaker.jsx';
import { pickFromGoogleDrive, pickFromOneDrive, cloudConfigured } from './cloudPickers.js';

const ACCEPT = 'image/png,image/svg+xml,image/gif,image/webp';

/** SVGs can carry scripts; strip them before upload (the media origin is sandboxed too, this is defence in depth). */
async function sanitize(file) {
  if (file.type !== 'image/svg+xml') return file;
  const clean = DOMPurify.sanitize(await file.text(), { USE_PROFILES: { svg: true, svgFilters: true } });
  return new File([clean], file.name, { type: 'image/svg+xml' });
}

export default function StickerManager({ onClose }) {
  const { packs } = useStore();
  const [packId, setPackId] = useState(packs[0]?.id);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [making, setMaking] = useState(false);
  const pack = packs.find((p) => p.id === packId);

  const refresh = async () => useStore.setState({ packs: await api.stickerPacks() });

  async function newPack() {
    const name = prompt('Pack name', 'My stickers');
    if (!name) return;
    const p = await api.createPack(name);
    await refresh();
    setPackId(p.id);
  }

  async function addFiles(files) {
    if (!pack) { setError('Create a pack first'); return; }
    setBusy(true); setError(null);
    try {
      for (const raw of files) {
        if (!ACCEPT.split(',').includes(raw.type)) throw new Error(`${raw.name}: only PNG, SVG, GIF, WEBP`);
        const f = await sanitize(raw);
        const up = await api.upload('STICKER', f);
        await api.addSticker(pack.id, { name: f.name.replace(/\.\w+$/, ''), url: up.url, mimeType: up.mimeType });
      }
      await refresh();
    } catch (e) { setError(e.message); } finally { setBusy(false); }
  }

  const fromCloud = (fn) => async () => {
    try { addFiles(await fn(ACCEPT)); } catch (e) { setError(e.message); }
  };

  return (
    <Modal title="Sticker packs" onClose={onClose} width={640}>
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        <select className="input" style={{ flex: 1 }} value={packId ?? ''} onChange={(e) => setPackId(e.target.value)}>
          {packs.map((p) => <option key={p.id} value={p.id}>{p.name} ({p.stickers.length})</option>)}
        </select>
        <button className="btn" onClick={newPack}>+ New pack</button>
      </div>
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        <label className="btn primary">
          From this device
          <input type="file" accept={ACCEPT} multiple hidden onChange={(e) => addFiles([...e.target.files])} />
        </label>
        <button className="btn" disabled={!cloudConfigured.google} onClick={fromCloud(pickFromGoogleDrive)}>Google Drive</button>
        <button className="btn" disabled={!cloudConfigured.onedrive} onClick={fromCloud(pickFromOneDrive)}>OneDrive</button>
        <button className="btn" onClick={() => setMaking(true)}>✏️ Create sticker</button>
      </div>
      {busy && <div className="muted">Uploading…</div>}
      {error && <div style={{ color: '#e5484d' }}>{error}</div>}
      {making && <StickerMaker onCancel={() => setMaking(false)} onSave={(f) => { setMaking(false); addFiles([f]); }} />}
      <div className="sticker-grid" style={{ gridTemplateColumns: 'repeat(6, 1fr)' }}>
        {pack?.stickers.map((s) => <img key={s.id} src={s.url} alt={s.name} title={s.name} />)}
      </div>
    </Modal>
  );
}
