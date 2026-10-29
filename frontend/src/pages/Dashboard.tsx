import { useEffect, useState } from 'react';
import { apiErrorMessage } from '../services/errors';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { gameService } from '../services/gameService';
import RatingsCard from '../components/RatingsCard';
import { CLOCK_SETTINGS, DEFAULT_CLOCK_SETTING } from '../types';
import { Play, Search, Users } from 'lucide-react';

const Dashboard = () => {
  const { user } = useAuth();
  const navigate = useNavigate();
  const [gameId, setGameId] = useState('');
  const [clockSetting, setClockSetting] = useState(DEFAULT_CLOCK_SETTING);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [searching, setSearching] = useState(false);

  useEffect(() => {
    if (!searching) return;
    let active = true;
    const poll = async () => {
      try {
        const response = await gameService.seek(clockSetting);
        if (active && response.status === 'MATCHED' && response.gameId) {
          active = false;
          navigate(`/game/${response.gameId}`);
        }
      } catch (err) {
        if (active) {
          setError(apiErrorMessage(err, 'Failed to find an opponent'));
          setSearching(false);
        }
      }
    };
    poll();
    const interval = setInterval(poll, 1000);
    return () => {
      clearInterval(interval);
      if (active) {
        active = false;
        gameService.cancelSeek(clockSetting).catch(() => {});
      }
    };
  }, [searching, clockSetting, navigate]);

  const handleCreateGame = async () => {
    setLoading(true);
    setError('');
    try {
      // Create a game with WHITE color
      const response = await gameService.createGame('WHITE', clockSetting);
      navigate(`/game/${response.gameId}`);
    } catch (err) {
      setError(apiErrorMessage(err, 'Failed to create game'));
      setLoading(false);
    }
  };

  const handleJoinGame = () => {
    if (gameId.trim()) {
      navigate(`/game/${gameId.trim()}`);
    }
  };

  return (
    <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
      <div className="mb-8">
        <h1 className="text-3xl font-bold text-gray-900">
          Welcome, {user?.username}!
        </h1>
        <p className="mt-2 text-gray-600">Start a new game or join an existing one</p>
      </div>

      {error && (
        <div className="mb-6 rounded-md bg-red-50 p-4">
          <p className="text-sm text-red-800">{error}</p>
        </div>
      )}

      <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
        {/* Create Game Card */}
        <div className="card">
          <div className="flex items-center space-x-3 mb-4">
            <Play className="text-primary-600" size={24} />
            <h2 className="text-xl font-semibold">Create New Game</h2>
          </div>
          <p className="text-gray-600 mb-4">Start a new chess game and invite a friend</p>
          <label htmlFor="clock-setting" className="block text-sm font-medium text-gray-700 mb-2">
            Clock (minutes + increment seconds, rated)
          </label>
          <select
            id="clock-setting"
            className="input mb-4"
            value={clockSetting}
            disabled={searching}
            onChange={(e) => setClockSetting(e.target.value)}
          >
            {CLOCK_SETTINGS.map((option) => (
              <option key={option} value={option}>
                {option}
              </option>
            ))}
          </select>
          <button
            onClick={handleCreateGame}
            disabled={loading}
            className="w-full btn btn-primary disabled:opacity-50"
          >
            {loading ? 'Creating game...' : 'Create Game'}
          </button>
          <button
            onClick={() => { setError(''); setSearching(!searching); }}
            disabled={loading}
            className="w-full btn btn-secondary mt-3 flex items-center justify-center gap-2 disabled:opacity-50"
          >
            <Search size={18} />
            {searching ? `Searching for a ${clockSetting} opponent... (cancel)` : 'Find Opponent'}
          </button>
        </div>

        {/* Join Game Card */}
        <div className="card">
          <div className="flex items-center space-x-3 mb-4">
            <Users className="text-primary-600" size={24} />
            <h2 className="text-xl font-semibold">Join Game</h2>
          </div>
          <p className="text-gray-600 mb-4">Enter a game ID to join</p>
          <div className="space-y-4">
            <input
              type="text"
              className="input"
              placeholder="Enter Game ID"
              value={gameId}
              onChange={(e) => setGameId(e.target.value)}
            />
            <button
              onClick={handleJoinGame}
              disabled={!gameId.trim()}
              className="w-full btn btn-primary disabled:opacity-50"
            >
              Join Game
            </button>
          </div>
        </div>
      </div>

      {user?.id && (
        <div className="mt-6">
          <RatingsCard userId={user.id} />
        </div>
      )}

      {/* Quick Start Guide */}
      <div className="mt-12 card bg-blue-50">
        <h3 className="text-lg font-semibold mb-4">Quick Start Guide</h3>
        <ul className="space-y-2 text-gray-700">
          <li>• Click "Find Opponent" to be paired with a player of similar rating on the same clock</li>
          <li>• Click "Create Game" to start a new game and share the game ID with your friend</li>
          <li>• Enter a game ID in "Join Game" to play with someone</li>
          <li>• Use the chat feature during the game to communicate</li>
          <li>• You can resign or offer a draw during the game</li>
          <li>• Every finished game updates both players' rating for its time control</li>
        </ul>
      </div>
    </div>
  );
};

export default Dashboard;
