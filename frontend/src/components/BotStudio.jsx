import React, { useEffect, useState } from 'react';
import { api } from '../api.js';
import { useStore } from '../store.js';
import Modal from './Modal.jsx';
import ModelImporter from './avatar/ModelImporter.jsx';
import { BROWSER_LANGS } from '../tts/speech.js';

const LANGS = [
  ['vi', 'Tiếng Việt'], ['en', 'English'], ['zh', '中文'], ['ja', '日本語'], ['ko', '한국어'], ['es', 'Español'], ['ru', 'Русский'],
];
const PROVIDERS = [
  ['BROWSER', 'Browser voice (free, offline)'],
  ['ELEVENLABS', 'ElevenLabs (multilingual)'],
  ['GOOGLE', 'Google Cloud TTS'],
  ['COEIROINK', 'COEIROINK (Japanese, local engine)'],
];
const PRESET_PERSONA = `Cheerful idol VTuber who streams rhythm games and loves ramen.
Talks with lots of energy, teases the user playfully, never mean. Speaks casually.`;

export default function BotStudio({ onClose, bot }) {
  const { avatars } = useStore();
  const [form, setForm] = useState({
    name: bot?.name ?? 'Mika', persona: bot?.persona ?? PRESET_PERSONA, language: bot?.language ?? 'en',
    ttsProvider: bot?.ttsProvider ?? 'BROWSER', voiceId: bot?.voiceId ?? '', avatarModelId: bot?.avatar?.id ?? avatars[0]?.id,
  });
  const [providers, setProviders] = useState({ BROWSER: true });
  const [voices, setVoices] = useState([]);
  const [importing, setImporting] = useState(false);
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });

  useEffect(() => { api.ttsProviders().then(setProviders).catch(() => {}); }, []);
  useEffect(() => {
    if (form.ttsProvider === 'BROWSER') {
      const lang = BROWSER_LANGS[form.language];
      setVoices(speechSynthesis.getVoices().filter((v) => v.lang.startsWith(lang.slice(0, 2))).map((v) => ({ id: v.name, name: `${v.name} (${v.lang})` })));
    } else if (form.ttsProvider === 'ELEVENLABS' || form.ttsProvider === 'COEIROINK') {
      api.ttsVoices(form.ttsProvider).then(setVoices).catch(() => setVoices([]));
    } else setVoices([]);
  }, [form.ttsProvider, form.language]);

  async function save() {
    const body = { ...form, voiceId: form.voiceId || null };
    const saved = bot ? await api.updateBot(bot.id, body) : await api.createBot(body);
    useStore.setState((s) => ({ bots: [...s.bots.filter((b) => b.id !== saved.id), saved] }));
    if (!bot) {
      const c = await api.createConversation({ type: 'BOT', botId: saved.id });
      useStore.setState((s) => ({ conversations: [c, ...s.conversations] }));
      useStore.getState().open(c.id);
    }
    onClose();
  }

  return (
    <Modal title={bot ? `Edit ${bot.name}` : 'Create an AI friend'} onClose={onClose}>
      <div className="field"><label>Name</label><input className="input" value={form.name} onChange={set('name')} /></div>
      <div className="field"><label>Personality / character sheet</label>
        <textarea className="input" rows={5} value={form.persona} onChange={set('persona')} />
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
        <div className="field"><label>Main language</label>
          <select className="input" value={form.language} onChange={set('language')}>
            {LANGS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
          </select>
        </div>
        <div className="field"><label>Voice engine</label>
          <select className="input" value={form.ttsProvider} onChange={set('ttsProvider')}>
            {PROVIDERS.map(([v, l]) => <option key={v} value={v} disabled={!providers[v]}>{l}{providers[v] ? '' : ' — not configured'}</option>)}
          </select>
        </div>
      </div>
      <div className="field"><label>Voice</label>
        {voices.length ? (
          <select className="input" value={form.voiceId} onChange={set('voiceId')}>
            <option value="">Default</option>
            {voices.map((v) => <option key={v.id} value={v.id}>{v.name}</option>)}
          </select>
        ) : (
          <input className="input" placeholder={form.ttsProvider === 'GOOGLE' ? 'e.g. ja-JP-Neural2-B (optional)' : 'Voice id (optional)'} value={form.voiceId} onChange={set('voiceId')} />
        )}
      </div>
      <div className="field"><label>Avatar</label>
        <div style={{ display: 'flex', gap: 8 }}>
          <select className="input" value={form.avatarModelId ?? ''} onChange={set('avatarModelId')}>
            {avatars.map((a) => <option key={a.id} value={a.id}>{a.name} · {a.format}</option>)}
          </select>
          <button className="btn" onClick={() => setImporting(true)}>Import model…</button>
        </div>
      </div>
      {importing && (
        <ModelImporter onDone={(a) => { setImporting(false); if (a) setForm({ ...form, avatarModelId: a.id }); }} />
      )}
      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
        <button className="btn" onClick={onClose}>Cancel</button>
        <button className="btn primary" onClick={save}>{bot ? 'Save' : 'Create & chat'}</button>
      </div>
    </Modal>
  );
}
