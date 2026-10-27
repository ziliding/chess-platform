package me.zilid.chessplatform.controller;

import java.util.UUID;
import me.zilid.chessplatform.model.dto.AnalysisResponse;
import me.zilid.chessplatform.security.UserPrincipal;
import me.zilid.chessplatform.service.AnalysisService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/games/{matchId}/analysis")
public class AnalysisController {
    private final AnalysisService analysisService;

    public AnalysisController(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @GetMapping
    public AnalysisResponse getAnalysis(@PathVariable UUID matchId) {
        return analysisService.get(matchId);
    }

    /** 202 for a newly queued job; 200 with the existing job if the analysis is already queued or done. */
    @PostMapping
    public ResponseEntity<AnalysisResponse> requestAnalysis(
            @PathVariable UUID matchId, @AuthenticationPrincipal UserPrincipal currentUser) {
        AnalysisService.RequestResult result = analysisService.request(matchId, currentUser.getId());
        return ResponseEntity.status(result.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(result.analysis());
    }
}
