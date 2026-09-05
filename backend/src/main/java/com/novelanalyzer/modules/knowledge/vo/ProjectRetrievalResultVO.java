package com.novelanalyzer.modules.knowledge.vo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ProjectRetrievalResultVO {
    private List<Map<String, Object>> evidence = new ArrayList<>();
    private List<String> gaps = new ArrayList<>();
    private Map<String, Object> diagnostics = new LinkedHashMap<>();
    private boolean partial;

    public List<Map<String, Object>> getEvidence() { return evidence; }
    public void setEvidence(List<Map<String, Object>> evidence) { this.evidence = evidence == null ? new ArrayList<>() : new ArrayList<>(evidence); }
    public List<String> getGaps() { return gaps; }
    public void setGaps(List<String> gaps) { this.gaps = gaps == null ? new ArrayList<>() : new ArrayList<>(gaps); }
    public Map<String, Object> getDiagnostics() { return diagnostics; }
    public void setDiagnostics(Map<String, Object> diagnostics) { this.diagnostics = diagnostics == null ? new LinkedHashMap<>() : new LinkedHashMap<>(diagnostics); }
    public boolean isPartial() { return partial; }
    public void setPartial(boolean partial) { this.partial = partial; }
}
