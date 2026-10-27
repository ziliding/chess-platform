import axios from 'axios';
import api from './api';
import { Analysis } from '../types';

export const analysisService = {
  // null while the game has not been analyzed
  get: async (matchId: string): Promise<Analysis | null> => {
    try {
      const response = await api.get(`/games/${matchId}/analysis`);
      return response.data;
    } catch (err) {
      if (axios.isAxiosError(err) && err.response?.status === 404) return null;
      throw err;
    }
  },

  // Queues an analysis, or returns the one that is already queued or done
  request: async (matchId: string): Promise<Analysis> => {
    const response = await api.post(`/games/${matchId}/analysis`);
    return response.data;
  },
};
