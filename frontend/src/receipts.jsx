/**
 * Read state of one of MY messages: 'read' (✓✓ blue) once every other member has read past it, else 'sent' (✓).
 * AI friends "read" a message when they reply after it.
 */
export function readState(conversation, message, meId, laterBotReply = false) {
  if (!message || message.senderId !== meId || message.recalled) return null;
  if (conversation.type === 'BOT') return laterBotReply ? 'read' : 'sent';
  const others = conversation.members.filter((u) => u.id !== meId);
  if (!others.length) return 'sent';
  const t = new Date(message.createdAt);
  const readBy = conversation.readBy || {};
  return others.every((u) => readBy[u.id] && new Date(readBy[u.id]) >= t) ? 'read' : 'sent';
}

export function Ticks({ state }) {
  if (!state) return null;
  return <span className={`tick ${state === 'read' ? 'read' : ''}`} aria-label={state === 'read' ? 'Read' : 'Sent'}>{state === 'read' ? '✓✓' : '✓'}</span>;
}
