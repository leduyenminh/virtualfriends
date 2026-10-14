import React, { useEffect, useRef, useState } from 'react';

/** Icon button that opens a dropdown; closes on outside click, Escape, or after picking an item. */
export default function Menu({ icon, title, align, children }) {
  const [open, setOpen] = useState(false);
  const ref = useRef(null);

  useEffect(() => {
    if (!open) return undefined;
    const onDown = (e) => { if (!ref.current?.contains(e.target)) setOpen(false); };
    const onKey = (e) => { if (e.key === 'Escape') setOpen(false); };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => { document.removeEventListener('mousedown', onDown); document.removeEventListener('keydown', onKey); };
  }, [open]);

  return (
    <div className="menu-wrap" ref={ref}>
      <button type="button" className={`icon-btn ${open ? 'on' : ''}`} title={title} aria-label={title} aria-haspopup="menu"
        aria-expanded={open} onClick={() => setOpen(!open)}>{icon}</button>
      {open && <div className={`menu ${align === 'left' ? 'left' : ''}`} role="menu" onClick={() => setOpen(false)}>{children}</div>}
    </div>
  );
}
