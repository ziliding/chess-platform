import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import axios from 'axios';
import { Chessboard } from 'react-chessboard';
import { Chess } from 'chess.js';
import { ArrowLeft, ChevronLeft, ChevronRight, ChevronsLeft, ChevronsRight, Sparkles } from 'lucide-react';
import { analysisService } from '../services/analysisService';
import {
  Watcher,
  evalText,
  failureNotice,
  mistakes,
  moveLabel,
  requestRefusal,
  viewOf,
  watchAnalysis,
} from '../services/analysisState';
import { gameService } from '../services/gameService';
import { subscribeToAnalysis } from '../services/websocketService';
import { Analysis as AnalysisData, AnalysisMove } from '../types';

const CLASSIFICATION_COLOR: Record<string, string> = {
  INACCURACY: 'text-yellow-600',
  MISTAKE: 'text-orange-600',
  BLUNDER: 'text-red-600',
};

// The game's positions from its stored PGN: index 0 is the start, index n the position after ply n
function positionsOf(pgn: string): { fens: string[]; sans: string[] } {
  const chess = new Chess();
  chess.loadPgn(pgn);
  const moves = chess.history({ verbose: true });
  return { fens: [new Chess().fen(), ...moves.map((move) => move.after)], sans: moves.map((move) => move.san) };
}

const Analysis = () => {
  const { matchId } = useParams<{ matchId: string }>();
  const [analysis, setAnalysis] = useState<AnalysisData | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [loadError, setLoadError] = useState('');
  const [notice, setNotice] = useState('');
  const [requesting, setRequesting] = useState(false);
  const [game, setGame] = useState<{ fens: string[]; sans: string[] } | null>(null);
  const [gameError, setGameError] = useState('');
  const [ply, setPly] = useState(0);
  const watcher = useRef<Watcher | null>(null);

  useEffect(() => {
    if (!matchId) return;
    let cancelled = false;
    gameService
      .getMatchPGN(matchId)
      .then((pgn) => {
        if (cancelled) return;
        const positions = positionsOf(pgn);
        setGame(positions);
        setPly(positions.sans.length);
      })
      .catch(() => !cancelled && setGameError('Could not load the game.'));
    return () => {
      cancelled = true;
    };
  }, [matchId]);

  useEffect(() => {
    if (!matchId) return;
    const current = watchAnalysis(
      matchId,
      {
        subscribe: subscribeToAnalysis,
        fetch: analysisService.get,
        setTimer: (callback, ms) => window.setTimeout(callback, ms),
        clearTimer: (handle) => window.clearTimeout(handle as number),
        now: () => Date.now(),
      },
      (next) => {
        setAnalysis(next);
        setLoaded(true);
        setLoadError('');
      },
      () => setLoadError('Could not load the analysis.'),
    );
    watcher.current = current;
    return () => {
      current.stop();
      watcher.current = null;
    };
  }, [matchId]);

  const requestAnalysis = async () => {
    if (!matchId) return;
    setNotice('');
    setRequesting(true);
    try {
      const requested = await analysisService.request(matchId);
      watcher.current?.adopt(requested);
    } catch (err) {
      const status = axios.isAxiosError(err) ? err.response?.status : undefined;
      const code = axios.isAxiosError(err) ? (err.response?.data as { code?: string } | undefined)?.code : undefined;
      setNotice(requestRefusal(status, code));
    } finally {
      setRequesting(false);
    }
  };

  const last = game?.sans.length ?? 0;
  const goTo = useCallback((next: number) => setPly(Math.max(0, Math.min(last, next))), [last]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'ArrowLeft') goTo(ply - 1);
      if (event.key === 'ArrowRight') goTo(ply + 1);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [goTo, ply]);

  const moves = useMemo(() => analysis?.moves ?? [], [analysis]);
  const errors = useMemo(() => mistakes(moves), [moves]);
  const current = ply > 0 ? moves[ply - 1] : undefined;
  const view = viewOf(analysis);
  const failure = view === 'failed' && analysis ? failureNotice(analysis.errorCode) : null;
  const evalNow = ply === 0 ? analysis?.initialEval : current?.eval;
  const sans = game?.sans ?? moves.map((move) => move.san);

  const moveButton = (index: number) => {
    const move = moves[index];
    const classification = move?.classification ?? '';
    return (
      <button
        key={index}
        onClick={() => goTo(index + 1)}
        className={`px-1.5 py-0.5 rounded text-left ${ply === index + 1 ? 'bg-primary-100 font-semibold' : 'hover:bg-gray-100'} ${CLASSIFICATION_COLOR[classification] ?? 'text-gray-800'}`}
        title={move ? `${evalText(move.eval)}${move.classification !== 'BEST' ? ` · best ${move.bestSan}` : ''}` : undefined}
      >
        {move ? moveLabel(move, sans[index] ?? move.san).replace(/^\d+\.+/, '') : sans[index]}
      </button>
    );
  };

  return (
    <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
      <Link to="/history" className="inline-flex items-center space-x-1 text-sm text-gray-600 hover:text-gray-900 mb-4">
        <ArrowLeft size={14} />
        <span>Game history</span>
      </Link>
      <h1 className="text-3xl font-bold text-gray-900 mb-6">Game analysis</h1>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-8">
        <div>
          {gameError && <p className="text-sm text-red-700 mb-3">{gameError}</p>}
          <div className="max-w-[560px]">
            <Chessboard
              options={{
                position: game?.fens[ply] ?? new Chess().fen(),
                allowDragging: false,
                boardOrientation: 'white',
              }}
            />
          </div>
          <div className="mt-4 flex items-center space-x-2">
            <button className="btn btn-secondary" onClick={() => goTo(0)} aria-label="First position">
              <ChevronsLeft size={16} />
            </button>
            <button className="btn btn-secondary" onClick={() => goTo(ply - 1)} aria-label="Previous move">
              <ChevronLeft size={16} />
            </button>
            <button className="btn btn-secondary" onClick={() => goTo(ply + 1)} aria-label="Next move">
              <ChevronRight size={16} />
            </button>
            <button className="btn btn-secondary" onClick={() => goTo(last)} aria-label="Last position">
              <ChevronsRight size={16} />
            </button>
            {evalNow && view === 'completed' && (
              <span className="ml-3 text-sm font-mono text-gray-700">Eval {evalText(evalNow)}</span>
            )}
          </div>
          {view === 'completed' && current && current.classification !== 'BEST' && isErrorMove(current) && (
            <p className="mt-3 text-sm text-gray-700">
              <span className={CLASSIFICATION_COLOR[current.classification]}>{moveLabel(current)}</span>
              {' — best was '}
              <span className="font-semibold">{current.bestSan}</span>
            </p>
          )}
        </div>

        <div className="space-y-6">
          {loadError && <div className="rounded-md bg-red-50 p-4 text-sm text-red-800">{loadError}</div>}
          {!loaded && !loadError && <p className="text-gray-600">Loading analysis…</p>}

          {loaded && view === 'none' && (
            <div className="card">
              <p className="text-gray-700 mb-4">This game has not been analyzed yet.</p>
              <button className="btn btn-primary flex items-center space-x-2" onClick={requestAnalysis} disabled={requesting}>
                <Sparkles size={16} />
                <span>Analyze game</span>
              </button>
            </div>
          )}

          {view === 'queued' && analysis && (
            <div className="card flex items-center space-x-3">
              <div className="animate-spin rounded-full h-5 w-5 border-b-2 border-primary-600"></div>
              <p className="text-gray-700">Analyzing… this usually takes under 30 seconds.</p>
            </div>
          )}

          {failure && (
            <div className="card">
              <p className="text-gray-800 mb-3">{failure.text}</p>
              {failure.retryable && (
                <button className="btn btn-primary" onClick={requestAnalysis} disabled={requesting}>
                  Retry
                </button>
              )}
            </div>
          )}

          {notice && <div className="rounded-md bg-yellow-50 p-4 text-sm text-yellow-900">{notice}</div>}

          {view === 'completed' && analysis && (
            <>
              {analysis.summary && (
                <div className="card">
                  <h2 className="text-lg font-semibold text-gray-900 mb-2">Summary</h2>
                  <p className="text-gray-700">{analysis.summary.text}</p>
                  {analysis.engine && (
                    <p className="mt-2 text-xs text-gray-500">
                      {analysis.engine.name}, depth {analysis.engine.depth}
                    </p>
                  )}
                </div>
              )}

              <div className="card">
                <h2 className="text-lg font-semibold text-gray-900 mb-2">Mistakes</h2>
                {errors.length === 0 ? (
                  <p className="text-gray-600 text-sm">No inaccuracies, mistakes or blunders.</p>
                ) : (
                  <ul className="space-y-1 text-sm">
                    {errors.map((move) => (
                      <li key={move.ply}>
                        <button className="text-left hover:underline" onClick={() => goTo(move.ply)}>
                          <span className={`font-semibold ${CLASSIFICATION_COLOR[move.classification]}`}>
                            {moveLabel(move)}
                          </span>
                          <span className="text-gray-600"> best was {move.bestSan}</span>
                          <span className="text-gray-400"> · {evalText(move.eval)}</span>
                        </button>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            </>
          )}

          {sans.length > 0 && (
            <div className="card">
              <h2 className="text-lg font-semibold text-gray-900 mb-2">Moves</h2>
              <div className="grid grid-cols-[2.5rem_1fr_1fr] gap-y-0.5 text-sm font-mono">
                {Array.from({ length: Math.ceil(sans.length / 2) }, (_, row) => (
                  <div key={row} className="contents">
                    <span className="text-gray-400">{row + 1}.</span>
                    {moveButton(row * 2)}
                    {row * 2 + 1 < sans.length ? moveButton(row * 2 + 1) : <span />}
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

function isErrorMove(move: AnalysisMove): boolean {
  return mistakes([move]).length > 0;
}

export default Analysis;
