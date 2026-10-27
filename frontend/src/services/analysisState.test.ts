import { describe, expect, it, vi } from 'vitest';
import {
  DEADLINE_GRACE_MS,
  WatchDeps,
  evalText,
  failureNotice,
  mistakes,
  moveLabel,
  requestRefusal,
  viewOf,
  watchAnalysis,
} from './analysisState';
import type { Analysis, AnalysisMove } from '../types';

const move = (ply: number, classification: AnalysisMove['classification'], san = 'e4'): AnalysisMove => ({
  ply,
  san,
  uci: 'e2e4',
  eval: { cp: 30, mate: null },
  bestSan: 'd4',
  bestUci: 'd2d4',
  winChanceLoss: 0,
  classification,
});

const analysis = (status: Analysis['status'], overrides: Partial<Analysis> = {}): Analysis => ({
  matchId: 'match-1',
  status,
  errorCode: null,
  deadline: '2026-10-26T12:03:00Z',
  completedAt: null,
  engine: null,
  initialEval: null,
  moves: null,
  summary: null,
  ...overrides,
});

describe('formatting', () => {
  it('shows evaluations from White’s point of view', () => {
    expect(evalText({ cp: 37, mate: null })).toBe('+0.4');
    expect(evalText({ cp: -120, mate: null })).toBe('-1.2');
    expect(evalText({ cp: 0, mate: null })).toBe('0.0');
    expect(evalText({ cp: null, mate: 3 })).toBe('+M3');
    expect(evalText({ cp: null, mate: -2 })).toBe('-M2');
    expect(evalText({ cp: null, mate: 0 })).toBe('Checkmate');
  });

  it('labels moves with their number, side and mark', () => {
    expect(moveLabel(move(5, 'BEST', 'Qh5'))).toBe('3.Qh5');
    expect(moveLabel(move(6, 'BLUNDER', 'Nf6'))).toBe('3...Nf6??');
    expect(moveLabel(move(2, 'MISTAKE', 'e5'))).toBe('1...e5?');
    expect(moveLabel(move(1, 'INACCURACY', 'e4'))).toBe('1.e4?!');
    expect(moveLabel(move(1, 'GOOD', 'e4'), 'e4+')).toBe('1.e4+');
  });

  it('picks out inaccuracies, mistakes and blunders', () => {
    const all = [move(1, 'BEST'), move(2, 'GOOD'), move(3, 'INACCURACY'), move(4, 'MISTAKE'), move(5, 'BLUNDER')];
    expect(mistakes(all).map((m) => m.ply)).toEqual([3, 4, 5]);
  });
});

describe('degraded states', () => {
  it('says what a failed analysis failed on, and whether trying again can help', () => {
    expect(failureNotice('TIMED_OUT')).toEqual({ text: 'Analysis took too long.', retryable: true });
    expect(failureNotice('ENGINE_ERROR')).toEqual({ text: 'Analysis failed.', retryable: true });
    expect(failureNotice('BUDGET_EXCEEDED')).toEqual({ text: 'Analysis failed.', retryable: true });
    expect(failureNotice('INVALID_PGN')).toEqual({ text: "This game can't be analyzed.", retryable: false });
    expect(failureNotice('TOO_LONG').retryable).toBe(false);
    expect(failureNotice('UNAVAILABLE')).toEqual({
      text: 'Analysis is temporarily unavailable. Try again in a few minutes.',
      retryable: true,
    });
    // A code from a newer server is treated as a temporary problem
    expect(failureNotice('SOMETHING_NEW').retryable).toBe(true);
  });

  it('explains each way a request can be refused', () => {
    expect(requestRefusal(503, 'ANALYSIS_UNAVAILABLE')).toMatch(/temporarily unavailable/);
    expect(requestRefusal(503, 'ANALYSIS_BUSY')).toMatch(/busy/);
    expect(requestRefusal(429, undefined)).toMatch(/3 analyses running/);
    expect(requestRefusal(403, undefined)).toMatch(/players/);
    expect(requestRefusal(undefined, undefined)).toMatch(/Could not request/);
  });

  it('names the view for each analysis state', () => {
    expect(viewOf(null)).toBe('none');
    expect(viewOf(analysis('QUEUED'))).toBe('queued');
    expect(viewOf(analysis('COMPLETED'))).toBe('completed');
    expect(viewOf(analysis('FAILED'))).toBe('failed');
  });
});

describe('watchAnalysis', () => {
  function setup(fetched: (Analysis | null)[]) {
    const events: string[] = [];
    let push: (a: Analysis) => void = () => {};
    let connected: () => void = () => {};
    const timers: { callback: () => void; ms: number }[] = [];
    const fetch = vi.fn(async () => {
      events.push('fetch');
      return fetched.length > 1 ? fetched.shift()! : fetched[0];
    });
    const deps: WatchDeps = {
      subscribe: (onUpdate, onConnected) => {
        events.push('subscribe');
        push = onUpdate;
        connected = onConnected;
        return () => events.push('unsubscribe');
      },
      fetch,
      setTimer: (callback, ms) => timers.push({ callback, ms }) - 1,
      clearTimer: vi.fn(),
      now: () => Date.parse('2026-10-26T12:00:00Z'),
    };
    const seen: (Analysis | null)[] = [];
    const errors: unknown[] = [];
    const watcher = watchAnalysis('match-1', deps, (a) => seen.push(a), (e) => errors.push(e));
    return { events, seen, errors, timers, fetch, watcher, push: (a: Analysis) => push(a), connect: () => connected() };
  }
  const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

  it('subscribes before it asks the server', async () => {
    const { events } = setup([null]);
    await flush();
    expect(events.slice(0, 2)).toEqual(['subscribe', 'fetch']);
  });

  it('shows "not analyzed" when there is no analysis', async () => {
    const { seen } = setup([null]);
    await flush();
    expect(seen).toEqual([null]);
  });

  it('reads the state again after every WebSocket connection', async () => {
    const { fetch, connect } = setup([null]);
    await flush();
    connect();
    await flush();
    connect();
    await flush();
    expect(fetch).toHaveBeenCalledTimes(3);
  });

  it('checks once more after the deadline of a queued analysis', async () => {
    const { timers, fetch } = setup([analysis('QUEUED')]);
    await flush();
    // 3 minutes to the deadline, plus the grace period
    expect(timers).toHaveLength(1);
    expect(timers[0].ms).toBe(180_000 + DEADLINE_GRACE_MS);
    timers[0].callback();
    await flush();
    expect(fetch).toHaveBeenCalledTimes(2);
  });

  it('shows a pushed result and ignores pushes for other games', async () => {
    const { seen, push } = setup([analysis('QUEUED')]);
    await flush();
    push(analysis('COMPLETED', { matchId: 'other-game' }));
    push(analysis('COMPLETED'));
    expect(seen.map((a) => a?.status)).toEqual(['QUEUED', 'COMPLETED']);
  });

  it('does not let a late GET undo a pushed result', async () => {
    const { seen, push } = setup([analysis('QUEUED')]);
    push(analysis('COMPLETED'));
    await flush();
    expect(seen.map((a) => a?.status)).toEqual(['COMPLETED']);
  });

  it('restarts a failed analysis only when the request that did it is adopted', async () => {
    const { seen, push, watcher } = setup([analysis('FAILED', { errorCode: 'TIMED_OUT' })]);
    await flush();
    push(analysis('QUEUED'));
    expect(seen.map((a) => a?.status)).toEqual(['FAILED']);
    watcher.adopt(analysis('QUEUED'));
    expect(seen.map((a) => a?.status)).toEqual(['FAILED', 'QUEUED']);
  });

  it('reports a failed read and keeps what it has', async () => {
    const events: unknown[] = [];
    const error = new Error('network');
    watchAnalysis(
      'match-1',
      {
        subscribe: () => () => {},
        fetch: () => Promise.reject(error),
        setTimer: () => 0,
        clearTimer: () => {},
        now: () => 0,
      },
      () => {},
      (e) => events.push(e),
    );
    await flush();
    expect(events).toEqual([error]);
  });

  it('stops listening and checking when closed', async () => {
    const { events, timers, watcher, push, seen } = setup([analysis('QUEUED')]);
    await flush();
    watcher.stop();
    push(analysis('COMPLETED'));
    expect(events).toContain('unsubscribe');
    expect(seen).toHaveLength(1);
    expect(timers).toHaveLength(1);
  });
});
