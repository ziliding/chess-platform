package me.zilid.chessplatform.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fails analyses that nobody answered before their deadline: a stopped analysis service, a lost message or a dead
 * letter. Every backend instance runs the sweep; {@link AnalysisService#failExpired} fails each job once.
 */
@Component
@ConditionalOnProperty(name = "app.analysis.sweep.enabled", havingValue = "true", matchIfMissing = true)
public class AnalysisSweeper {
    private final AnalysisService analysisService;

    public AnalysisSweeper(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @Scheduled(fixedDelayString = "${app.analysis.sweep.interval}")
    public void sweep() {
        analysisService.failExpired();
    }
}
