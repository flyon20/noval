from __future__ import annotations

from app.services.harness.contracts import SkillUseState
from app.services.intents import Intent
from app.services.skills.mediation import SkillMediator
from app.services.skills.runtime_skill import RuntimeSkill


def runtime_skill(skill_id: str, instructions: str, *, version: str = "1.0.0") -> RuntimeSkill:
    return RuntimeSkill(
        skillId=skill_id,
        version=version,
        intents=(Intent.market_scan,),
        triggers=("rank",),
        instructions=instructions,
        title=skill_id,
        description=f"Descriptor for {skill_id}",
        requestedCapabilities=("market.read",),
        source="backend",
        status="ACTIVE",
        contentHash=(skill_id[0] * 64),
    )


def test_activates_complete_skill_and_builds_bom_from_actual_context() -> None:
    skill = runtime_skill("market-skill", "Complete governed instructions.")
    expected_prompt = skill.activation_prompt()

    result = SkillMediator().mediate([(skill, ("intent_match",))], max_chars=len(expected_prompt))

    assert result.prompt == expected_prompt
    assert result.activatedSkillIds == ("market-skill",)
    assert result.records[0].state is SkillUseState.ACTIVATED
    assert result.records[0].bodyInjected is True
    assert result.bom.skills == (skill.trace_pin(),)


def test_rejects_entire_skill_when_budget_cannot_fit_body() -> None:
    skill = runtime_skill("market-skill", "Atomic body " + ("x" * 200))

    result = SkillMediator().mediate([(skill, ("intent_match",))], max_chars=80)

    assert result.prompt == ""
    assert result.activations == ()
    assert result.bom.skills == ()
    assert result.records[0].state is SkillUseState.REJECTED
    assert result.records[0].rejectionReasons == ("budget",)
    assert result.records[0].bodyInjected is False


def test_zero_budget_disables_dedicated_skill_cap_and_activates_all_eligible_skills() -> None:
    first = runtime_skill("first-market-skill", "First complete governed body. " + ("a" * 5000))
    second = runtime_skill("second-market-skill", "Second complete governed body. " + ("b" * 5000))

    result = SkillMediator().mediate(
        [
            (first, ("intent_match",)),
            (second, ("task_match",)),
        ],
        max_chars=0,
        eligible_skill_ids={first.skillId, second.skillId},
    )

    assert result.activatedSkillIds == (first.skillId, second.skillId)
    assert first.activation_prompt() in result.prompt
    assert second.activation_prompt() in result.prompt
    assert all(record.state is SkillUseState.ACTIVATED for record in result.records)


def test_deduplicates_same_skill_version_and_preserves_candidate_reasons() -> None:
    skill = runtime_skill("market-skill", "Complete governed instructions.")

    result = SkillMediator().mediate(
        [(skill, ("task_match",)), (skill, ("intent_match",))],
        max_chars=10_000,
    )

    assert result.activatedSkillIds == ("market-skill",)
    assert result.records[0].candidateReasons == ("task_match", "intent_match")
    assert result.trace_summary()["activatedCount"] == 1


def test_matching_preferred_skill_is_activated_first_within_existing_budget() -> None:
    automatic = runtime_skill("automatic-market-skill", "Automatic market guidance.")
    preferred = runtime_skill("preferred-market-skill", "Preferred market guidance.")

    result = SkillMediator().mediate(
        [
            (automatic, ("intent_match",)),
            (preferred, ("intent_match", "user_preferred")),
        ],
        max_chars=len(preferred.activation_prompt()),
        preferred_skill_id=preferred.skillId,
    )

    assert result.activatedSkillIds == (preferred.skillId,)
    assert result.preferredSkillStatus == "activated"
    assert result.decisions[0].descriptor.skillId == preferred.skillId


def test_unmatched_preferred_skill_does_not_add_candidates_or_activations() -> None:
    automatic = runtime_skill("automatic-market-skill", "Automatic market guidance.")

    result = SkillMediator().mediate(
        [(automatic, ("intent_match",))],
        max_chars=10_000,
        eligible_skill_ids={automatic.skillId},
        preferred_skill_id="missing-outline-skill",
    )

    assert result.preferredSkillStatus == "not_matched"
    assert [decision.descriptor.skillId for decision in result.decisions] == [automatic.skillId]
    assert result.activatedSkillIds == (automatic.skillId,)
