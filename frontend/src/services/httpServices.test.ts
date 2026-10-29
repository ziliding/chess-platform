import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AxiosError, type AxiosAdapter, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import api from './api';
import { apiErrorMessage } from './errors';
import { friendService } from './friendService';
import { gameService } from './gameService';
import { userService } from './userService';

const user = { id: 'user-1', username: 'alice', email: 'alice@example.com' };

describe('HTTP services', () => {
  const originalAdapter = api.defaults.adapter;
  let requests: InternalAxiosRequestConfig[];
  let responseData: unknown;

  beforeEach(() => {
    requests = [];
    responseData = undefined;
    const adapter: AxiosAdapter = async (config) => {
      requests.push(config);
      return { data: responseData, status: 200, statusText: 'OK', headers: {}, config };
    };
    api.defaults.adapter = adapter;
  });

  afterEach(() => {
    api.defaults.adapter = originalAdapter;
    vi.unstubAllGlobals();
  });

  it('sends login credentials and returns the authenticated user', async () => {
    responseData = user;

    await expect(userService.login('alice@example.com', 'secret')).resolves.toEqual(user);
    expect(requests).toHaveLength(1);
    expect(requests[0]).toMatchObject({
      baseURL: '/api',
      method: 'post',
      url: '/login',
      data: JSON.stringify({ username: 'alice@example.com', password: 'secret' }),
      withCredentials: true,
    });
  });

  it('uses the expected request body for registration and profile updates', async () => {
    responseData = user;
    const registration = { username: 'alice', email: 'alice@example.com', rawPassword: 'secret' };

    await expect(userService.createUser(registration)).resolves.toEqual(user);
    await expect(userService.updateUser({ about: 'Chess player' })).resolves.toEqual(user);

    expect(requests.map(({ method, url, data }) => ({ method, url, data }))).toEqual([
      { method: 'post', url: '/users', data: JSON.stringify(registration) },
      { method: 'put', url: '/users', data: JSON.stringify({ about: 'Chess player' }) },
    ]);
  });

  it('passes search and pagination parameters through to user and game endpoints', async () => {
    const page = { content: [user], page: { size: 5, number: 2, totalElements: 1, totalPages: 1 } };
    responseData = page;

    await expect(userService.searchUsers('ali', 2, 5)).resolves.toEqual(page);
    await expect(gameService.getGames(user.id, 2, 5)).resolves.toEqual(page);
    await expect(friendService.getFriends(2, 5)).resolves.toEqual(page);

    expect(requests.map(({ url, params }) => ({ url, params }))).toEqual([
      { url: '/users', params: { search: 'ali', page: 2, size: 5 } },
      { url: '/games/users/user-1', params: { page: 2, size: 5 } },
      { url: '/friends', params: { page: 2, size: 5 } },
    ]);
  });

  it('uses the game API routes for creation, joining, state, and PGN', async () => {
    responseData = { gameId: 'game-1' };

    await gameService.createGame('BLACK');
    await gameService.createGame('WHITE', '3+2');
    await gameService.joinGame('game-1');
    await gameService.getGameState('game-1');
    await gameService.getMatchPGN('match-1');

    expect(requests.map(({ method, url, params }) => ({ method, url, params }))).toEqual([
      { method: 'post', url: '/games', params: { color: 'BLACK', timeControl: '5+3' } },
      { method: 'post', url: '/games', params: { color: 'WHITE', timeControl: '3+2' } },
      { method: 'post', url: '/games/game-1/join', params: undefined },
      { method: 'get', url: '/games/game-1/state', params: undefined },
      { method: 'get', url: '/matches/match-1/pgn', params: undefined },
    ]);
  });

  it('fetches a user\'s ratings by id', async () => {
    const ratings = [{ timeControl: 'BLITZ', rating: 1250, gamesPlayed: 12, peakRating: 1290 }];
    responseData = ratings;

    await expect(userService.getRatings('user-1')).resolves.toEqual(ratings);
    expect(requests.map(({ method, url }) => ({ method, url }))).toEqual([
      { method: 'get', url: '/users/user-1/ratings' },
    ]);
  });

  it('uses the friend request routes and methods for each action', async () => {
    responseData = { friendRequestId: 'request-1' };

    await friendService.getReceivedRequests();
    await friendService.getSentRequests();
    await friendService.sendFriendRequest('user-2');
    await friendService.acceptFriendRequest('request-1');
    await friendService.rejectFriendRequest('request-1');
    await friendService.removeFriend('user-2');

    expect(requests.map(({ method, url, params }) => ({ method, url, params }))).toEqual([
      { method: 'get', url: '/friends/received', params: { page: 0, size: 10 } },
      { method: 'get', url: '/friends/sent', params: { page: 0, size: 10 } },
      { method: 'post', url: '/friends/send/user-2', params: undefined },
      { method: 'post', url: '/friends/accept/request-1', params: undefined },
      { method: 'put', url: '/friends/reject/request-1', params: undefined },
      { method: 'delete', url: '/friends/user-2', params: undefined },
    ]);
  });

  it('uses the session and account endpoints', async () => {
    responseData = user;

    await expect(userService.getCurrentUser()).resolves.toEqual(user);
    await userService.logout();
    await userService.deleteUser();

    expect(requests.map(({ method, url }) => ({ method, url }))).toEqual([
      { method: 'get', url: '/me' },
      { method: 'post', url: '/logout' },
      { method: 'delete', url: '/users' },
    ]);
  });

  it('redirects an expired session to login and preserves public auth pages', async () => {
    const location = { pathname: '/dashboard', href: '/dashboard' };
    vi.stubGlobal('window', { location });
    api.defaults.adapter = async (config) => {
      const response: AxiosResponse = {
        data: { error: 'Session expired' },
        status: 401,
        statusText: 'Unauthorized',
        headers: {},
        config,
      };
      throw new AxiosError('Unauthorized', AxiosError.ERR_BAD_RESPONSE, config, undefined, response);
    };

    await expect(userService.getCurrentUser()).rejects.toBeInstanceOf(AxiosError);
    expect(location.href).toBe('/login');

    location.pathname = '/register';
    location.href = '/register';
    await expect(userService.getCurrentUser()).rejects.toBeInstanceOf(AxiosError);
    expect(location.href).toBe('/register');
  });

  it.each([
    [{ detail: 'User not found', title: 'Not Found', status: 404 }, 'User not found'],
    [{ detail: 'Validation failed', errors: { username: ['Too short'] } }, 'Validation failed'],
    [{ title: 'Service Unavailable' }, 'Service Unavailable'],
    [{ detail: 'New format', error: 'Old format' }, 'New format'],
    [{ detail: { nested: 'invalid' }, error: 123 }, 'Request failed'],
    [{ detail: '   ' }, 'Request failed'],
    ['<html>Bad Gateway</html>', 'Request failed'],
    [null, 'Request failed'],
  ])('reads problem details and tolerates unexpected bodies: %j', (data, expected) => {
    const response = { data, status: 400 } as AxiosResponse;
    const error = new AxiosError('Request error', AxiosError.ERR_BAD_RESPONSE, undefined, undefined, response);
    expect(apiErrorMessage(error, 'Request failed')).toBe(expected);
  });

  it('extracts API error messages and falls back for other errors', () => {
    const response = { data: { error: 'Name already taken' }, status: 409 } as AxiosResponse;
    const error = new AxiosError('Conflict', AxiosError.ERR_BAD_RESPONSE, undefined, undefined, response);

    expect(apiErrorMessage(error, 'Request failed')).toBe('Name already taken');
    const legacyResponse = { data: { message: 'Legacy error' }, status: 400 } as AxiosResponse;
    const legacyError = new AxiosError('Bad request', AxiosError.ERR_BAD_RESPONSE, undefined, undefined, legacyResponse);
    expect(apiErrorMessage(legacyError, 'Request failed')).toBe('Legacy error');
    expect(apiErrorMessage(new AxiosError('Network error'), 'Request failed')).toBe('Request failed');
    expect(apiErrorMessage(new Error('Unexpected'), 'Request failed')).toBe('Request failed');
  });
});
