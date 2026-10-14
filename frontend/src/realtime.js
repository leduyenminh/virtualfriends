import { Client } from '@stomp/stompjs';
import { freshToken } from './api.js';

let client;

export function connectRealtime(onEvent) {
  const url = import.meta.env.VITE_WS_URL
    ?? `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`;
  client = new Client({
    brokerURL: url,
    reconnectDelay: 3000,
    heartbeatIncoming: 20000,
    heartbeatOutgoing: 20000,
    // Runs before every (re)connect: access tokens live 15 minutes, so always present a fresh one.
    beforeConnect: async () => {
      try {
        client.connectHeaders = { Authorization: `Bearer ${await freshToken()}` };
      } catch {
        await client.deactivate(); // session is gone; App shows the sign-in screen
      }
    },
    onConnect: () => {
      client.subscribe('/user/queue/events', (frame) => onEvent(JSON.parse(frame.body)));
    },
  });
  client.activate();
  return () => client.deactivate();
}

export function sendTyping(conversationId, typing) {
  if (client?.connected) {
    client.publish({ destination: '/app/typing', body: JSON.stringify({ conversationId, typing }) });
  }
}
