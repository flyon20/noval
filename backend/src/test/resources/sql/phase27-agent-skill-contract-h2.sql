ALTER TABLE ai_skill_candidate ADD COLUMN IF NOT EXISTS description VARCHAR(1000);
ALTER TABLE ai_skill_candidate ADD COLUMN IF NOT EXISTS requested_capabilities_json CLOB;
ALTER TABLE ai_skill_candidate ADD COLUMN IF NOT EXISTS skill_metadata_json CLOB;

ALTER TABLE ai_runtime_skill ADD COLUMN IF NOT EXISTS description VARCHAR(1000);
ALTER TABLE ai_runtime_skill ADD COLUMN IF NOT EXISTS requested_capabilities_json CLOB;
ALTER TABLE ai_runtime_skill ADD COLUMN IF NOT EXISTS skill_metadata_json CLOB;

UPDATE ai_skill_candidate
SET description = COALESCE(NULLIF(description, ''), title),
    requested_capabilities_json = COALESCE(
        requested_capabilities_json,
        CONCAT(
            '[',
            REGEXP_REPLACE(CONCAT(
                CASE WHEN allowed_tools_json LIKE '%"rank.lookup"%' OR allowed_tools_json LIKE '%"rank.research_pack"%' THEN '"market.read",' ELSE '' END,
                CASE WHEN allowed_tools_json LIKE '%"rank.refresh"%' THEN '"market.refresh",' ELSE '' END,
                CASE WHEN allowed_tools_json LIKE '%"book.search"%' OR allowed_tools_json LIKE '%"book.research_pack"%' OR allowed_tools_json LIKE '%"knowledge.vector_search"%' THEN '"book.read",' ELSE '' END,
                CASE WHEN allowed_tools_json LIKE '%"project.resolve"%' THEN '"project.resolve",' ELSE '' END,
                CASE WHEN allowed_tools_json LIKE '%"project.retrieve"%' THEN '"project.retrieve",' ELSE '' END,
                CASE WHEN allowed_tools_json LIKE '%"project.foreshadowing.list"%' OR allowed_tools_json LIKE '%"project.timeline_lookup"%' OR allowed_tools_json LIKE '%"project.character_state_lookup"%' OR allowed_tools_json LIKE '%"project.world_rule_lookup"%' THEN '"project.continuity.read",' ELSE '' END,
                CASE WHEN allowed_tools_json LIKE '%"memory.project_context"%' THEN '"memory.project.read",' ELSE '' END,
                CASE WHEN allowed_tools_json LIKE '%"skill.lookup"%' THEN '"skill.activate",' ELSE '' END,
                CASE WHEN allowed_tools_json LIKE '%"reader.simulate_feedback"%' THEN '"review.reader",' ELSE '' END,
                CASE WHEN allowed_tools_json LIKE '%"editor.risk_check"%' THEN '"review.editor",' ELSE '' END
            ), ',$', ''),
            ']'
        )
    ),
    skill_metadata_json = COALESCE(skill_metadata_json, '{"legacyFormat":true}');

UPDATE ai_runtime_skill
SET description = COALESCE(NULLIF(description, ''), title),
    requested_capabilities_json = COALESCE(
        requested_capabilities_json,
        CONCAT(
            '[',
            REGEXP_REPLACE(CONCAT(
                CASE WHEN allowed_tools_json LIKE '%"rank.lookup"%' OR allowed_tools_json LIKE '%"rank.research_pack"%' THEN '"market.read",' ELSE '' END,
                CASE WHEN allowed_tools_json LIKE '%"rank.refresh"%' THEN '"market.refresh",' ELSE '' END,
                CASE WHEN allowed_tools_json LIKE '%"book.search"%' OR allowed_tools_json LIKE '%"book.research_pack"%' OR allowed_tools_json LIKE '%"knowledge.vector_search"%' THEN '"book.read",' ELSE '' END,
                CASE WHEN allowed_tools_json LIKE '%"project.resolve"%' THEN '"project.resolve",' ELSE '' END,
                CASE WHEN allowed_tools_json LIKE '%"project.retrieve"%' THEN '"project.retrieve",' ELSE '' END,
                CASE WHEN allowed_tools_json LIKE '%"project.foreshadowing.list"%' OR allowed_tools_json LIKE '%"project.timeline_lookup"%' OR allowed_tools_json LIKE '%"project.character_state_lookup"%' OR allowed_tools_json LIKE '%"project.world_rule_lookup"%' THEN '"project.continuity.read",' ELSE '' END,
                CASE WHEN allowed_tools_json LIKE '%"memory.project_context"%' THEN '"memory.project.read",' ELSE '' END,
                CASE WHEN allowed_tools_json LIKE '%"skill.lookup"%' THEN '"skill.activate",' ELSE '' END,
                CASE WHEN allowed_tools_json LIKE '%"reader.simulate_feedback"%' THEN '"review.reader",' ELSE '' END,
                CASE WHEN allowed_tools_json LIKE '%"editor.risk_check"%' THEN '"review.editor",' ELSE '' END
            ), ',$', ''),
            ']'
        )
    ),
    skill_metadata_json = COALESCE(skill_metadata_json, '{"legacyFormat":true}');
