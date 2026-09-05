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
import com.novelanalyzer.modules.knowledge.vo.KnowledgeHealthVO;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeSearchResultVO;
import com.novelanalyzer.modules.knowledge.vo.RankLookupResultVO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
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

    public Optional<CrawlBookEntity> findBook(String platform, String bookName) {
        if (platform == null || platform.isBlank() || bookName == null || bookName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(crawlBookMapper.selectOne(
            new LambdaQueryWrapper<CrawlBookEntity>()
                .eq(CrawlBookEntity::getPlatform, platform.trim())
                .eq(CrawlBookEntity::getBookName, bookName.trim())
                .eq(CrawlBookEntity::getDeleted, 0)
                .orderByDesc(CrawlBookEntity::getLastCrawlTime)
                .last("LIMIT 1")
        ));
    }

    public List<CrawlBookEntity> findBooksByIds(List<Long> bookIds) {
        if (bookIds == null || bookIds.isEmpty()) {
            return List.of();
        }
        return crawlBookMapper.selectList(
            new LambdaQueryWrapper<CrawlBookEntity>()
                .in(CrawlBookEntity::getId, bookIds)
                .eq(CrawlBookEntity::getDeleted, 0)
        );
    }

    public List<CrawlChapterEntity> findChapters(Long bookId, int limit) {
        return crawlChapterMapper.selectList(
            new LambdaQueryWrapper<CrawlChapterEntity>()
                .eq(CrawlChapterEntity::getBookId, bookId)
                .eq(CrawlChapterEntity::getDeleted, 0)
                .isNotNull(CrawlChapterEntity::getContent)
                .ne(CrawlChapterEntity::getContent, "")
                .orderByAsc(CrawlChapterEntity::getChapterNo)
                .last("LIMIT " + Math.max(1, limit))
        );
    }

    public List<CrawlChapterEntity> findChaptersByBookIds(List<Long> bookIds, int limitPerBook) {
        if (bookIds == null || bookIds.isEmpty()) {
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(limitPerBook, 20));
        String sql = """
            SELECT id,
                   platform,
                   book_id,
                   chapter_no,
                   chapter_title,
                   content,
                   word_count,
                   source_word_count,
                   crawl_time,
                   create_time,
                   deleted
            FROM (
                SELECT cc.*,
                       ROW_NUMBER() OVER (PARTITION BY cc.book_id ORDER BY cc.chapter_no ASC, cc.id ASC) AS rn
                FROM crawl_chapter cc
                WHERE cc.deleted = 0
                  AND cc.book_id IN (%s)
                  AND cc.content IS NOT NULL
                  AND TRIM(cc.content) <> ''
            ) ranked_chapter
            WHERE rn <= ?
            ORDER BY chapter_no ASC, id ASC
            """.formatted(inPlaceholders(bookIds.size()));
        List<Object> args = new ArrayList<>(bookIds);
        args.add(safeLimit);
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            CrawlChapterEntity entity = new CrawlChapterEntity();
            entity.setId(rs.getLong("id"));
            entity.setPlatform(rs.getString("platform"));
            entity.setBookId(readNullableLong(rs, "book_id"));
            entity.setChapterNo(readNullableInt(rs, "chapter_no"));
            entity.setChapterTitle(rs.getString("chapter_title"));
            entity.setContent(rs.getString("content"));
            entity.setWordCount(readNullableInt(rs, "word_count"));
            entity.setSourceWordCount(readNullableInt(rs, "source_word_count"));
            entity.setCrawlTime(readNullableLocalDateTime(rs, "crawl_time"));
            entity.setCreateTime(readNullableLocalDateTime(rs, "create_time"));
            entity.setDeleted(readNullableInt(rs, "deleted"));
            return entity;
        }, args.toArray());
    }

    public Optional<AnalysisResultEntity> findAnalysisResult(Long analysisResultId) {
        return Optional.ofNullable(analysisResultMapper.selectOne(
            new LambdaQueryWrapper<AnalysisResultEntity>()
                .eq(AnalysisResultEntity::getId, analysisResultId)
                .eq(AnalysisResultEntity::getDeleted, 0)
                .last("LIMIT 1")
        ));
    }

    public List<AnalysisResultEntity> findLatestAnalysisResultsForBook(Long userId, Long bookId, int limit) {
        return analysisResultMapper.selectList(
            new LambdaQueryWrapper<AnalysisResultEntity>()
                .eq(AnalysisResultEntity::getUserId, userId)
                .eq(AnalysisResultEntity::getBookId, bookId)
                .eq(AnalysisResultEntity::getDeleted, 0)
                .orderByDesc(AnalysisResultEntity::getCreateTime)
                .orderByDesc(AnalysisResultEntity::getId)
                .last("LIMIT " + Math.max(1, Math.min(limit, 20)))
        );
    }

    public List<AnalysisResultEntity> findLatestAnalysisResultsForBooks(Long userId,
                                                                        List<Long> bookIds,
                                                                        int limitPerBook) {
        if (bookIds == null || bookIds.isEmpty()) {
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(limitPerBook, 20));
        String sql = """
            SELECT id,
                   user_id,
                   platform,
                   book_id,
                   channel_code,
                   board_code,
                   snapshot_id,
                   analysis_type,
                   chapter_count,
                   prompt_config_id,
                   model_name,
                   result_content,
                   result_json,
                   token_used,
                   cost_time,
                   create_time,
                   update_time,
                   deleted
            FROM (
                SELECT ar.*,
                       ROW_NUMBER() OVER (PARTITION BY ar.book_id ORDER BY ar.create_time DESC, ar.id DESC) AS rn
                FROM analysis_result ar
                WHERE ar.deleted = 0
                  AND ar.user_id = ?
                  AND ar.book_id IN (%s)
            ) ranked_analysis
            WHERE rn <= ?
            ORDER BY create_time DESC, id DESC
            """.formatted(inPlaceholders(bookIds.size()));
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.addAll(bookIds);
        args.add(safeLimit);
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            AnalysisResultEntity entity = new AnalysisResultEntity();
            entity.setId(rs.getLong("id"));
            entity.setUserId(readNullableLong(rs, "user_id"));
            entity.setPlatform(rs.getString("platform"));
            entity.setBookId(readNullableLong(rs, "book_id"));
            entity.setChannelCode(rs.getString("channel_code"));
            entity.setBoardCode(rs.getString("board_code"));
            entity.setSnapshotId(readNullableLong(rs, "snapshot_id"));
            entity.setAnalysisType(rs.getString("analysis_type"));
            entity.setChapterCount(readNullableInt(rs, "chapter_count"));
            entity.setPromptConfigId(readNullableLong(rs, "prompt_config_id"));
            entity.setModelName(rs.getString("model_name"));
            entity.setResultContent(rs.getString("result_content"));
            entity.setResultJson(rs.getString("result_json"));
            entity.setTokenUsed(readNullableInt(rs, "token_used"));
            entity.setCostTime(readNullableLong(rs, "cost_time"));
            entity.setCreateTime(readNullableLocalDateTime(rs, "create_time"));
            entity.setUpdateTime(readNullableLocalDateTime(rs, "update_time"));
            entity.setDeleted(readNullableInt(rs, "deleted"));
            return entity;
        }, args.toArray());
    }

    public List<RankEvidence> findLatestRankEvidenceForBook(Long bookId) {
        String sql = """
            WITH ranked AS (
                SELECT cr.id,
                       cr.platform,
                       cr.category,
                       cr.channel_code,
                       cr.board_code,
                       cr.snapshot_id,
                       cr.rank_no,
                       cr.book_id,
                       cr.book_name,
                       cr.author,
                       cr.intro,
                       cr.crawl_time,
                       rb.board_name,
                       rb.description AS channel_name,
                       rs.snapshot_time,
                       ROW_NUMBER() OVER (
                           PARTITION BY cr.platform,
                                        COALESCE(cr.channel_code, rb.channel_code, ''),
                                        COALESCE(cr.board_code, rb.board_code, '')
                           ORDER BY COALESCE(rs.snapshot_time, cr.crawl_time) DESC, cr.id DESC
                       ) AS latest_row
                FROM crawl_rank cr
                LEFT JOIN rank_snapshot rs ON rs.id = cr.snapshot_id AND rs.deleted = 0
                LEFT JOIN rank_board rb ON rb.id = rs.rank_board_id AND rb.deleted = 0
                WHERE cr.book_id = ?
                  AND cr.deleted = 0
            )
            SELECT id,
                   platform,
                   category,
                   channel_code,
                   board_code,
                   snapshot_id,
                   rank_no,
                   book_id,
                   book_name,
                   author,
                   intro,
                   crawl_time,
                   board_name,
                   channel_name,
                   snapshot_time
            FROM ranked
            WHERE latest_row = 1
            ORDER BY COALESCE(snapshot_time, crawl_time) DESC, rank_no ASC
            LIMIT 20
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new RankEvidence(
            rs.getLong("id"),
            rs.getString("platform"),
            rs.getString("channel_code"),
            rs.getString("board_code"),
            rs.getString("channel_name"),
            rs.getString("board_name"),
            readNullableLong(rs, "snapshot_id"),
            readNullableInt(rs, "rank_no"),
            rs.getLong("book_id"),
            rs.getString("book_name"),
            rs.getString("author"),
            rs.getString("intro"),
            readNullableLocalDateTime(rs, "snapshot_time"),
            readNullableLocalDateTime(rs, "crawl_time"),
            rs.getString("category")
        ), bookId);
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
                  AND UPPER(kc.source_type) <> 'ANALYSIS'
                """
        );
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (chunkId != null) {
            sql.append(" AND kc.id = ? AND kc.vector_status = 'INDEXED'");
            args.add(chunkId);
            if (pointId != null && !pointId.isBlank()) {
                sql.append(" AND kc.qdrant_point_id = ?");
                args.add(pointId);
            }
        } else if (pointId != null && !pointId.isBlank()) {
            sql.append(" AND kc.qdrant_point_id = ? AND kc.vector_status = 'INDEXED'");
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

    public List<KnowledgeSearchResultVO> findCrawledChapterSources(Long bookId,
                                                                   String platform,
                                                                   Integer chapterNo,
                                                                   int limit) {
        StringBuilder sql = new StringBuilder(
            """
                SELECT cc.id AS chapter_id,
                       cc.book_id,
                       cb.book_name,
                       cc.platform,
                       cc.chapter_no,
                       cc.chapter_title,
                       cc.content
                FROM crawl_chapter cc
                LEFT JOIN crawl_book cb ON cb.id = cc.book_id AND cb.deleted = 0
                WHERE cc.deleted = 0
                  AND cc.book_id = ?
                  AND cc.content IS NOT NULL
                  AND TRIM(cc.content) <> ''
                """
        );
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(bookId);
        if (platform != null && !platform.isBlank()) {
            sql.append(" AND cc.platform = ?");
            args.add(platform.trim());
        }
        if (chapterNo != null) {
            sql.append(" AND cc.chapter_no = ?");
            args.add(chapterNo);
        }
        sql.append(" ORDER BY cc.chapter_no ASC LIMIT ?");
        args.add(Math.max(1, Math.min(limit, 20)));

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            KnowledgeSearchResultVO vo = new KnowledgeSearchResultVO();
            vo.setChunkId(null);
            vo.setDocumentId(null);
            vo.setScore(0.5d);
            vo.setBookId(rs.getLong("book_id"));
            vo.setBookName(rs.getString("book_name"));
            vo.setPlatform(rs.getString("platform"));
            vo.setSourceType("CHAPTER");
            vo.setSourceRefId(rs.getLong("chapter_id"));
            int readChapterNo = rs.getInt("chapter_no");
            vo.setChapterNo(rs.wasNull() ? null : readChapterNo);
            vo.setAnalysisType(null);
            vo.setTitle(rs.getString("chapter_title"));
            vo.setPreview(buildPreview(rs.getString("content")));
            return vo;
        }, args.toArray());
    }

    public List<KnowledgeSearchResultVO> findLexicalSearchResults(String query,
                                                                  Long bookId,
                                                                  String platform,
                                                                  String sourceType,
                                                                  Integer chapterNo,
                                                                  String analysisType,
                                                                  int limit) {
        List<String> terms = lexicalTerms(query);
        if (terms.isEmpty()) {
            return List.of();
        }
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
                  AND kc.vector_status = 'INDEXED'
                  AND UPPER(kc.source_type) <> 'ANALYSIS'
                """
        );
        List<Object> args = new ArrayList<>();
        if (bookId != null) {
            sql.append(" AND kc.book_id = ?");
            args.add(bookId);
        }
        if (platform != null && !platform.isBlank()) {
            sql.append(" AND kd.platform = ?");
            args.add(platform.trim());
        }
        if (sourceType != null && !sourceType.isBlank()) {
            sql.append(" AND kc.source_type = ?");
            args.add(sourceType.trim().toUpperCase());
        }
        if (chapterNo != null) {
            sql.append(" AND kc.chapter_no = ?");
            args.add(chapterNo);
        }
        if (analysisType != null && !analysisType.isBlank()) {
            sql.append(" AND kc.analysis_type = ?");
            args.add(analysisType.trim());
        }
        sql.append(" AND (");
        for (int i = 0; i < terms.size(); i++) {
            if (i > 0) {
                sql.append(" OR ");
            }
            sql.append("""
                LOWER(kc.chunk_text) LIKE ? OR
                LOWER(kd.title) LIKE ? OR
                LOWER(COALESCE(cb.book_name, '')) LIKE ?
                """);
            String pattern = "%" + terms.get(i).toLowerCase() + "%";
            args.add(pattern);
            args.add(pattern);
            args.add(pattern);
        }
        sql.append(") ORDER BY kc.update_time DESC, kc.id DESC LIMIT ?");
        args.add(Math.max(1, Math.min(limit, 20)));

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            KnowledgeSearchResultVO vo = new KnowledgeSearchResultVO();
            vo.setChunkId(rs.getLong("chunk_id"));
            vo.setDocumentId(rs.getLong("document_id"));
            vo.setScore(lexicalScore(rowNum));
            Long readBookId = readNullableLong(rs, "book_id");
            vo.setBookId(readBookId);
            vo.setBookName(rs.getString("book_name"));
            vo.setPlatform(rs.getString("platform"));
            vo.setSourceType(rs.getString("source_type"));
            vo.setSourceRefId(readNullableLong(rs, "source_ref_id"));
            vo.setChapterNo(readNullableInt(rs, "chapter_no"));
            vo.setAnalysisType(rs.getString("analysis_type"));
            vo.setTitle(rs.getString("title"));
            vo.setPreview(buildPreview(rs.getString("chunk_text")));
            return vo;
        }, args.toArray());
    }

    public List<RankLookupResultVO> lookupLatestRanks(String platform,
                                                      String channelCode,
                                                      String boardCode,
                                                      String category,
                                                      Integer rankNo,
                                                      int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        StringBuilder sql = new StringBuilder(
            """
                SELECT cr.id AS rank_id,
                       cr.snapshot_id,
                       rs.snapshot_time AS snapshot_time,
                       cr.platform,
                       COALESCE(cr.channel_code, rb.channel_code) AS channel_code,
                       COALESCE(cr.board_code, rb.board_code) AS board_code,
                       rb.description AS channel_name,
                       rb.board_name,
                       cr.category,
                       cr.rank_no,
                       cr.book_id,
                       cr.book_name,
                       cr.author,
                       cr.intro
                """
        );
        List<Object> args = new ArrayList<>();
        sql.append(
            """
                FROM rank_board rb
                JOIN (
                    SELECT rb2.id AS rank_board_id,
                           MAX(rs2.snapshot_time) AS latest_snapshot_time
                    FROM rank_board rb2
                    JOIN rank_snapshot rs2 ON rs2.rank_board_id = rb2.id AND rs2.deleted = 0
                    WHERE rb2.deleted = 0
                      AND rb2.platform = ?
                """
        );
        args.add(platform);
        appendRankBoardFilters(sql, args, "rb2", channelCode, boardCode);
        sql.append(
            """
                    GROUP BY rb2.id
                ) latest ON latest.rank_board_id = rb.id
                JOIN rank_snapshot rs ON rs.rank_board_id = rb.id
                    AND rs.deleted = 0
                    AND rs.snapshot_time = latest.latest_snapshot_time
                JOIN crawl_rank cr ON cr.snapshot_id = rs.id
                    AND cr.deleted = 0
                    AND cr.platform = rb.platform
                WHERE rb.deleted = 0
                  AND rb.platform = ?
                """
        );
        args.add(platform);
        appendRankFilters(sql, args, "cr", "rb", channelCode, boardCode, category);
        if (rankNo != null) {
            sql.append(" AND cr.rank_no = ?");
            args.add(rankNo);
        }
        sql.append(" ORDER BY cr.rank_no ASC, cr.id ASC LIMIT ?");
        args.add(safeLimit);
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> mapRankLookupResult(rs), args.toArray());
    }

    public List<RankLookupResultVO> lookupRankSnapshotsInTimeWindow(String platform,
                                                                    String channelCode,
                                                                    String boardCode,
                                                                    String category,
                                                                    Integer rankNo,
                                                                    int timeWindowDays,
                                                                    int limit) {
        Optional<LocalDateTime> latestSnapshotTime = findLatestRankSnapshotTime(
            platform,
            channelCode,
            boardCode,
            category,
            rankNo
        );
        if (latestSnapshotTime.isEmpty()) {
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(limit, 100));
        LocalDateTime earliestSnapshotTime = latestSnapshotTime.get().minusDays(Math.max(1, Math.min(timeWindowDays, 365)));
        StringBuilder sql = new StringBuilder(
            """
                SELECT cr.id AS rank_id,
                       cr.snapshot_id,
                       rs.snapshot_time AS snapshot_time,
                       cr.platform,
                       COALESCE(cr.channel_code, rb.channel_code) AS channel_code,
                       COALESCE(cr.board_code, rb.board_code) AS board_code,
                       rb.description AS channel_name,
                       rb.board_name,
                       cr.category,
                       cr.rank_no,
                       cr.book_id,
                       cr.book_name,
                       cr.author,
                       cr.intro
                FROM rank_board rb
                JOIN rank_snapshot rs ON rs.rank_board_id = rb.id
                    AND rs.deleted = 0
                    AND rs.snapshot_time >= ?
                JOIN crawl_rank cr ON cr.snapshot_id = rs.id
                    AND cr.deleted = 0
                    AND cr.platform = rb.platform
                WHERE rb.deleted = 0
                  AND rb.platform = ?
                """
        );
        List<Object> args = new ArrayList<>();
        args.add(earliestSnapshotTime);
        args.add(platform);
        appendRankFilters(sql, args, "cr", "rb", channelCode, boardCode, category);
        if (rankNo != null) {
            sql.append(" AND cr.rank_no = ?");
            args.add(rankNo);
        }
        sql.append(" ORDER BY rs.snapshot_time DESC, cr.rank_no ASC, cr.id ASC LIMIT ?");
        args.add(safeLimit);
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> mapRankLookupResult(rs), args.toArray());
    }

    public List<RankLookupResultVO> lookupRankSnapshotsInDateRange(String platform,
                                                                   String channelCode,
                                                                   String boardCode,
                                                                   String category,
                                                                   Integer rankNo,
                                                                   LocalDate snapshotStartDate,
                                                                   LocalDate snapshotEndDate,
                                                                   int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        Optional<RankSnapshotDateBounds> bounds = findRankSnapshotDateBounds(
            platform,
            channelCode,
            boardCode,
            category,
            rankNo,
            snapshotStartDate,
            snapshotEndDate
        );
        if (bounds.isEmpty()) {
            return List.of();
        }
        List<RankLookupResultVO> latestRows = lookupRankSnapshotsOnDate(
            platform, channelCode, boardCode, category, rankNo, bounds.get().latest(), safeLimit
        );
        if (bounds.get().earliest().equals(bounds.get().latest())) {
            return latestRows;
        }
        List<RankLookupResultVO> earliestRows = lookupRankSnapshotsOnDate(
            platform, channelCode, boardCode, category, rankNo, bounds.get().earliest(), safeLimit
        );
        List<RankLookupResultVO> balanced = new ArrayList<>(safeLimit);
        int rowIndex = 0;
        while (balanced.size() < safeLimit
            && (rowIndex < latestRows.size() || rowIndex < earliestRows.size())) {
            if (rowIndex < latestRows.size()) {
                balanced.add(latestRows.get(rowIndex));
            }
            if (balanced.size() < safeLimit && rowIndex < earliestRows.size()) {
                balanced.add(earliestRows.get(rowIndex));
            }
            rowIndex++;
        }
        return balanced;
    }

    private Optional<RankSnapshotDateBounds> findRankSnapshotDateBounds(String platform,
                                                                         String channelCode,
                                                                         String boardCode,
                                                                         String category,
                                                                         Integer rankNo,
                                                                         LocalDate snapshotStartDate,
                                                                         LocalDate snapshotEndDate) {
        StringBuilder sql = new StringBuilder(
            """
                SELECT MIN(rs.snapshot_time) AS earliest_snapshot_time,
                       MAX(rs.snapshot_time) AS latest_snapshot_time
                FROM rank_board rb
                JOIN rank_snapshot rs ON rs.rank_board_id = rb.id
                    AND rs.deleted = 0
                    AND rs.snapshot_time >= ?
                    AND rs.snapshot_time < ?
                JOIN crawl_rank cr ON cr.snapshot_id = rs.id
                    AND cr.deleted = 0
                    AND cr.platform = rb.platform
                WHERE rb.deleted = 0
                  AND rb.platform = ?
                """
        );
        List<Object> args = new ArrayList<>();
        args.add(snapshotStartDate.atStartOfDay());
        args.add(snapshotEndDate.plusDays(1).atStartOfDay());
        args.add(platform);
        appendRankFilters(sql, args, "cr", "rb", channelCode, boardCode, category);
        if (rankNo != null) {
            sql.append(" AND cr.rank_no = ?");
            args.add(rankNo);
        }
        return jdbcTemplate.query(sql.toString(), rs -> {
            if (!rs.next()) {
                return Optional.empty();
            }
            Timestamp earliest = rs.getTimestamp("earliest_snapshot_time");
            Timestamp latest = rs.getTimestamp("latest_snapshot_time");
            if (earliest == null || latest == null) {
                return Optional.empty();
            }
            return Optional.of(new RankSnapshotDateBounds(
                earliest.toLocalDateTime().toLocalDate(),
                latest.toLocalDateTime().toLocalDate()
            ));
        }, args.toArray());
    }

    private List<RankLookupResultVO> lookupRankSnapshotsOnDate(String platform,
                                                                String channelCode,
                                                                String boardCode,
                                                                String category,
                                                                Integer rankNo,
                                                                LocalDate snapshotDate,
                                                                int limit) {
        StringBuilder sql = new StringBuilder(
            """
                SELECT cr.id AS rank_id,
                       cr.snapshot_id,
                       rs.snapshot_time AS snapshot_time,
                       cr.platform,
                       COALESCE(cr.channel_code, rb.channel_code) AS channel_code,
                       COALESCE(cr.board_code, rb.board_code) AS board_code,
                       rb.description AS channel_name,
                       rb.board_name,
                       cr.category,
                       cr.rank_no,
                       cr.book_id,
                       cr.book_name,
                       cr.author,
                       cr.intro
                FROM rank_board rb
                JOIN rank_snapshot rs ON rs.rank_board_id = rb.id
                    AND rs.deleted = 0
                    AND rs.snapshot_time >= ?
                    AND rs.snapshot_time < ?
                JOIN crawl_rank cr ON cr.snapshot_id = rs.id
                    AND cr.deleted = 0
                    AND cr.platform = rb.platform
                WHERE rb.deleted = 0
                  AND rb.platform = ?
                """
        );
        List<Object> args = new ArrayList<>();
        args.add(snapshotDate.atStartOfDay());
        args.add(snapshotDate.plusDays(1).atStartOfDay());
        args.add(platform);
        appendRankFilters(sql, args, "cr", "rb", channelCode, boardCode, category);
        if (rankNo != null) {
            sql.append(" AND cr.rank_no = ?");
            args.add(rankNo);
        }
        sql.append(" ORDER BY cr.rank_no ASC, rs.snapshot_time DESC, cr.id ASC LIMIT ?");
        args.add(Math.max(1, Math.min(limit, 100)));
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> mapRankLookupResult(rs), args.toArray());
    }

    private record RankSnapshotDateBounds(LocalDate earliest, LocalDate latest) {
    }

    private Optional<LocalDateTime> findLatestRankSnapshotTime(String platform,
                                                               String channelCode,
                                                               String boardCode,
                                                               String category,
                                                               Integer rankNo) {
        StringBuilder sql = new StringBuilder(
            """
                SELECT MAX(rs.snapshot_time) AS latest_snapshot_time
                FROM rank_board rb
                JOIN rank_snapshot rs ON rs.rank_board_id = rb.id
                    AND rs.deleted = 0
                JOIN crawl_rank cr ON cr.snapshot_id = rs.id
                    AND cr.deleted = 0
                    AND cr.platform = rb.platform
                WHERE rb.deleted = 0
                  AND rb.platform = ?
                """
        );
        List<Object> args = new ArrayList<>();
        args.add(platform);
        appendRankFilters(sql, args, "cr", "rb", channelCode, boardCode, category);
        if (rankNo != null) {
            sql.append(" AND cr.rank_no = ?");
            args.add(rankNo);
        }
        return jdbcTemplate.query(sql.toString(), rs -> {
            if (!rs.next()) {
                return Optional.empty();
            }
            return Optional.ofNullable(readNullableLocalDateTime(rs, "latest_snapshot_time"));
        }, args.toArray());
    }

    private RankLookupResultVO mapRankLookupResult(java.sql.ResultSet rs) throws java.sql.SQLException {
        RankLookupResultVO vo = new RankLookupResultVO();
        vo.setRankId(rs.getLong("rank_id"));
        vo.setSnapshotId(readNullableLong(rs, "snapshot_id"));
        vo.setSnapshotTime(readNullableLocalDateTime(rs, "snapshot_time"));
        vo.setPlatform(rs.getString("platform"));
        vo.setChannelCode(rs.getString("channel_code"));
        vo.setBoardCode(rs.getString("board_code"));
        vo.setChannelName(rs.getString("channel_name"));
        vo.setBoardName(rs.getString("board_name"));
        vo.setCategory(rs.getString("category"));
        vo.setRankNo(readNullableInt(rs, "rank_no"));
        vo.setBookId(readNullableLong(rs, "book_id"));
        vo.setBookName(rs.getString("book_name"));
        vo.setAuthor(rs.getString("author"));
        vo.setIntro(rs.getString("intro"));
        vo.setSourceLabel(buildRankSourceLabel(vo));
        return vo;
    }

    private void appendRankBoardFilters(StringBuilder sql,
                                        List<Object> args,
                                        String boardAlias,
                                        String channelCode,
                                        String boardCode) {
        if (channelCode != null && !channelCode.isBlank()) {
            String channelLabel = rankChannelLabel(channelCode);
            if (channelLabel != null) {
                sql.append(" AND (")
                    .append(boardAlias).append(".channel_code = ? OR ")
                    .append(boardAlias).append(".description LIKE ?");
                args.add(channelCode.trim());
                args.add("%" + channelLabel + "%");
            } else {
                sql.append(" AND ").append(boardAlias).append(".channel_code = ?");
                args.add(channelCode.trim());
            }
            if (channelLabel != null) {
                sql.append(")");
            }
        }
        if (boardCode != null && !boardCode.isBlank()) {
            sql.append(" AND ").append(boardAlias).append(".board_code = ?");
            args.add(boardCode);
        }
    }

    private void appendRankFilters(StringBuilder sql,
                                   List<Object> args,
                                   String rankAlias,
                                   String boardAlias,
                                   String channelCode,
                                   String boardCode,
                                   String category) {
        if (channelCode != null && !channelCode.isBlank()) {
            String channelLabel = rankChannelLabel(channelCode);
            if (channelLabel != null) {
                sql.append(" AND (")
                    .append(rankAlias).append(".channel_code = ? OR ")
                    .append(boardAlias).append(".channel_code = ? OR ")
                    .append(boardAlias).append(".description LIKE ?");
                args.add(channelCode.trim());
                args.add(channelCode.trim());
                args.add("%" + channelLabel + "%");
            } else {
                sql.append(" AND (")
                    .append(rankAlias).append(".channel_code = ? OR ")
                    .append(boardAlias).append(".channel_code = ?");
                args.add(channelCode.trim());
                args.add(channelCode.trim());
            }
            sql.append(")");
        }
        if (boardCode != null && !boardCode.isBlank()) {
            sql.append(" AND (").append(rankAlias).append(".board_code = ? OR ").append(boardAlias).append(".board_code = ?)");
            args.add(boardCode);
            args.add(boardCode);
        }
        if (category != null && !category.isBlank()) {
            sql.append(" AND (")
                .append(rankAlias).append(".category LIKE ? OR ")
                .append(boardAlias).append(".board_name LIKE ? OR ")
                .append(boardAlias).append(".description LIKE ?)");
            String pattern = "%" + category + "%";
            args.add(pattern);
            args.add(pattern);
            args.add(pattern);
        }
    }

    private String rankChannelLabel(String channelCode) {
        String normalized = channelCode == null ? "" : channelCode.trim();
        if ("male-new".equals(normalized)) {
            return "男频新书榜";
        }
        if ("female-new".equals(normalized)) {
            return "女频新书榜";
        }
        return null;
    }

    private String inClause(String column, int size) {
        return column + " IN (" + String.join(", ", java.util.Collections.nCopies(size, "?")) + ")";
    }

    private String inPlaceholders(int size) {
        return String.join(", ", java.util.Collections.nCopies(size, "?"));
    }

    private String buildRankSourceLabel(RankLookupResultVO vo) {
        String channel = firstNonBlank(vo.getChannelName(), vo.getChannelCode());
        String board = firstNonBlank(vo.getBoardName(), vo.getCategory(), vo.getBoardCode());
        String rank = vo.getRankNo() == null ? "" : " #" + vo.getRankNo();
        return (channel == null ? "榜单" : channel) + " / " + (board == null ? "未知榜单" : board) + rank;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private List<String> lexicalTerms(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String normalized = query.trim().replaceAll("[\\p{Punct}，。！？、；：\"'（）【】《》]+", " ");
        List<String> terms = new ArrayList<>();
        for (String token : normalized.split("\\s+")) {
            String term = token.trim();
            if (term.length() >= 2 && terms.stream().noneMatch(existing -> existing.equalsIgnoreCase(term))) {
                terms.add(term);
            }
            if (terms.size() >= 5) {
                break;
            }
        }
        if (terms.isEmpty() && normalized.length() >= 2) {
            terms.add(normalized);
        }
        return terms;
    }

    private double lexicalScore(int rowNum) {
        return Math.max(0.4d, 0.45d - rowNum * 0.01d);
    }

    public List<Long> findBookIdsForKnowledgeRebuild(String mode, int limit) {
        return findBookIdsForKnowledgeRebuild(mode, limit, null, null, 10);
    }

    public List<KnowledgeHealthVO.ChunkStat> countChunksBySourceAndStatus(String embeddingModel, Integer embeddingDimension) {
        String sql = """
            SELECT source_type, vector_status, COUNT(*) AS cnt
            FROM knowledge_chunk
            WHERE deleted = 0
              AND embedding_model = ?
              AND embedding_dimension = ?
            GROUP BY source_type, vector_status
            ORDER BY source_type, vector_status
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            KnowledgeHealthVO.ChunkStat stat = new KnowledgeHealthVO.ChunkStat();
            stat.setSourceType(rs.getString("source_type"));
            stat.setVectorStatus(rs.getString("vector_status"));
            stat.setCount(rs.getLong("cnt"));
            return stat;
        }, embeddingModel, embeddingDimension);
    }

    public KnowledgeHealthVO.CoverageStat rankCoverage(String embeddingModel, Integer embeddingDimension) {
        String sql = """
            SELECT COUNT(DISTINCT cr.id) AS total_count,
                   COUNT(DISTINCT kc.source_ref_id) AS indexed_count
            FROM crawl_rank cr
            LEFT JOIN knowledge_chunk kc
              ON kc.source_type = 'RANK'
             AND kc.source_ref_id = cr.id
             AND kc.vector_status = 'INDEXED'
             AND kc.deleted = 0
             AND kc.embedding_model = ?
             AND kc.embedding_dimension = ?
            WHERE cr.deleted = 0
            """;
        return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> coverageStat(rs), embeddingModel, embeddingDimension);
    }

    public KnowledgeHealthVO.CoverageStat chapterCoverage(String embeddingModel, Integer embeddingDimension) {
        String sql = """
            SELECT COUNT(DISTINCT cc.id) AS total_count,
                   COUNT(DISTINCT kc.source_ref_id) AS indexed_count
            FROM crawl_chapter cc
            LEFT JOIN knowledge_chunk kc
              ON kc.source_type = 'CHAPTER'
             AND kc.source_ref_id = cc.id
             AND kc.vector_status = 'INDEXED'
             AND kc.deleted = 0
             AND kc.embedding_model = ?
             AND kc.embedding_dimension = ?
            WHERE cc.deleted = 0
            """;
        return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> coverageStat(rs), embeddingModel, embeddingDimension);
    }

    public List<KnowledgeHealthVO.JobStat> countKnowledgeIndexJobsByStatus() {
        String sql = """
            SELECT status, COUNT(*) AS cnt
            FROM async_job
            WHERE deleted = 0
              AND job_type = 'KNOWLEDGE_INDEX_BOOK'
            GROUP BY status
            ORDER BY status
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            KnowledgeHealthVO.JobStat stat = new KnowledgeHealthVO.JobStat();
            stat.setStatus(rs.getString("status"));
            stat.setCount(rs.getLong("cnt"));
            return stat;
        });
    }

    private KnowledgeHealthVO.CoverageStat coverageStat(java.sql.ResultSet rs) throws java.sql.SQLException {
        long total = rs.getLong("total_count");
        long indexed = rs.getLong("indexed_count");
        KnowledgeHealthVO.CoverageStat stat = new KnowledgeHealthVO.CoverageStat();
        stat.setTotal(total);
        stat.setIndexed(indexed);
        stat.setMissing(Math.max(0, total - indexed));
        return stat;
    }

    public List<Long> findBookIdsForKnowledgeRebuild(String mode, int limit, String embeddingModel, Integer embeddingDimension) {
        return findBookIdsForKnowledgeRebuild(mode, limit, embeddingModel, embeddingDimension, 10);
    }

    public List<Long> findBookIdsForKnowledgeRebuild(
        String mode,
        int limit,
        String embeddingModel,
        Integer embeddingDimension,
        int maxChapters
    ) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        int safeMaxChapters = Math.max(1, maxChapters);
        String normalizedMode = mode == null ? "FAILED_ONLY" : mode.trim().toUpperCase();
        String normalizedEmbeddingModel = embeddingModel == null ? null : embeddingModel.trim();
        String sql;
        Object[] args;
        if ("FULL_REINDEX".equals(normalizedMode)) {
            sql = """
                SELECT b.id
                FROM crawl_book b
                WHERE b.deleted = 0
                  AND (
                    (
                      NULLIF(TRIM(COALESCE(b.intro, '')), '') IS NOT NULL
                      AND NOT EXISTS (
                        SELECT 1
                        FROM knowledge_chunk kc
                        WHERE kc.book_id = b.id
                          AND kc.source_type = 'INTRO'
                          AND kc.source_ref_id = b.id
                          AND kc.deleted = 0
                          AND kc.vector_status = 'INDEXED'
                          AND kc.chunk_strategy_version = 'rag-v2'
                          AND kc.embedding_model = ?
                          AND kc.embedding_dimension = ?
                      )
                    )
                    OR EXISTS (
                      SELECT 1
                      FROM crawl_rank cr
                      WHERE cr.book_id = b.id
                        AND cr.deleted = 0
                        AND NOT EXISTS (
                          SELECT 1
                          FROM knowledge_chunk kc
                          WHERE kc.book_id = b.id
                            AND kc.source_type = 'RANK'
                            AND kc.source_ref_id = cr.id
                            AND kc.deleted = 0
                            AND kc.vector_status = 'INDEXED'
                            AND kc.chunk_strategy_version = 'rag-v2'
                            AND kc.embedding_model = ?
                            AND kc.embedding_dimension = ?
                        )
                    )
                    OR EXISTS (
                      SELECT 1
                      FROM (
                        SELECT cc_inner.id,
                               cc_inner.book_id,
                               ROW_NUMBER() OVER (PARTITION BY cc_inner.book_id ORDER BY cc_inner.chapter_no ASC) AS rn
                        FROM crawl_chapter cc_inner
                        WHERE cc_inner.deleted = 0
                          AND cc_inner.book_id IS NOT NULL
                      ) cc
                      WHERE cc.book_id = b.id
                        AND cc.rn <= ?
                        AND NOT EXISTS (
                          SELECT 1
                          FROM knowledge_chunk kc
                          WHERE kc.book_id = b.id
                            AND kc.source_type = 'CHAPTER'
                            AND kc.source_ref_id = cc.id
                            AND kc.deleted = 0
                            AND kc.vector_status = 'INDEXED'
                            AND kc.chunk_strategy_version = 'rag-v2'
                            AND kc.embedding_model = ?
                            AND kc.embedding_dimension = ?
                        )
                    )
                  )
                ORDER BY b.id ASC
                LIMIT ?
                """;
            args = new Object[] {
                normalizedEmbeddingModel,
                embeddingDimension,
                normalizedEmbeddingModel,
                embeddingDimension,
                safeMaxChapters,
                normalizedEmbeddingModel,
                embeddingDimension,
                safeLimit
            };
        } else if ("ALL".equals(normalizedMode)) {
            sql = """
                SELECT id
                FROM crawl_book
                WHERE deleted = 0
                ORDER BY id ASC
                LIMIT ?
                """;
            args = new Object[] {safeLimit};
        } else if ("RANK_MISSING".equals(normalizedMode) || "RANK_INCREMENTAL".equals(normalizedMode)) {
            sql = """
                SELECT DISTINCT cr.book_id
                FROM crawl_rank cr
                WHERE cr.deleted = 0
                  AND cr.book_id IS NOT NULL
                  AND NOT EXISTS (
                      SELECT 1
                      FROM knowledge_chunk kc
                      WHERE kc.book_id = cr.book_id
                        AND kc.source_type = 'RANK'
                        AND kc.source_ref_id = cr.id
                        AND kc.deleted = 0
                        AND kc.vector_status = 'INDEXED'
                        AND kc.chunk_strategy_version = 'rag-v2'
                  )
                ORDER BY cr.book_id ASC
                LIMIT ?
                """;
            args = new Object[] {safeLimit};
        } else if ("CHAPTER_MISSING".equals(normalizedMode)) {
            sql = """
                SELECT DISTINCT cc.book_id
                FROM (
                    SELECT cc_inner.id,
                           cc_inner.book_id,
                           ROW_NUMBER() OVER (PARTITION BY cc_inner.book_id ORDER BY cc_inner.chapter_no ASC) AS rn
                    FROM crawl_chapter cc_inner
                    WHERE cc_inner.deleted = 0
                      AND cc_inner.book_id IS NOT NULL
                ) cc
                WHERE cc.rn <= ?
                  AND NOT EXISTS (
                      SELECT 1
                      FROM knowledge_chunk kc
                      WHERE kc.book_id = cc.book_id
                        AND kc.source_type = 'CHAPTER'
                        AND kc.source_ref_id = cc.id
                        AND kc.deleted = 0
                        AND kc.vector_status = 'INDEXED'
                        AND kc.chunk_strategy_version = 'rag-v2'
                        AND kc.embedding_model = ?
                        AND kc.embedding_dimension = ?
                  )
                ORDER BY cc.book_id ASC
                LIMIT ?
                """;
            args = new Object[] {safeMaxChapters, normalizedEmbeddingModel, embeddingDimension, safeLimit};
        } else {
            sql = """
                SELECT DISTINCT book_id
                FROM (
                    SELECT kc.book_id
                    FROM knowledge_chunk kc
                    WHERE kc.deleted = 0
                      AND kc.book_id IS NOT NULL
                      AND (
                          kc.vector_status = 'FAILED'
                          OR kc.vector_status = 'PENDING'
                          OR kc.chunk_strategy_version IS NULL
                          OR kc.chunk_strategy_version <> 'rag-v2'
                      )
                    UNION
                    SELECT cr.book_id
                    FROM crawl_rank cr
                    WHERE cr.deleted = 0
                      AND cr.book_id IS NOT NULL
                      AND NOT EXISTS (
                          SELECT 1
                          FROM knowledge_chunk kc
                          WHERE kc.book_id = cr.book_id
                            AND kc.source_type = 'RANK'
                            AND kc.deleted = 0
                            AND kc.vector_status = 'INDEXED'
                      )
                ) pending_books
                ORDER BY book_id ASC
                LIMIT ?
                """;
            args = new Object[] {safeLimit};
        }
        return jdbcTemplate.queryForList(sql, Long.class, args);
    }

    private Long readNullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Integer readNullableInt(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private LocalDateTime readNullableLocalDateTime(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
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

    public record RankEvidence(
        Long id,
        String platform,
        String channelCode,
        String boardCode,
        String channelName,
        String boardName,
        Long snapshotId,
        Integer rankNo,
        Long bookId,
        String bookName,
        String author,
        String intro,
        LocalDateTime snapshotTime,
        LocalDateTime crawlTime,
        String category
    ) {
    }
}
