package com.novelanalyzer.modules.knowledge.vo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class StoryGraphResultVO {
    private List<Map<String, Object>> nodes = new ArrayList<>();
    private List<Map<String, Object>> edges = new ArrayList<>();
    private List<Map<String, Object>> paths = new ArrayList<>();
    private List<String> gaps = new ArrayList<>();
    private boolean partial;

    public List<Map<String, Object>> getNodes() { return nodes; }
    public void setNodes(List<Map<String, Object>> nodes) { this.nodes = nodes == null ? new ArrayList<>() : new ArrayList<>(nodes); }
    public List<Map<String, Object>> getEdges() { return edges; }
    public void setEdges(List<Map<String, Object>> edges) { this.edges = edges == null ? new ArrayList<>() : new ArrayList<>(edges); }
    public List<Map<String, Object>> getPaths() { return paths; }
    public void setPaths(List<Map<String, Object>> paths) { this.paths = paths == null ? new ArrayList<>() : new ArrayList<>(paths); }
    public List<String> getGaps() { return gaps; }
    public void setGaps(List<String> gaps) { this.gaps = gaps == null ? new ArrayList<>() : new ArrayList<>(gaps); }
    public boolean isPartial() { return partial; }
    public void setPartial(boolean partial) { this.partial = partial; }
}
