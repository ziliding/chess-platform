package me.zilid.chessplatform.service;

import me.zilid.chessplatform.config.AnalysisConfig;
import me.zilid.chessplatform.model.dto.AnalysisResultMessage;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Receives the analysis service's results. A message that cannot be converted or applied is retried a few times, then
 * dead-lettered to {@code analysis.results.dlq}; the job it belongs to is failed by the timeout sweeper.
 */
@Component
public class AnalysisResultListener {
    private final AnalysisService analysisService;

    public AnalysisResultListener(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @RabbitListener(queues = AnalysisConfig.RESULT_QUEUE)
    public void onResult(AnalysisResultMessage result) {
        analysisService.applyResult(result);
    }
}
