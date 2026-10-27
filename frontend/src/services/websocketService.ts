import { Client, IMessage } from '@stomp/stompjs';
import { Analysis, GameState, ChatMessage } from '../types';

export class WebSocketService {
  private client: Client | null = null;
  
  connect(gameId: string, _username: string, onGameUpdate: (state: GameState) => void, onChatMessage: (message: ChatMessage) => void) {
    if (this.client && this.client.active) {
      console.warn('WebSocket already active, skip connect');
      return;
    }

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const client = new Client({
      brokerURL: `${protocol}//${window.location.host}/ws`,
      debug: (str) => {
        console.log('STOMP: ' + str);
      },
      reconnectDelay: 5000,
      heartbeatIncoming: 4000,
      heartbeatOutgoing: 4000
    });
    this.client = client;

    client.onConnect = () => {
      if (this.client !== client) return;
      console.log('WebSocket connected');
      
      // Subscribe to game updates
      client.subscribe(`/topic/game.${gameId}`, (message: IMessage) => {
        if (this.client !== client) return;
        const gameState: GameState = JSON.parse(message.body);
        onGameUpdate(gameState);
      });

      // Subscribe to chat messages
      client.subscribe(`/topic/game.${gameId}.chat`, (message: IMessage) => {
        if (this.client !== client) return;
        const chatMessage: ChatMessage = JSON.parse(message.body);
        onChatMessage(chatMessage);
      });

      client.subscribe(`/user/topic/errors`, (message: IMessage) => {
        if (this.client !== client) return;
        const { error }: { error: string } = JSON.parse(message.body);
        onChatMessage({
          sender: 'System',
          message: error,
          timestamp: new Date().toISOString(),
          type: 'SYSTEM',
        });
      });

      // Send join message
      client.publish({
        destination: `/app/game/${gameId}/join`,
        body: '{}'
      });
    };

    client.onStompError = (frame) => {
      console.error('STOMP error:', frame);
    };

    client.activate();
  }

  disconnect() {
    const client = this.client;
    this.client = null;
    if (client) void client.deactivate();
  }

  sendMove(gameId: string, moveFrom: string, moveTo: string, promotion?: string) {
    if (!this.client || !this.client.connected) {
      throw new Error('WebSocket not connected');
    }

    this.client.publish({
      destination: `/app/game/${gameId}/move`,
      body: JSON.stringify({
        gameId,
        moveFrom,
        moveTo,
        promotion,
      }),
    });
  }

  sendChatMessage(gameId: string, sender: string, message: string) {
    if (!this.client || !this.client.connected) {
      throw new Error('WebSocket not connected');
    }

    this.client.publish({
      destination: `/app/game/${gameId}/chat`,
      body: JSON.stringify({
        sender,
        message,
        type: 'CHAT',
      }),
    });
  }

  resign(gameId: string, _playerColor: string) {
    if (!this.client || !this.client.connected) {
      throw new Error('WebSocket not connected');
    }

    this.client.publish({
      destination: `/app/game/${gameId}/resign`,
      body: '{}'
    });
  }

  // Ask the server to end the game because the side to move has run out of time; it checks its own clock
  claimTimeout(gameId: string) {
    if (!this.client || !this.client.connected) {
      throw new Error('WebSocket not connected');
    }

    this.client.publish({
      destination: `/app/game/${gameId}/flag`,
      body: '',
    });
  }

  offerDraw(gameId: string) {
    if (!this.client || !this.client.connected) {
      throw new Error('WebSocket not connected');
    }

    this.client.publish({
      destination: `/app/game/${gameId}/draw/offer`,
      body: '',
    });
  }

  acceptDraw(gameId: string) {
    if (!this.client || !this.client.connected) {
      throw new Error('WebSocket not connected');
    }

    this.client.publish({
      destination: `/app/game/${gameId}/draw/accept`,
      body: '',
    });
  }
}

export const websocketService = new WebSocketService();

// Analysis results are pushed to the player's private /user/topic/analysis. This is a separate connection from a
// game's: it joins no game. Returns a function that closes it.
export function subscribeToAnalysis(onUpdate: (analysis: Analysis) => void, onConnected: () => void): () => void {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  const client = new Client({
    brokerURL: `${protocol}//${window.location.host}/ws`,
    reconnectDelay: 5000,
    heartbeatIncoming: 4000,
    heartbeatOutgoing: 4000,
  });
  // Runs again after every reconnection, which drops the subscription
  client.onConnect = () => {
    client.subscribe('/user/topic/analysis', (message: IMessage) => {
      onUpdate(JSON.parse(message.body));
    });
    onConnected();
  };
  client.activate();
  return () => void client.deactivate();
}
