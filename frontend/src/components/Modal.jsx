import React from 'react';

export default function Modal({ title, onClose, children, width }) {
  return (
    <div className="modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="window modal" style={width ? { width } : undefined}>
        <div className="titlebar"><h2>{title}</h2><button className="btn icon" onClick={onClose}>✕</button></div>
        <div className="body">{children}</div>
      </div>
    </div>
  );
}
