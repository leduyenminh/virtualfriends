import React, { useState } from 'react';
import { useStore } from '../../store.js';
import { stopSpeaking } from '../../tts/speech.js';
import AvatarStage from './AvatarStage.jsx';
import BotStudio from '../BotStudio.jsx';

const EMOJI = { neutral: '😐', happy: '😊', sad: '😢', angry: '😠', surprised: '😲', shy: '😳' };

export default function AvatarPanel({ bot }) {
  const { botFace, autoSpeak } = useStore();
  const [editing, setEditing] = useState(false);
  if (!bot) return null;
  return (
    <aside className="window avatar-panel">
      <div className="titlebar">
        <h2>{bot.name}</h2>
        <button className="icon-btn" title={autoSpeak ? 'Mute voice' : 'Unmute voice'}
          onClick={() => { stopSpeaking(); useStore.setState({ autoSpeak: !autoSpeak }); }}>{autoSpeak ? '🔊' : '🔇'}</button>
        <button className="icon-btn" title="Edit character" onClick={() => setEditing(true)}>⚙️</button>
        <button className="icon-btn" title="Close" onClick={() => useStore.setState({ showAvatar: false })}>✕</button>
      </div>
      <div className="stage">
        <AvatarStage avatar={bot.avatar} emotion={botFace.emotion} speaking={botFace.speaking} />
        <span className="emotion-chip">{EMOJI[botFace.emotion] ?? '🙂'} {botFace.emotion}{botFace.speaking ? ' · speaking' : ''}</span>
      </div>
      <div style={{ padding: 12, fontSize: 12 }} className="muted">
        Voice: {bot.ttsProvider.toLowerCase()} · Language: {bot.language} · Model: {bot.avatar?.format?.toLowerCase() ?? 'stick'}
      </div>
      {editing && <BotStudio bot={bot} onClose={() => setEditing(false)} />}
    </aside>
  );
}
