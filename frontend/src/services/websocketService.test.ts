import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { WebSocketService, subscribeToAnalysis } from './websocketService';
import type { ChatMessage, GameState } from '../types';

interface TestClient {
  brokerURL: string;
  active: boolean;
  connected: boolean;
  onConnect: () => void;
  subscriptions: Map<string, (message: { body: string }) => void>;
  activate: ReturnType<typeof vi.fn>;
  deactivate: ReturnType<typeof vi.fn>;
  publish: ReturnType<typeof vi.fn>;
}

const stomp = vi.hoisted(() => ({ clients: [] as unknown[] }));

vi.mock('@stomp/stompjs', () => ({
  Client: class {
    brokerURL: string;
    active = false;
    connected = false;
    onConnect = () => {};
    onStompError = () => {};
    subscriptions = new Map<string, (message: { body: string }) => void>();
    activate = vi.fn();
    deactivate = vi.fn();
    publish = vi.fn();
    subscribe = vi.fn((destination: string, callback: (message: { body: string }) => void) => {
      this.subscriptions.set(destination, callback);
    });

    constructor({ brokerURL }: { brokerURL: string }) {
      this.brokerURL = brokerURL;
      stomp.clients.push(this);
    }
  },
}));

function latestClient(): TestClient {
  return stomp.clients[stomp.clients.length - 1] as TestClient;
}

describe('WebSocketService', () => {
  beforeEach(() => {
    stomp.clients.length = 0;
    vi.stubGlobal('window', { location: { protocol: 'http:', host: 'localhost:3000' } });
    vi.spyOn(console, 'log').mockImplementation(() => {});
    vi.spyOn(console, 'warn').mockImplementation(() => {});
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it('connects through the current page origin with the matching WebSocket protocol', () => {
    const service = new WebSocketService();
    service.connect('game-1', 'alice', vi.fn(), vi.fn());
    expect(latestClient().brokerURL).toBe('ws://localhost:3000/ws');

    service.disconnect();
    vi.stubGlobal('window', { location: { protocol: 'https:', host: 'chess.example' } });
    service.connect('game-1', 'alice', vi.fn(), vi.fn());
    expect(latestClient().brokerURL).toBe('wss://chess.example/ws');
  });

  it('subscribes to game, chat, and error messages before joining', () => {
    const service = new WebSocketService();
    const onGameUpdate = vi.fn();
    const onChatMessage = vi.fn();

    service.connect('game-1', 'alice', onGameUpdate, onChatMessage);
    const client = latestClient();
    expect(client.activate).toHaveBeenCalledOnce();

    client.onConnect();
    expect([...client.subscriptions.keys()]).toEqual([
      '/topic/game.game-1',
      '/topic/game.game-1.chat',
      '/user/topic/errors',
    ]);
    expect(client.publish).toHaveBeenCalledWith({ destination: '/app/game/game-1/join', body: '{}' });

    const state: GameState = {
      gameStatus: 'ONGOING',
      fen: 'example',
      turnColor: 'WHITE',
      whiteRemainingMillis: 300_000,
      blackRemainingMillis: 300_000,
      clockRunning: false,
      firstMoveRemainingMillis: 30_000,
    };
    const chat: ChatMessage = { sender: 'bob', message: 'Hi', timestamp: 'now', type: 'CHAT' };
    client.subscriptions.get('/topic/game.game-1')?.({ body: JSON.stringify(state) });
    client.subscriptions.get('/topic/game.game-1.chat')?.({ body: JSON.stringify(chat) });

    expect(onGameUpdate).toHaveBeenCalledExactlyOnceWith(state);
    expect(onChatMessage).toHaveBeenCalledExactlyOnceWith(chat);
  });

  it('shows backend socket errors as system chat messages', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-09-23T12:00:00.000Z'));
    const service = new WebSocketService();
    const onChatMessage = vi.fn();
    service.connect('game-1', 'alice', vi.fn(), onChatMessage);
    const client = latestClient();
    client.onConnect();

    client.subscriptions.get('/user/topic/errors')?.({
      body: JSON.stringify({ error: 'Invalid input: illegal move' }),
    });

    expect(onChatMessage).toHaveBeenCalledExactlyOnceWith({
      sender: 'System',
      message: 'Invalid input: illegal move',
      timestamp: '2026-09-23T12:00:00.000Z',
      type: 'SYSTEM',
    });
  });

  it('does not start another connection while one is active', () => {
    const service = new WebSocketService();
    service.connect('game-1', 'alice', vi.fn(), vi.fn());
    latestClient().active = true;

    service.connect('game-1', 'alice', vi.fn(), vi.fn());

    expect(stomp.clients).toHaveLength(1);
  });

  it('ignores callbacks from a disconnected client after a new game connects', () => {
    const service = new WebSocketService();
    const oldGameUpdate = vi.fn();
    service.connect('game-1', 'alice', oldGameUpdate, vi.fn());
    const oldClient = latestClient();
    oldClient.onConnect();

    service.disconnect();
    service.connect('game-2', 'alice', vi.fn(), vi.fn());
    const newClient = latestClient();
    oldClient.onConnect();
    oldClient.subscriptions.get('/topic/game.game-1')?.({
      body: JSON.stringify({ gameStatus: 'ONGOING', fen: 'example', turnColor: 'WHITE' }),
    });

    expect(oldGameUpdate).not.toHaveBeenCalled();
    expect(newClient.subscriptions.size).toBe(0);
    expect(newClient.publish).not.toHaveBeenCalled();

    newClient.onConnect();
    expect([...newClient.subscriptions.keys()]).toContain('/topic/game.game-2');
    expect(newClient.publish).toHaveBeenCalledWith({
      destination: '/app/game/game-2/join',
      body: '{}',
    });
  });

  it('publishes move, chat, resign, draw, and timeout commands to their destinations', () => {
    const service = new WebSocketService();
    service.connect('game-1', 'alice', vi.fn(), vi.fn());
    const client = latestClient();
    client.connected = true;

    service.sendMove('game-1', 'e7', 'e8', 'q');
    service.sendChatMessage('game-1', 'alice', 'Good game');
    service.resign('game-1', 'WHITE');
    service.offerDraw('game-1');
    service.acceptDraw('game-1');
    service.claimTimeout('game-1');

    expect(client.publish.mock.calls.map(([message]) => message)).toEqual([
      {
        destination: '/app/game/game-1/move',
        body: JSON.stringify({ gameId: 'game-1', moveFrom: 'e7', moveTo: 'e8', promotion: 'q' }),
      },
      {
        destination: '/app/game/game-1/chat',
        body: JSON.stringify({ sender: 'alice', message: 'Good game', type: 'CHAT' }),
      },
      { destination: '/app/game/game-1/resign', body: '{}' },
      { destination: '/app/game/game-1/draw/offer', body: '' },
      { destination: '/app/game/game-1/draw/accept', body: '' },
      { destination: '/app/game/game-1/flag', body: '' },
    ]);
  });

  it('rejects commands without a connection and deactivates on disconnect', () => {
    const service = new WebSocketService();
    expect(() => service.sendMove('game-1', 'e2', 'e4')).toThrow('WebSocket not connected');
    expect(() => service.sendChatMessage('game-1', 'alice', 'Hi')).toThrow('WebSocket not connected');

    service.connect('game-1', 'alice', vi.fn(), vi.fn());
    const client = latestClient();
    service.disconnect();

    expect(client.deactivate).toHaveBeenCalledOnce();
    expect(() => service.offerDraw('game-1')).toThrow('WebSocket not connected');
    expect(() => service.claimTimeout('game-1')).toThrow('WebSocket not connected');
  });
});

describe('subscribeToAnalysis', () => {
  beforeEach(() => {
    stomp.clients.length = 0;
    vi.stubGlobal('window', { location: { protocol: 'https:', host: 'chess.example' } });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('listens to the private analysis queue without joining a game', () => {
    const onUpdate = vi.fn();
    subscribeToAnalysis(onUpdate, vi.fn());
    const client = latestClient();

    expect(client.brokerURL).toBe('wss://chess.example/ws');
    expect(client.activate).toHaveBeenCalled();

    client.onConnect();
    expect([...client.subscriptions.keys()]).toEqual(['/user/topic/analysis']);
    expect(client.publish).not.toHaveBeenCalled();

    client.subscriptions.get('/user/topic/analysis')?.({ body: '{"matchId":"m1","status":"COMPLETED"}' });
    expect(onUpdate).toHaveBeenCalledWith({ matchId: 'm1', status: 'COMPLETED' });
  });

  it('reports every connection, so the caller can read the state again after a reconnect', () => {
    const onConnected = vi.fn();
    subscribeToAnalysis(vi.fn(), onConnected);
    const client = latestClient();

    client.onConnect();
    client.onConnect();

    expect(onConnected).toHaveBeenCalledTimes(2);
  });

  it('closes the connection', () => {
    const close = subscribeToAnalysis(vi.fn(), vi.fn());
    close();
    expect(latestClient().deactivate).toHaveBeenCalled();
  });
});
