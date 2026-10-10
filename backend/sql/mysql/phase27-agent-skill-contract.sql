DELIMITER $$

DROP PROCEDURE IF EXISTS noval_phase27_add_column_if_missing $$
CREATE PROCEDURE noval_phase27_add_column_if_missing(
    IN p_table_name VARCHAR(128),
    IN p_column_name VARCHAR(128),
    IN p_ddl TEXT
)
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name = p_table_name
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = p_table_name AND column_name = p_column_name
    ) THEN
        SET @ddl = p_ddl;
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END $$

DELIMITER ;

CALL noval_phase27_add_column_if_missing('ai_skill_candidate', 'description',
    'ALTER TABLE ai_skill_candidate ADD COLUMN description VARCHAR(1000) NULL AFTER title');
CALL noval_phase27_add_column_if_missing('ai_skill_candidate', 'requested_capabilities_json',
    'ALTER TABLE ai_skill_candidate ADD COLUMN requested_capabilities_json JSON NULL AFTER allowed_tools_json');
CALL noval_phase27_add_column_if_missing('ai_skill_candidate', 'skill_metadata_json',
    'ALTER TABLE ai_skill_candidate ADD COLUMN skill_metadata_json JSON NULL AFTER requested_capabilities_json');

CALL noval_phase27_add_column_if_missing('ai_runtime_skill', 'description',
    'ALTER TABLE ai_runtime_skill ADD COLUMN description VARCHAR(1000) NULL AFTER title');
CALL noval_phase27_add_column_if_missing('ai_runtime_skill', 'requested_capabilities_json',
    'ALTER TABLE ai_runtime_skill ADD COLUMN requested_capabilities_json JSON NULL AFTER allowed_tools_json');
CALL noval_phase27_add_column_if_missing('ai_runtime_skill', 'skill_metadata_json',
    'ALTER TABLE ai_runtime_skill ADD COLUMN skill_metadata_json JSON NULL AFTER requested_capabilities_json');

UPDATE ai_skill_candidate
SET description = COALESCE(NULLIF(description, ''), NULLIF(title, ''), skill_id),
    requested_capabilities_json = COALESCE(
        requested_capabilities_json,
        JSON_MERGE_PRESERVE(
            CASE WHEN JSON_CONTAINS(IF(JSON_VALID(allowed_tools_json), allowed_tools_json, JSON_ARRAY()), JSON_QUOTE('rank.lookup'))
                OR JSON_CONTAINS(IF(JSON_VALID(allowed_tools_json), allowed_tools_json, JSON_ARRAY()), JSON_QUOTE('rank.research_pack'))
                THEN JSON_ARRAY('market.read') ELSE JSON_ARRAY() END,
            CASE WHEN JSON_CONTAINS(IF(JSON_VALID(allowed_tools_json), allowed_tools_json, JSON_ARRAY()), JSON_QUOTE('rank.refresh'))
                THEN JSON_ARRAY('market.refresh') ELSE JSON_ARRAY() END,
            CASE WHEN JSON_CONTAINS(IF(JSON_VALID(allowed_tools_json), allowed_tools_json, JSON_ARRAY()), JSON_QUOTE('book.search'))
                OR JSON_CONTAINS(IF(JSON_VALID(allowed_tools_json), allowed_tools_json, JSON_ARRAY()), JSON_QUOTE('book.research_pack'))
                OR JSON_CONTAINS(IF(JSON_VALID(allowed_tools_json), allowed_tools_json, JSON_ARRAY()), JSON_QUOTE('knowledge.vector_search'))
                THEN JSON_ARRAY('book.read') ELSE JSON_ARRAY() END,
            CASE WHEN JSON_CONTAINS(IF(JSON_VALID(allowed_tools_json), allowed_tools_json, JSON_ARRAY()), JSON_QUOTE('project.resolve'))
                THEN JSON_ARRAY('project.resolve') ELSE JSON_ARRAY() END,
            CASE WHEN JSON_CONTAINS(IF(JSON_VALID(allowed_tools_json), allowed_tools_json, JSON_ARRAY()), JSON_QUOTE('project.retrieve'))
                THEN JSON_ARRAY('project.retrieve') ELSE JSON_ARRAY() END,
            CASE WHEN JSON_CONTAINS(IF(JSON_VALID(allowed_tools_json), allowed_tools_json, JSON_ARRAY()), JSON_QUOTE('project.foreshadowing.list'))
                OR JSON_CONTAINS(IF(JSON_VALID(allowed_tools_json), allowed_tools_json, JSON_ARRAY()), JSON_QUOTE('project.timeline_lookup'))
                OR JSON_CONTAINS(IF(JSON_VALID(allowed_tools_json), allowed_tools_json, JSON_ARRAY()), JSON_QUOTE('project.character_state_lookup'))
                OR JSON_CONTAINS(IF(JSON_VALID(allowed_tools_json), allowed_tools_json, JSON_ARRAY()), JSON_QUOTE('project.world_rule_lookup'))
                THEN JSON_ARRAY('project.continuity.read') ELSE JSON_ARRAY() END,
            CASE WHEN JSON_CONTAINS(IF(JSON_VALID(allowed_tools_json), allowed_tools_json, JSON_ARRAY()), JSON_QUOTE('memory.project_context'))
                THEN JSON_ARRAY('memory.project.read') ELSE JSON_ARRAY() END,
            CASE WHEN JSON_CONTAINS(IF(JSON_VALID(allowed_tools_json), allowed_tools_json, JSON_ARRAY()), JSON_QUOTE('skill.lookup'))
                THEN JSON_ARRAY('skill.activate') ELSE JSON_ARRAY() END,
            CASE WHEN JSON_CONTAINS(IF(JSON_VALID(allowed_tools_json), allowed_tools_json, JSON_ARRAY()), JSON_QUOTE('reader.simulate_feedback'))
                THEN JSON_ARRAY('review.reader') ELSE JSON_ARRAY() END,
            CASE WHEN JSON_CONTAINS(IF(JSON_VALID(allowed_tools_json), allowed_tools_json, JSON_ARRAY()), JSON_QUOTE('editor.risk_check'))
                THEN JSON_ARRAY('review.editor') ELSE JSON_ARRAY() END
        )
    ),
    skill_metadata_json = COALESCE(skill_metadata_json, JSON_OBJECT('legacyFormat', TRUE));

UPDATE ai_runtime_skill
SET description = COALESCE(NULLIF(description, ''), NULLIF(title, ''), skill_id),
    requested_capabilities_json = COALESCE(
        requested_capabilities_json,
        JSON_MERGE_PRESERVE(
            CASE WHEN JSON_CONTAINS(IF(JSON_VALID(allowed_tools_json), allowed_tools_json, JSON_ARRAY()), JSON_QUOTE('rank.lookup'))
                OR JSON_CONTAINS(IF(JSON_VALID(allowed_tools_json), allowed_tools_json, JSON_ARRAY()), JSON_QUOTE('rank.research_pack'))
                THEN JSON_ARRAY('market.read') ELSE JSON_ARRAY() END,
            CASE WHEN JSON_CONTAINS(IF(JSON_VALID(allowed_tools_json), allowed_tools_json, JSON_ARRAY()), JSON_QUOTE('rank.refresh'))
                THEN JSON_ARRAY('market.refresh') ELSE JSON_ARRAY() END,
            CASE WHEN JSON_CONTAINS(IF(JSON_VALID(allowed_tools_json), allowed_tools_json, JSON_ARRAY()), JSON_QUOTE('book.search'))
                OR JSON_CONTAINS(IF(JSON_VALID(allowed_tools_json), allowed_tools_json, JSON_ARRAY()), JSON_QUOTE('book.research_pack'))
                OR JSON_CONTAINS(IF(JSON_VALID(allowed_tools_json), allowed_tools_json, JSON_ARRAY()), JSON_QUOTE('knowledge.vector_search'))
                THEN JSON_ARRAY('book.read') ELSE JSON_ARRAY() END,
            CASE WHEN JSON_CONTAINS(IF(JSON_VALID(allowed_tools_json), allowed_tools_json, JSON_ARRAY()), JSON_QUOTE('project.resolve'))
                THEN JSON_ARRAY('project.resolve') ELSE JSON_ARRAY() END,
            CASE WHEN JSON_CONTAINS(IF(JSON_VALID(allowed_tools_json), allowed_tools_json, JSON_ARRAY()), JSON_QUOTE('project.retrieve'))
                THEN JSON_ARRAY('project.retrieve') ELSE JSON_ARRAY() END,
            CASE WHEN JSON_CONTAINS(IF(JSON_VALID(allowed_tools_json), allowed_tools_json, JSON_ARRAY()), JSON_QUOTE('project.foreshadowing.list'))
                OR JSON_CONTAINS(IF(JSON_VALID(allowed_tools_json), allowed_tools_json, JSON_ARRAY()), JSON_QUOTE('project.timeline_lookup'))
                OR JSON_CONTAINS(IF(JSON_VALID(allowed_tools_json), allowed_tools_json, JSON_ARRAY()), JSON_QUOTE('project.character_state_lookup'))
                OR JSON_CONTAINS(IF(JSON_VALID(allowed_tools_json), allowed_tools_json, JSON_ARRAY()), JSON_QUOTE('project.world_rule_lookup'))
                THEN JSON_ARRAY('project.continuity.read') ELSE JSON_ARRAY() END,
            CASE WHEN JSON_CONTAINS(IF(JSON_VALID(allowed_tools_json), allowed_tools_json, JSON_ARRAY()), JSON_QUOTE('memory.project_context'))
                THEN JSON_ARRAY('memory.project.read') ELSE JSON_ARRAY() END,
            CASE WHEN JSON_CONTAINS(IF(JSON_VALID(allowed_tools_json), allowed_tools_json, JSON_ARRAY()), JSON_QUOTE('skill.lookup'))
                THEN JSON_ARRAY('skill.activate') ELSE JSON_ARRAY() END,
            CASE WHEN JSON_CONTAINS(IF(JSON_VALID(allowed_tools_json), allowed_tools_json, JSON_ARRAY()), JSON_QUOTE('reader.simulate_feedback'))
                THEN JSON_ARRAY('review.reader') ELSE JSON_ARRAY() END,
            CASE WHEN JSON_CONTAINS(IF(JSON_VALID(allowed_tools_json), allowed_tools_json, JSON_ARRAY()), JSON_QUOTE('editor.risk_check'))
                THEN JSON_ARRAY('review.editor') ELSE JSON_ARRAY() END
        )
    ),
    skill_metadata_json = COALESCE(skill_metadata_json, JSON_OBJECT('legacyFormat', TRUE));

DROP PROCEDURE IF EXISTS noval_phase27_add_column_if_missing;
