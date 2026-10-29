package me.zilid.chessplatform.model.dto;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The part of an analysis result worth keeping: schemaVersion 1 of the contract in {@code contracts/analysis/v1}. Also
 * the JSON stored in {@code game_analyses.result}.
 */
public record AnalysisPayload(Engine engine, Eval initialEval, List<Move> moves, Summary summary) {
    public record Engine(String name, int depth) {}

    /** From White's point of view; exactly one of the two is set. */
    public record Eval(@Nullable Integer cp, @Nullable Integer mate) {}

    /**
     * One played move, with the engine's best move and how much the played move cost.
     *
     * @param classification BEST, GOOD, INACCURACY, MISTAKE or BLUNDER. A string, not an enum, so a value added by a
     *     newer analysis service does not break reading old messages.
     * @param explanation the LLM's plain-text explanation of a mistake; null for other moves, when the LLM was not
     *     used, and in results stored before it existed
     */
    public record Move(
            int ply,
            String san,
            String uci,
            Eval eval,
            String bestSan,
            String bestUci,
            double winChanceLoss,
            String classification,
            @Nullable String explanation) {}

    /**
     * A plain-language summary of the game.
     *
     * @param source TEMPLATE or LLM
     */
    public record Summary(String text, String source) {}
}
