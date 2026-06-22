package com.novelanalyzer.modules.knowledge.vo;

import java.util.ArrayList;
import java.util.List;

public class RankResearchPackVO {

    private List<RankLookupResultVO> ranks = new ArrayList<>();
    private List<BookProfileVO> books = new ArrayList<>();
    private List<ChapterMaterialVO> chapters = new ArrayList<>();
    private List<AnalysisMaterialVO> analyses = new ArrayList<>();

    public List<RankLookupResultVO> getRanks() {
        return ranks;
    }

    public void setRanks(List<RankLookupResultVO> ranks) {
        this.ranks = ranks == null ? new ArrayList<>() : ranks;
    }

    public List<BookProfileVO> getBooks() {
        return books;
    }

    public void setBooks(List<BookProfileVO> books) {
        this.books = books == null ? new ArrayList<>() : books;
    }

    public List<ChapterMaterialVO> getChapters() {
        return chapters;
    }

    public void setChapters(List<ChapterMaterialVO> chapters) {
        this.chapters = chapters == null ? new ArrayList<>() : chapters;
    }

    public List<AnalysisMaterialVO> getAnalyses() {
        return analyses;
    }

    public void setAnalyses(List<AnalysisMaterialVO> analyses) {
        this.analyses = analyses == null ? new ArrayList<>() : analyses;
    }
}
