export interface User {
  id: string;
  username: string;
  email: string;
  about?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface LoginRequest {
  username: string;
  password: string;
}

export interface UserCreateRequest {
  username: string;
  email: string;
  rawPassword: string;
  about?: string;
}

export interface UserUpdateRequest {
  email?: string;
  about?: string;
  rawPassword?: string;
}

export interface FriendRequest {
  friendRequestId: string;
  sender: User;
  recipient: User;
  requestedAt: string;
  updatedAt: string;
  status: 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'CANCELLED';
}

export type TimeControl = 'BULLET' | 'BLITZ' | 'RAPID' | 'CLASSICAL' | 'CORRESPONDENCE';

export const TIME_CONTROL_LABELS: Record<TimeControl, string> = {
  BULLET: 'Bullet',
  BLITZ: 'Blitz',
  RAPID: 'Rapid',
  CLASSICAL: 'Classical',
  CORRESPONDENCE: 'Correspondence',
};

// Initial minutes + increment seconds, as the backend reads them
export const CLOCK_SETTINGS = ['1+0', '2+1', '3+0', '3+2', '5+0', '5+3', '10+0', '10+5', '15+10', '30+0', '30+20'];

export const DEFAULT_CLOCK_SETTING = '5+3';

export interface PlayerRating {
  timeControl: TimeControl;
  rating: number;
  gamesPlayed: number;
  peakRating: number;
}

export interface MatchRecord {
  id: string;
  whitePlayer: User;
  blackPlayer: User;
  startTime: string;
  endTime?: string;
  result: string;
  reason?: string;
  pgn?: string;
  // Rating fields are null for games played before ratings were tracked.
  timeControl?: TimeControl | null;
  whiteRating?: number | null;
  blackRating?: number | null;
  whiteRatingChange?: number | null;
  blackRatingChange?: number | null;
}

export interface GameState {
  gameStatus: GameStatus;
  fen: string;
  lastMoveFrom?: string;
  lastMoveTo?: string;
  turnColor: 'WHITE' | 'BLACK';
  // Time left for each side when the server sent this state
  whiteRemainingMillis: number;
  blackRemainingMillis: number;
  // Whether the side to move is losing time: false before Black's first move and after the game ends
  clockRunning: boolean;
  // Before the clock starts: how long the side to move has left to make its first move before the game is aborted
  firstMoveRemainingMillis: number | null;
}

export type GameStatus = 
  | 'ONGOING'
  | 'CHECKMATE_WHITE_WINS'
  | 'CHECKMATE_BLACK_WINS'
  | 'RESIGNED_WHITE_WINS'
  | 'RESIGNED_BLACK_WINS'
  | 'STALEMATE'
  | 'DRAW_BY_REPETITION'
  | 'DRAW_BY_FIFTY_MOVE_RULE'
  | 'DRAW_BY_INSUFFICIENT_MATERIAL'
  | 'DRAW_BY_AGREEMENT'
  | 'FLAGGED_WHITE_WINS'
  | 'FLAGGED_BLACK_WINS'
  | 'DRAW_BY_TIMEOUT_VS_INSUFFICIENT_MATERIAL'
  | 'ABORTED';

export interface MoveRequest {
  gameId: string;
  moveFrom: string;
  moveTo: string;
  promotion?: string;
}

export interface ChatMessage {
  sender: string;
  message: string;
  timestamp: string;
  type: 'CHAT' | 'JOIN' | 'LEAVE' | 'SYSTEM';
}

// Spring Data's PagedModel (VIA_DTO serialization)
export interface PageResponse<T> {
  content: T[];
  page: {
    size: number;
    number: number;
    totalElements: number;
    totalPages: number;
  };
}

export interface GameCreatedResponse {
  gameId: string;
  color: 'WHITE' | 'BLACK';
  clockSetting: string;
  timeControl: TimeControl;
  fen: string;
  socketUrl: string;
}

export interface GameJoinResponse {
  gameId: string;
  role: string;
  clockSetting: string;
  timeControl: TimeControl;
  fen: string;
  status: GameStatus;
  currentTurn: string;
}

// AI game analysis (contract v1, see docs/specs/ai-analysis.md)
export type AnalysisStatus = 'QUEUED' | 'COMPLETED' | 'FAILED';
export type MoveClassification = 'BEST' | 'GOOD' | 'INACCURACY' | 'MISTAKE' | 'BLUNDER';

// From White's point of view; exactly one of the two is set. mate 0 means the side to move is checkmated.
export interface AnalysisEval {
  cp: number | null;
  mate: number | null;
}

export interface AnalysisMove {
  ply: number;
  san: string;
  uci: string;
  eval: AnalysisEval;
  bestSan: string;
  bestUci: string;
  winChanceLoss: number;
  classification: MoveClassification;
}

export interface Analysis {
  matchId: string;
  status: AnalysisStatus;
  errorCode: string | null;
  // When a QUEUED analysis is given up on
  deadline: string;
  completedAt: string | null;
  engine: { name: string; depth: number } | null;
  initialEval: AnalysisEval | null;
  moves: AnalysisMove[] | null;
  summary: { text: string; source: 'TEMPLATE' | 'LLM' } | null;
}
