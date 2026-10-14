import React, { useState } from 'react';
import { api } from '../../api.js';
import { useStore } from '../../store.js';

const HELP = {
  VRM: 'A single .vrm file (VRoid Studio, or Blender with the VRM add-on).',
  GLTF: 'A .glb file, or a folder with .gltf + .bin + textures. From Blender: File → Export → glTF 2.0.',
  LIVE2D: 'The Live2D model folder (contains *.model3.json, *.moc3, textures/…). Cubism 4 models.',
  UNITY: 'A Unity WebGL build folder made with the VChatBridge template (compression disabled).',
};

export default function ModelImporter({ onDone }) {
  const [format, setFormat] = useState('VRM');
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const folder = format === 'LIVE2D' || format === 'UNITY';

  async function pick(e) {
    const files = [...e.target.files];
    if (!files.length) return;
    setBusy(true); setError(null);
    try {
      let url;
      if (folder || files.length > 1) {
        const up = await api.uploadBundle(files);
        url = up.url;
        if (format === 'LIVE2D') {
          const entry = files.find((f) => f.name.endsWith('.model3.json'));
          if (!entry) throw new Error('No .model3.json found in that folder');
          url = `${up.url}/${entry.webkitRelativePath.split('/').slice(1).join('/')}`;
        } else if (format === 'GLTF') {
          const entry = files.find((f) => /\.(gltf|glb)$/i.test(f.name));
          url = `${up.url}/${(entry.webkitRelativePath || entry.name).split('/').slice(entry.webkitRelativePath ? 1 : 0).join('/')}`;
        }
      } else {
        url = (await api.upload('MODEL', files[0])).url;
      }
      const avatar = await api.importAvatar({ name: name || files[0].name.replace(/\.\w+$/, ''), format, url });
      useStore.setState((s) => ({ avatars: [...s.avatars, avatar] }));
      onDone(avatar);
    } catch (err) { setError(err.message); } finally { setBusy(false); }
  }

  return (
    <div className="window" style={{ padding: 12, display: 'grid', gap: 8 }}>
      <div style={{ display: 'flex', gap: 8 }}>
        <select className="input" value={format} onChange={(e) => setFormat(e.target.value)}>
          <option value="VRM">VRM</option><option value="GLTF">glTF / GLB (Blender)</option>
          <option value="LIVE2D">Live2D</option><option value="UNITY">Unity WebGL</option>
        </select>
        <input className="input" placeholder="Name" value={name} onChange={(e) => setName(e.target.value)} />
      </div>
      <div className="muted" style={{ fontSize: 12 }}>{HELP[format]}</div>
      <input type="file" disabled={busy} onChange={pick}
        {...(folder ? { webkitdirectory: '', directory: '' } : { accept: '.vrm,.glb,.gltf,.bin,.png,.jpg', multiple: true })} />
      {busy && <div className="muted">Uploading…</div>}
      {error && <div style={{ color: '#e5484d' }}>{error}</div>}
      <button className="btn" onClick={() => onDone(null)}>Cancel</button>
    </div>
  );
}
