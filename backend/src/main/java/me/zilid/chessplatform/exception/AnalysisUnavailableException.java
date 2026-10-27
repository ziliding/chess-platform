package me.zilid.chessplatform.exception;

/** The analysis service cannot take a job right now; the client may try again later. */
public class AnalysisUnavailableException extends RuntimeException {
    public static final String UNAVAILABLE = "ANALYSIS_UNAVAILABLE";
    public static final String BUSY = "ANALYSIS_BUSY";

    private final String code;

    public AnalysisUnavailableException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** {@link #UNAVAILABLE} or {@link #BUSY} */
    public String getCode() {
        return code;
    }
}
