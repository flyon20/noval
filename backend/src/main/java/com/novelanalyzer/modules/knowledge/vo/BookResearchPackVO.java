package com.novelanalyzer.modules.knowledge.vo;

import java.util.ArrayList;
import java.util.List;

public class BookResearchPackVO {

    private BookProfileVO book;
    private List<RankLookupResultVO> ranks = new ArrayList<>();
    private List<ChapterMaterialVO> chapters = new ArrayList<>();
    private List<AnalysisMaterialVO> analyses = new ArrayList<>();

    public BookProfileVO getBook() {
        return book;
    }

    public void setBook(BookProfileVO book) {
        this.book = book;
    }

    public List<RankLookupResultVO> getRanks() {
        return ranks;
    }

    public void setRanks(List<RankLookupResultVO> ranks) {
        this.ranks = ranks == null ? new ArrayList<>() : ranks;
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
