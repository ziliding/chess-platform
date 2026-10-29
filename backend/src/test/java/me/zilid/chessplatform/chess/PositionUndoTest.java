package me.zilid.chessplatform.chess;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import me.zilid.chessplatform.chess.format.Fen;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class PositionUndoTest {

    private static Stream<Arguments> specialMoves() {
        return Stream.of(
                Arguments.of(
                        "white kingside castle",
                        "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 3 7",
                        "e1",
                        "g1",
                        null,
                        "r3k2r/8/8/8/8/8/8/R4RK1 b kq - 4 7"),
                Arguments.of(
                        "black queenside castle",
                        "r3k2r/8/8/8/8/8/8/R3K2R b KQkq - 5 7",
                        "e8",
                        "c8",
                        null,
                        "2kr3r/8/8/8/8/8/8/R3K2R w KQ - 6 8"),
                Arguments.of(
                        "en passant capture",
                        "7k/8/8/3pP3/8/8/8/K7 w - d6 4 10",
                        "e5",
                        "d6",
                        null,
                        "7k/8/3P4/8/8/8/8/K7 b - - 0 10"),
                Arguments.of(
                        "promotion capture",
                        "1r5k/P7/8/8/8/8/8/K7 w - - 9 10",
                        "a7",
                        "b8",
                        PieceType.KNIGHT,
                        "1N5k/8/8/8/8/8/8/K7 b - - 0 10"),
                Arguments.of(
                        "black double pawn push",
                        "7k/7p/8/8/8/8/8/K7 b - - 17 22",
                        "h7",
                        "h5",
                        null,
                        "7k/8/8/7p/8/8/8/K7 w - h6 0 23"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("specialMoves")
    void applyingAndUndoingSpecialMoveRestoresEntirePosition(
            String name, String before, String from, String to, @Nullable PieceType promotion, String after) {
        Position position = Fen.parse(before);
        Move move = MoveGenerator.findLegalMove(position, Square.fromNotation(from), Square.fromNotation(to), promotion)
                .orElseThrow();

        UndoInfo undo = position.applyMove(move);
        assertThat(Fen.format(position)).as(name + " after application").isEqualTo(after);

        position.undoMove(move, undo);
        assertThat(Fen.format(position)).as(name + " after undo").isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
                "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
                "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1",
                "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8",
                "7k/8/8/3pP3/8/8/8/K7 w - d6 4 10"
            })
    void generatingLegalMovesLeavesPositionUnchanged(String fen) {
        Position position = Fen.parse(fen);

        assertThat(MoveGenerator.legalMoves(position, position.getTurnColor())).isNotEmpty();

        assertThat(Fen.format(position)).isEqualTo(fen);
    }
}
