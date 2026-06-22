package com.novelanalyzer.modules.data.vo;

import java.util.List;

public class AnalysisHistorySearchMeta {

    private List<String> matchedFields = List.of();
    private List<String> matchSnippets = List.of();
    private Double matchScore;

    public AnalysisHistorySearchMeta() {
    }

    public AnalysisHistorySearchMeta(List<String> matchedFields, List<String> matchSnippets, Double matchScore) {
        this.matchedFields = matchedFields == null ? List.of() : matchedFields;
        this.matchSnippets = matchSnippets == null ? List.of() : matchSnippets;
        this.matchScore = matchScore;
    }

    public List<String> getMatchedFields() {
        return matchedFields;
    }

    public void setMatchedFields(List<String> matchedFields) {
        this.matchedFields = matchedFields == null ? List.of() : matchedFields;
    }

    public List<String> getMatchSnippets() {
        return matchSnippets;
    }

    public void setMatchSnippets(List<String> matchSnippets) {
        this.matchSnippets = matchSnippets == null ? List.of() : matchSnippets;
    }

    public Double getMatchScore() {
        return matchScore;
    }

    public void setMatchScore(Double matchScore) {
        this.matchScore = matchScore;
    }
}
