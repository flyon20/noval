package com.novelanalyzer.modules.knowledge.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.novelanalyzer.modules.analysis.mapper.AnalysisResultMapper;
import com.novelanalyzer.modules.analysis.model.AnalysisResultEntity;
import com.novelanalyzer.modules.crawler.mapper.CrawlBookMapper;
import com.novelanalyzer.modules.crawler.mapper.CrawlChapterMapper;
import com.novelanalyzer.modules.crawler.model.CrawlBookEntity;
import com.novelanalyzer.modules.crawler.model.CrawlChapterEntity;
import com.novelanalyzer.modules.knowledge.mapper.KnowledgeChunkMapper;
import com.novelanalyzer.modules.knowledge.mapper.KnowledgeDocumentMapper;
import com.novelanalyzer.modules.knowledge.model.KnowledgeChunkEntity;
import com.novelanalyzer.modules.knowledge.model.KnowledgeDocumentEntity;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeSearchResultVO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class KnowledgeRepository {

    private final CrawlBookMapper crawlBookMapper;
    private final CrawlChapterMapper crawlChapterMapper;
    private final AnalysisResultMapper analysisResultMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final JdbcTemplate jdbcTemplate;

    public KnowledgeRepository(CrawlBookMapper crawlBookMapper,
                               CrawlChapterMapper crawlChapterMapper,
                               AnalysisResultMapper analysisResultMapper,
                               KnowledgeDocumentMapper knowledgeDocumentMapper,
                               KnowledgeChunkMapper knowledgeChunkMapper,
                               JdbcTemplate jdbcTemplate) {
        this.crawlBookMapper = crawlBookMapper;
        this.crawlChapterMapper = crawlChapterMapper;
        this.analysisResultMapper = analysisResultMapper;
        this.knowledgeDocumentMapper = knowledgeDocumentMapper;
        this.knowledgeChunkMapper = knowledgeChunkMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<CrawlBookEntity> findBook(Long bookId) {
        return Optional.ofNullable(crawlBookMapper.selectOne(
            new LambdaQueryWrapper<CrawlBookEntity>()
                .eq(CrawlBookEntity::getId, bookId)
                .eq(CrawlBookEntity::getDeleted, 0)
                .last("LIMIT 1")
        ));
    }

    public List<CrawlChapterEntity> findChapters(Long bookId, int limit) {
        return crawlChapterMapper.selectList(
            new LambdaQueryWrapper<CrawlChapterEntity>()
                .eq(CrawlChapterEntity::getBookId, bookId)
                .eq(CrawlChapterEntity::getDeleted, 0)
                .orderByAsc(CrawlChapterEntity::getChapterNo)
                .last("LIMIT " + Math.max(1, limit))
        );
    }

    public Optional<AnalysisResultEntity> findAnalysisResult(Long analysisResultId) {
        return Optional.ofNullable(analysisResultMapper.selectOne(
            new LambdaQueryWrapper<AnalysisResultEntity>()
                .eq(AnalysisResultEntity::getId, analysisResultId)
                .eq(AnalysisResultEntity::getDeleted, 0)
                .last("LIMIT 1")
        ));
    }

    public KnowledgeDocumentEntity saveOrUpdateDocument(String sourceType,
                                                        Long sourceRefId,
                                                        String platform,
                                                        Long bookId,
                                                        String title) {
        KnowledgeDocumentEntity existing = knowledgeDocumentMapper.selectOne(
            new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                .eq(KnowledgeDocumentEntity::getSourceType, sourceType)
                .eq(KnowledgeDocumentEntity::getSourceRefId, sourceRefId)
                .eq(KnowledgeDocumentEntity::getPlatform, platform)
                .eq(KnowledgeDocumentEntity::getBookId, bookId)
                .eq(KnowledgeDocumentEntity::getDeleted, 0)
                .last("LIMIT 1")
        );
        LocalDateTime now = LocalDateTime.now();
        if (existing != null) {
            existing.setTitle(title);
            existing.setStatus("INDEXED");
            existing.setUpdateTime(now);
            knowledgeDocumentMapper.updateById(existing);
            return existing;
        }

        KnowledgeDocumentEntity entity = new KnowledgeDocumentEntity();
        entity.setSourceType(sourceType);
        entity.setSourceRefId(sourceRefId);
        entity.setPlatform(platform);
        entity.setBookId(bookId);
        entity.setTitle(title);
        entity.setStatus("INDEXED");
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        entity.setDeleted(0);
        knowledgeDocumentMapper.insert(entity);
        return entity;
    }

    public Optional<KnowledgeChunkEntity> findChunk(Long documentId, String chunkKey) {
        return Optional.ofNullable(knowledgeChunkMapper.selectOne(
            new LambdaQueryWrapper<KnowledgeChunkEntity>()
                .eq(KnowledgeChunkEntity::getDocumentId, documentId)
                .eq(KnowledgeChunkEntity::getChunkKey, chunkKey)
                .eq(KnowledgeChunkEntity::getDeleted, 0)
                .last("LIMIT 1")
        ));
    }

    public KnowledgeChunkEntity saveChunk(KnowledgeChunkEntity entity) {
        LocalDateTime now = LocalDateTime.now();
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        entity.setDeleted(0);
        knowledgeChunkMapper.insert(entity);
        return entity;
    }

    public KnowledgeChunkEntity updateChunkForReindex(KnowledgeChunkEntity entity) {
        entity.setUpdateTime(LocalDateTime.now());
        knowledgeChunkMapper.updateById(entity);
        return entity;
    }

    public void updateChunkVectorStatus(KnowledgeChunkEntity entity, String status, String pointId) {
        entity.setVectorStatus(status);
        entity.setQdrantPointId(pointId);
        entity.setUpdateTime(LocalDateTime.now());
        knowledgeChunkMapper.updateById(entity);
    }

    public Optional<KnowledgeSearchResultVO> findSearchResultSource(Long chunkId, String pointId, double score) {
        StringBuilder sql = new StringBuilder(
            """
                SELECT kc.id AS chunk_id,
                       kc.document_id,
                       kc.book_id,
                       cb.book_name,
                       kc.source_type,
                       kc.source_ref_id,
                       kc.chapter_no,
                       kc.analysis_type,
                       kd.platform,
                       kd.title,
                       kc.chunk_text
                FROM knowledge_chunk kc
                JOIN knowledge_document kd ON kd.id = kc.document_id AND kd.deleted = 0
                LEFT JOIN crawl_book cb ON cb.id = kc.book_id AND cb.deleted = 0
                WHERE kc.deleted = 0
                """
        );
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (chunkId != null) {
            sql.append(" AND kc.id = ?");
            args.add(chunkId);
        } else if (pointId != null && !pointId.isBlank()) {
            sql.append(" AND kc.qdrant_point_id = ?");
            args.add(pointId);
        } else {
            return Optional.empty();
        }
        sql.append(" LIMIT 1");
        List<KnowledgeSearchResultVO> results = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            KnowledgeSearchResultVO vo = new KnowledgeSearchResultVO();
            vo.setChunkId(rs.getLong("chunk_id"));
            vo.setDocumentId(rs.getLong("document_id"));
            vo.setScore(score);
            vo.setBookId(rs.getLong("book_id"));
            vo.setBookName(rs.getString("book_name"));
            vo.setPlatform(rs.getString("platform"));
            vo.setSourceType(rs.getString("source_type"));
            vo.setSourceRefId(rs.getLong("source_ref_id"));
            int chapterNo = rs.getInt("chapter_no");
            vo.setChapterNo(rs.wasNull() ? null : chapterNo);
            vo.setAnalysisType(rs.getString("analysis_type"));
            vo.setTitle(rs.getString("title"));
            vo.setPreview(buildPreview(rs.getString("chunk_text")));
            return vo;
        }, args.toArray());
        return results.stream().findFirst();
    }

    private String buildPreview(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace('\r', ' ').replace('\n', ' ').trim();
        if (normalized.length() <= 160) {
            return normalized;
        }
        return normalized.substring(0, 160);
    }
}
