import api from './api';
import { MatchRecord, PageResponse, GameCreatedResponse, GameJoinResponse, GameState, SeekResponse, DEFAULT_CLOCK_SETTING } from '../types';

export const gameService = {
  createGame: async (color: 'WHITE' | 'BLACK', clockSetting: string = DEFAULT_CLOCK_SETTING): Promise<GameCreatedResponse> => {
    const response = await api.post('/games', null, {
      params: { color, timeControl: clockSetting }
    });
    return response.data;
  },

  // Poll about once a second while searching; a seek that stops being polled expires on the server
  seek: async (clockSetting: string): Promise<SeekResponse> => {
    const response = await api.post('/matchmaking', null, { params: { timeControl: clockSetting } });
    return response.data;
  },

  cancelSeek: async (clockSetting: string): Promise<void> => {
    await api.delete('/matchmaking', { params: { timeControl: clockSetting } });
  },

  joinGame: async (gameId: string): Promise<GameJoinResponse> => {
    const response = await api.post(`/games/${gameId}/join`);
    return response.data;
  },

  getGameState: async (gameId: string): Promise<GameState> => {
    const response = await api.get(`/games/${gameId}/state`);
    return response.data;
  },

  getGames: async (userId: string, page: number = 0, size: number = 10): Promise<PageResponse<MatchRecord>> => {
    const response = await api.get(`/games/users/${userId}`, {
      params: { page, size }
    });
    return response.data;
  },

  getGamePGN: async (gameId: string): Promise<string> => {
    const response = await api.get(`/games/${gameId}/pgn`);
    return response.data;
  },
};
