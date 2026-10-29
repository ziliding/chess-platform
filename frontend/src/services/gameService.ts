import api from './api';
import { MatchRecord, PageResponse, GameCreatedResponse, GameJoinResponse, GameState, DEFAULT_CLOCK_SETTING } from '../types';

export const gameService = {
  createGame: async (color: 'WHITE' | 'BLACK', clockSetting: string = DEFAULT_CLOCK_SETTING): Promise<GameCreatedResponse> => {
    const response = await api.post('/games', null, {
      params: { color, timeControl: clockSetting }
    });
    return response.data;
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

  getMatchPGN: async (matchId: string): Promise<string> => {
    const response = await api.get(`/matches/${matchId}/pgn`);
    return response.data;
  },
};
