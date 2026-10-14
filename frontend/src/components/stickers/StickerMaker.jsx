import React, { useEffect, useRef, useState } from 'react';

const SIZE = 512;

/** Tiny sticker editor: pick an image, add a caption with a thick outline, export a 512×512 PNG. */
export default function StickerMaker({ onSave, onCancel }) {
  const canvas = useRef(null);
  const [img, setImg] = useState(null);
  const [caption, setCaption] = useState('');
  const [color, setColor] = useState('#ffffff');
  const [outline, setOutline] = useState(true);

  useEffect(() => {
    const ctx = canvas.current.getContext('2d');
    ctx.clearRect(0, 0, SIZE, SIZE);
    if (img) {
      const s = Math.min(SIZE / img.width, SIZE / img.height) * 0.9;
      const w = img.width * s; const h = img.height * s;
      if (outline) {
        // white die-cut border: stamp the image offset in 16 directions, tinted white
        ctx.save();
        for (let a = 0; a < Math.PI * 2; a += Math.PI / 8) ctx.drawImage(img, (SIZE - w) / 2 + Math.cos(a) * 8, (SIZE - h) / 2 + Math.sin(a) * 8, w, h);
        ctx.globalCompositeOperation = 'source-in';
        ctx.fillStyle = '#fff';
        ctx.fillRect(0, 0, SIZE, SIZE);
        ctx.restore();
      }
      ctx.drawImage(img, (SIZE - w) / 2, (SIZE - h) / 2, w, h);
    } else {
      ctx.fillStyle = '#ffd6e8';
      ctx.beginPath(); ctx.arc(SIZE / 2, SIZE / 2, SIZE * 0.4, 0, Math.PI * 2); ctx.fill();
    }
    if (caption) {
      ctx.font = 'bold 64px "Segoe UI", sans-serif';
      ctx.textAlign = 'center';
      ctx.lineJoin = 'round';
      ctx.lineWidth = 12;
      ctx.strokeStyle = '#000';
      ctx.strokeText(caption, SIZE / 2, SIZE - 40);
      ctx.fillStyle = color;
      ctx.fillText(caption, SIZE / 2, SIZE - 40);
    }
  }, [img, caption, color, outline]);

  function load(e) {
    const f = e.target.files?.[0];
    if (!f) return;
    const i = new Image();
    i.onload = () => setImg(i);
    i.src = URL.createObjectURL(f);
  }

  function save() {
    canvas.current.toBlob((b) => onSave(new File([b], `${caption || 'sticker'}.png`, { type: 'image/png' })), 'image/png');
  }

  return (
    <div className="window" style={{ padding: 12, display: 'grid', gap: 8, gridTemplateColumns: '200px 1fr' }}>
      <canvas ref={canvas} width={SIZE} height={SIZE} style={{ width: 200, height: 200, background: 'repeating-conic-gradient(#eee 0 25%, #fff 0 50%) 0 0/20px 20px' }} />
      <div style={{ display: 'grid', gap: 8, alignContent: 'start' }}>
        <input type="file" accept="image/*" onChange={load} />
        <input className="input" placeholder="Caption (e.g. GG!, Cảm ơn, ありがとう)" value={caption} onChange={(e) => setCaption(e.target.value)} />
        <label><input type="color" value={color} onChange={(e) => setColor(e.target.value)} /> Text color</label>
        <label><input type="checkbox" checked={outline} onChange={(e) => setOutline(e.target.checked)} /> White die-cut border</label>
        <div style={{ display: 'flex', gap: 8 }}>
          <button className="btn" onClick={onCancel}>Cancel</button>
          <button className="btn primary" onClick={save}>Add to pack</button>
        </div>
      </div>
    </div>
  );
}
