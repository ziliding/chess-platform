import { useState, useEffect } from 'react';
import { apiErrorMessage } from '../services/errors';
import { useAuth } from '../context/AuthContext';
import { gameService } from '../services/gameService';
import { MatchRecord, TIME_CONTROL_LABELS } from '../types';
import { Link } from 'react-router-dom';
import { Trophy, Clock, Calendar, Download, Copy, Sparkles } from 'lucide-react';

const History = () => {
  const { user } = useAuth();
  const [games, setGames] = useState<MatchRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [copiedGameId, setCopiedGameId] = useState<string | null>(null);
  const pageSize = 10;

  const loadGames = async () => {
    if (!user?.id) {
      setLoading(false);
      return;
    }

    try {
      setLoading(true);
      setError('');
      const response = await gameService.getGames(user.id, page, pageSize);
      setGames(response.content || []);
      setTotalPages(response.page.totalPages || 0);
    } catch (err) {
      console.error('Error loading games:', err);
      setError(apiErrorMessage(err, 'Failed to load game history'));
      setGames([]);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (user?.id) {
      loadGames();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [user?.id, page]);

  const getGameResult = (game: MatchRecord) => {
    if (!user || !game.result) return { text: game.result || 'Unknown', color: 'text-gray-600' };

    const isWhite = game.whitePlayer?.id === user.id;
    const isBlack = game.blackPlayer?.id === user.id;

    if (game.result.includes('1-0')) {
      if (isWhite) return { text: 'Won', color: 'text-green-600 font-semibold' };
      if (isBlack) return { text: 'Lost', color: 'text-red-600 font-semibold' };
    } else if (game.result.includes('0-1')) {
      if (isBlack) return { text: 'Won', color: 'text-green-600 font-semibold' };
      if (isWhite) return { text: 'Lost', color: 'text-red-600 font-semibold' };
    } else if (game.result.includes('1/2-1/2') || game.result.includes('STALEMATE')) {
      return { text: 'Draw', color: 'text-blue-600 font-semibold' };
    }

    return { text: game.result, color: 'text-gray-600' };
  };

  const getOpponentName = (game: MatchRecord) => {
    if (!user) return 'Unknown';
    
    if (game.whitePlayer?.id === user.id) {
      return game.blackPlayer?.username || 'Unknown';
    }
    return game.whitePlayer?.username || 'Unknown';
  };

  const getRatingChange = (game: MatchRecord) => {
    if (!user) return null;
    const isWhite = game.whitePlayer?.id === user.id;
    const rating = isWhite ? game.whiteRating : game.blackRating;
    const change = isWhite ? game.whiteRatingChange : game.blackRatingChange;
    if (rating == null || change == null) return null;

    const sign = change > 0 ? '+' : '';
    const color = change > 0 ? 'text-green-600' : change < 0 ? 'text-red-600' : 'text-gray-600';
    return { text: `${rating} (${sign}${change})`, color };
  };

  const getPlayerColor = (game: MatchRecord) => {
    if (!user) return '';
    return game.whitePlayer?.id === user.id ? 'White' : 'Black';
  };

  const formatDuration = (startTime: string, endTime?: string) => {
    if (!endTime) return 'In progress';
    
    const start = new Date(startTime);
    const end = new Date(endTime);
    const durationMs = end.getTime() - start.getTime();
    const minutes = Math.floor(durationMs / 60000);
    const seconds = Math.floor((durationMs % 60000) / 1000);
    
    if (minutes > 0) {
      return `${minutes}m ${seconds}s`;
    }
    return `${seconds}s`;
  };

  const handleDownloadPGN = async (gameId: string) => {
    try {
      const pgnText = await gameService.getMatchPGN(gameId);
      
      // Create a blob and download
      const blob = new Blob([pgnText], { type: 'text/plain' });
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `game-${gameId}.pgn`;
      document.body.appendChild(a);
      a.click();
      window.URL.revokeObjectURL(url);
      document.body.removeChild(a);
    } catch (err) {
      console.error('Error downloading PGN:', err);
    }
  };

  const handleCopyPGN = async (gameId: string) => {
    try {
      const pgnText = await gameService.getMatchPGN(gameId);
      
      await navigator.clipboard.writeText(pgnText);
      setCopiedGameId(gameId);
      setTimeout(() => setCopiedGameId(null), 2000);
    } catch (err) {
      console.error('Error copying PGN:', err);
    }
  };

  if (loading && page === 0) {
    return (
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
        <div className="flex items-center justify-center h-64">
          <div className="text-center">
            <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-primary-600 mx-auto mb-4"></div>
            <p className="text-gray-600">Loading game history...</p>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
      <div className="mb-8">
        <h1 className="text-3xl font-bold text-gray-900">Game History</h1>
        <p className="mt-2 text-gray-600">View your past chess games</p>
      </div>

      {error && (
        <div className="mb-6 rounded-md bg-red-50 p-4">
          <p className="text-sm text-red-800">{error}</p>
        </div>
      )}

      {games.length === 0 && !loading ? (
        <div className="card text-center py-12">
          <Trophy size={48} className="mx-auto text-gray-400 mb-4" />
          <h3 className="text-lg font-semibold text-gray-900 mb-2">No games yet</h3>
          <p className="text-gray-600">Start playing to build your game history!</p>
        </div>
      ) : (
        <>
          <div className="space-y-4">
            {games.map((game) => {
              const result = getGameResult(game);
              const ratingChange = getRatingChange(game);
              
              return (
                <div key={game.id} className="card hover:shadow-lg transition-shadow">
                  <div className="flex items-center justify-between">
                    <div className="flex-1">
                      <div className="flex items-center space-x-4 mb-2">
                        <h3 className="text-lg font-semibold text-gray-900">
                          vs {getOpponentName(game)}
                        </h3>
                        <span className={`text-sm ${result.color}`}>
                          {result.text}
                        </span>
                      </div>
                      
                      <div className="flex flex-wrap items-center gap-4 text-sm text-gray-600">
                        <div className="flex items-center space-x-1">
                          <span className="font-medium">Playing as:</span>
                          <span>{getPlayerColor(game)}</span>
                        </div>

                        {game.timeControl && (
                          <div className="flex items-center space-x-1">
                            <span className="font-medium">{TIME_CONTROL_LABELS[game.timeControl]}</span>
                          </div>
                        )}

                        {ratingChange && (
                          <div className="flex items-center space-x-1">
                            <span className="font-medium">Rating:</span>
                            <span className={ratingChange.color}>{ratingChange.text}</span>
                          </div>
                        )}
                        
                        <div className="flex items-center space-x-1">
                          <Calendar size={14} />
                          <span>{new Date(game.startTime).toLocaleDateString()}</span>
                        </div>
                        
                        <div className="flex items-center space-x-1">
                          <Clock size={14} />
                          <span>{formatDuration(game.startTime, game.endTime)}</span>
                        </div>
                      </div>

                      <div className="mt-2 text-xs text-gray-500">
                        {game.result}
                      </div>
                    </div>

                    <div className="flex items-center space-x-3">
                      {game.endTime && (
                        <>
                          <button
                            onClick={() => handleDownloadPGN(game.id)}
                            className="btn btn-secondary text-sm flex items-center space-x-1"
                            title="Download PGN"
                          >
                            <Download size={14} />
                            <span>Download</span>
                          </button>
                          <button
                            onClick={() => handleCopyPGN(game.id)}
                            className="btn btn-secondary text-sm flex items-center space-x-1"
                            title="Copy PGN to clipboard"
                          >
                            <Copy size={14} />
                            <span>{copiedGameId === game.id ? 'Copied!' : 'Copy'}</span>
                          </button>
                          <Link
                            to={`/matches/${game.id}/analysis`}
                            className="btn btn-secondary text-sm flex items-center space-x-1"
                            title="Review this game with an engine"
                          >
                            <Sparkles size={14} />
                            <span>Analyze</span>
                          </Link>
                        </>
                      )}
                    </div>
                  </div>
                </div>
              );
            })}
          </div>

          {/* Pagination */}
          {totalPages > 1 && (
            <div className="mt-8 flex items-center justify-center space-x-4">
              <button
                onClick={() => setPage(Math.max(0, page - 1))}
                disabled={page === 0 || loading}
                className="btn btn-secondary disabled:opacity-50"
              >
                Previous
              </button>
              
              <span className="text-sm text-gray-600">
                Page {page + 1} of {totalPages}
              </span>
              
              <button
                onClick={() => setPage(Math.min(totalPages - 1, page + 1))}
                disabled={page >= totalPages - 1 || loading}
                className="btn btn-secondary disabled:opacity-50"
              >
                Next
              </button>
            </div>
          )}
        </>
      )}
    </div>
  );
};

export default History;
