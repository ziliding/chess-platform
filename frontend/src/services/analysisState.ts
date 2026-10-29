import { Analysis, AnalysisEval, AnalysisMove, MoveClassification } from '../types';

const MARKS: Partial<Record<MoveClassification, string>> = {
  INACCURACY: '?!',
  MISTAKE: '?',
  BLUNDER: '??',
};

// Evaluations are from White's point of view
export function evalText(ev: AnalysisEval): string {
  if (ev.mate !== null) {
    if (ev.mate === 0) return 'Checkmate';
    return `${ev.mate > 0 ? '+' : '-'}M${Math.abs(ev.mate)}`;
  }
  const pawns = (ev.cp ?? 0) / 100;
  return `${pawns > 0 ? '+' : ''}${pawns.toFixed(1)}`;
}

// "3...Nf6??" for Black's third move, a blunder
export function moveLabel(move: AnalysisMove, san: string = move.san): string {
  const number = Math.ceil(move.ply / 2);
  const dots = move.ply % 2 === 1 ? '.' : '...';
  return `${number}${dots}${san}${MARKS[move.classification] ?? ''}`;
}

export function isMistake(move: AnalysisMove): boolean {
  return move.classification in MARKS;
}

export function mistakes(moves: AnalysisMove[]): AnalysisMove[] {
  return moves.filter(isMistake);
}

export interface Notice {
  text: string;
  retryable: boolean;
}

// What a FAILED analysis says, by the error code the server stored
export function failureNotice(errorCode: string | null): Notice {
  switch (errorCode) {
    case 'TIMED_OUT':
      return { text: 'Analysis took too long.', retryable: true };
    case 'ENGINE_ERROR':
    case 'BUDGET_EXCEEDED':
      return { text: 'Analysis failed.', retryable: true };
    case 'INVALID_PGN':
    case 'TOO_LONG':
      return { text: "This game can't be analyzed.", retryable: false };
    default:
      // UNAVAILABLE, UNSUPPORTED_SCHEMA_VERSION and anything newer than this client
      return { text: 'Analysis is temporarily unavailable. Try again in a few minutes.', retryable: true };
  }
}

// What a refused request says: status and the problem's `code`, if any
export function requestRefusal(status: number | undefined, code: string | undefined): string {
  if (status === 429) return 'You have 3 analyses running. Wait for one to finish.';
  if (status === 403) return 'Only the players of this game can request its analysis.';
  if (status === 503 && code === 'ANALYSIS_BUSY') return 'The analysis queue is busy. Try again shortly.';
  if (status === 503) return 'Analysis is temporarily unavailable. Try again in a few minutes.';
  return 'Could not request the analysis. Try again.';
}

export type AnalysisView = 'none' | 'queued' | 'completed' | 'failed';

export function viewOf(analysis: Analysis | null): AnalysisView {
  if (analysis === null) return 'none';
  return analysis.status.toLowerCase() as AnalysisView;
}

// How long after its deadline a QUEUED analysis is checked once more: by then the server has failed it
export const DEADLINE_GRACE_MS = 20_000;

export interface WatchDeps {
  // onConnected runs after every (re)connection, once the subscription is in place
  subscribe: (onUpdate: (analysis: Analysis) => void, onConnected: () => void) => () => void;
  fetch: (matchId: string) => Promise<Analysis | null>;
  setTimer: (callback: () => void, ms: number) => unknown;
  clearTimer: (handle: unknown) => void;
  now: () => number;
}

export interface Watcher {
  stop: () => void;
  // Takes the analysis a request returned, which may restart a failed one
  adopt: (analysis: Analysis) => void;
}

/**
 * Keeps `onChange` up to date with a game's analysis. Pushes are only notifications and may be lost, so the state is
 * read with GET: right away, after every WebSocket (re)connection, and once after a QUEUED analysis's deadline.
 */
export function watchAnalysis(
  matchId: string,
  deps: WatchDeps,
  onChange: (analysis: Analysis | null) => void,
  onError: (error: unknown) => void,
): Watcher {
  let stopped = false;
  let current: Analysis | null = null;
  let timer: unknown = null;

  const schedule = () => {
    if (timer !== null) deps.clearTimer(timer);
    timer = null;
    if (current?.status !== 'QUEUED') return;
    const wait = Date.parse(current.deadline) + DEADLINE_GRACE_MS - deps.now();
    timer = deps.setTimer(() => void load(), Math.max(0, wait));
  };

  const set = (next: Analysis | null) => {
    current = next;
    onChange(next);
    schedule();
  };

  // A late GET or push must not undo a result already shown; only adopt() restarts a finished analysis
  const receive = (next: Analysis | null) => {
    if (stopped) return;
    const finished = current !== null && current.status !== 'QUEUED';
    if (finished && (next === null || next.status === 'QUEUED')) return;
    set(next);
  };

  const load = async () => {
    try {
      receive(await deps.fetch(matchId));
    } catch (error) {
      if (!stopped) onError(error);
    }
  };

  const unsubscribe = deps.subscribe((analysis) => {
    if (analysis.matchId === matchId) receive(analysis);
  }, () => void load());
  void load();

  return {
    stop: () => {
      stopped = true;
      if (timer !== null) deps.clearTimer(timer);
      unsubscribe();
    },
    adopt: (analysis) => {
      if (!stopped) set(analysis);
    },
  };
}
