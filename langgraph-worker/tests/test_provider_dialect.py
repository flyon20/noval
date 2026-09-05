import unittest

from app.services.provider_dialect import (
    KIMI_GLM_EFFORT_VALUES,
    OPENAI_EFFORT_VALUES,
    OPENAI_EXTENDED_CANONICAL_TIERS,
    OPENAI_EXTENDED_EFFORT_VALUES,
    OUTPUT_CAP_MAX_COMPLETION_TOKENS,
    OUTPUT_CAP_MAX_TOKENS,
    REASONING_DEEPSEEK_THINKING,
    REASONING_KIMI_GLM_EFFORT,
    REASONING_OMIT,
    REASONING_OPENAI_EFFORT,
    REASONING_QWEN_ENABLE_THINKING,
    CANONICAL_TIERS,
    canonical_tiers_for,
    kimi_glm_effort,
    openai_effort,
    qwen_thinking_enabled,
    resolve_dialect,
    resolve_family,
)


class ProviderDialectTest(unittest.TestCase):

    def test_provider_type_decides_family_over_model_name(self) -> None:
        # A gateway may serve a vendor model under an unrelated alias, so the
        # registry's declared providerType has to win.
        self.assertEqual("deepseek", resolve_family("deepseek", "house-alias-v1"))
        self.assertEqual("anthropic", resolve_family("anthropic", "house-alias-v1"))
        self.assertEqual("moonshot", resolve_family("moonshot", "house-alias-v1"))
        self.assertEqual("zhipu", resolve_family("zhipu", "house-alias-v1"))
        self.assertEqual("qwen", resolve_family("dashscope", "house-alias-v1"))
        self.assertEqual("openai", resolve_family("azure-openai", "house-alias-v1"))

    def test_generic_provider_type_falls_back_to_model_name(self) -> None:
        # "openai-compatible" names the wire, not the vendor.
        for model, expected in (
            ("deepseek-v4-pro", "deepseek"),
            ("claude-opus-5", "anthropic"),
            ("kimi-k2", "moonshot"),
            ("glm-5", "zhipu"),
            ("qwen3-max", "qwen"),
            ("gpt-5.6-sol", "openai"),
            ("o3-mini", "openai"),
        ):
            with self.subTest(model=model):
                self.assertEqual(expected, resolve_family("openai-compatible", model))

    def test_wire_only_provider_types_never_read_as_openai(self) -> None:
        # "openai-compatible" shares OpenAI's prefix but is the registry default
        # for every vendor, so reading it as OpenAI would mis-dialect them all.
        for provider_type in ("openai-compatible", "custom", "generic", "unspecified", ""):
            with self.subTest(provider_type=provider_type):
                self.assertEqual("deepseek", resolve_family(provider_type, "deepseek-v4-pro"))
                self.assertEqual("zhipu", resolve_family(provider_type, "glm-5"))

    def test_unknown_pair_stays_generic(self) -> None:
        self.assertEqual("openai-compatible", resolve_family(None, "house-alias-v1"))
        self.assertEqual("openai-compatible", resolve_family("", ""))

    def test_openai_reasoning_models_drop_temperature_and_rename_output_cap(self) -> None:
        dialect = resolve_dialect("openai", "gpt-5.6-sol")

        self.assertEqual(REASONING_OPENAI_EFFORT, dialect.reasoning_style)
        self.assertEqual(OUTPUT_CAP_MAX_COMPLETION_TOKENS, dialect.output_cap_field)
        self.assertFalse(dialect.accepts_temperature)

    def test_openai_chat_models_keep_temperature_and_max_tokens(self) -> None:
        dialect = resolve_dialect("openai", "gpt-4o")

        self.assertEqual(REASONING_OMIT, dialect.reasoning_style)
        self.assertEqual(OUTPUT_CAP_MAX_TOKENS, dialect.output_cap_field)
        self.assertTrue(dialect.accepts_temperature)

    def test_deepseek_keeps_its_thinking_contract(self) -> None:
        dialect = resolve_dialect("deepseek", "deepseek-v4-pro")

        self.assertEqual(REASONING_DEEPSEEK_THINKING, dialect.reasoning_style)
        self.assertEqual(OUTPUT_CAP_MAX_TOKENS, dialect.output_cap_field)
        # DeepSeek 只在 deep 模式不接受 temperature，fast 模式仍接受
        self.assertTrue(dialect.accepts_temperature)

    def test_openai_compatible_vendors_use_their_own_effort_controls(self) -> None:
        # Kimi / GLM 用 reasoning_effort 但枚举是 low|high|max (不是 OpenAI 的 minimal|low|medium|high)
        for provider_type, model in (
            ("moonshot", "kimi-k3"),
            ("zhipu", "glm-5.3"),
        ):
            with self.subTest(provider_type=provider_type):
                dialect = resolve_dialect(provider_type, model)
                self.assertEqual(REASONING_KIMI_GLM_EFFORT, dialect.reasoning_style)
                self.assertEqual(OUTPUT_CAP_MAX_TOKENS, dialect.output_cap_field)
                self.assertTrue(dialect.accepts_temperature)
                self.assertEqual("low", dialect.responses_fast_effort)

        # Qwen 用 enable_thinking 布尔参数，不是 reasoning_effort
        dialect = resolve_dialect("qwen", "qwen3-max")
        self.assertEqual(REASONING_QWEN_ENABLE_THINKING, dialect.reasoning_style)
        self.assertEqual(OUTPUT_CAP_MAX_TOKENS, dialect.output_cap_field)
        self.assertTrue(dialect.accepts_temperature)

    def test_anthropic_emits_no_openai_reasoning_control(self) -> None:
        # Anthropic's native surface is /v1/messages, so no OpenAI-style control.
        dialect = resolve_dialect("anthropic", "claude-opus-5")

        self.assertFalse(dialect.emits_reasoning)
        self.assertEqual(OUTPUT_CAP_MAX_TOKENS, dialect.output_cap_field)

    def test_unknown_vendor_emits_no_reasoning_parameters(self) -> None:
        dialect = resolve_dialect("openai-compatible", "house-alias-v1")

        self.assertFalse(dialect.emits_reasoning)

    def test_non_reasoning_openai_models_emit_no_effort_control(self) -> None:
        # gpt-4o is not a reasoning model; an effort block would be rejected.
        dialect = resolve_dialect("openai", "gpt-4o")

        self.assertFalse(dialect.emits_reasoning)

    def test_openai_effort_clamps_values_outside_the_enum(self) -> None:
        for requested, expected in (
            ("minimal", "minimal"),
            ("low", "low"),
            ("medium", "medium"),
            ("high", "high"),
            ("max", "high"),
            ("xhigh", "high"),
            (None, "high"),
            ("nonsense", "high"),
        ):
            with self.subTest(requested=requested):
                self.assertEqual(expected, openai_effort(requested))

    def test_openai_extended_effort_keeps_the_wider_enum(self) -> None:
        # gpt-5.6 起枚举变成 none|low|medium|high|xhigh|max：minimal 会被 400 拒掉，
        # 而 max/xhigh 是真档位，不能再被压到 high。
        for requested, expected in (
            ("minimal", "none"),
            ("none", "none"),
            ("off", "none"),
            ("low", "low"),
            ("medium", "medium"),
            ("high", "high"),
            ("xhigh", "xhigh"),
            ("max", "max"),
            (None, "high"),
            ("nonsense", "high"),
        ):
            with self.subTest(requested=requested):
                self.assertEqual(expected, openai_effort(requested, extended=True))

    def test_gpt_56_uses_the_extended_dialect_and_older_gpt5_does_not(self) -> None:
        # 两代共用请求外形，只有 effort 枚举不同，所以必须按模型名分开。
        for model in ("gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6"):
            with self.subTest(model=model):
                dialect = resolve_dialect("openai-compatible", model)
                self.assertTrue(dialect.extended_openai_effort)
                self.assertEqual("none", dialect.responses_fast_effort)
        for model in ("gpt-5", "gpt-5-mini", "o3-mini"):
            with self.subTest(model=model):
                dialect = resolve_dialect("openai-compatible", model)
                self.assertFalse(dialect.extended_openai_effort)
                self.assertEqual("minimal", dialect.responses_fast_effort)

    def test_kimi_glm_effort_clamps_and_maps_values(self) -> None:
        # Kimi/GLM 枚举是 low|high|max，需要映射 OpenAI 的 minimal/medium
        for requested, expected in (
            ("low", "low"),
            ("high", "high"),
            ("max", "max"),
            ("minimal", "low"),  # OpenAI minimal -> Kimi/GLM low
            ("medium", "high"),  # OpenAI medium -> Kimi/GLM high
            ("xhigh", "max"),
            (None, "high"),
            ("nonsense", "high"),
        ):
            with self.subTest(requested=requested):
                self.assertEqual(expected, kimi_glm_effort(requested))

    def test_canonical_tiers_reported_per_family(self) -> None:
        # 前端按这个列表渲染档位，所以每族只能报它真能区分开的档
        for provider_type, model, expected in (
            # DeepSeek 枚举最宽，四档都能落到不同值
            ("deepseek", "deepseek-chat", CANONICAL_TIERS),
            # OpenAI 推理模型就是规范标度本身
            ("openai", "gpt-5", CANONICAL_TIERS),
            # gpt-5.6 多出 xhigh/max 两档，要报六档
            ("openai", "gpt-5.6-sol", OPENAI_EXTENDED_CANONICAL_TIERS),
            # Kimi/GLM 没有 minimal/medium，只报三档
            ("moonshot", "kimi-k2", ("low", "high", "max")),
            ("zhipu", "glm-4.6", ("low", "high", "max")),
            # Qwen 只有布尔开关，用两个端点表示
            ("qwen", "qwen3-max", ("minimal", "high")),
            # 不发推理参数的族一律报空，控件隐藏
            ("anthropic", "claude-sonnet-4", ()),
            ("openai", "gpt-4o", ()),
            ("openai-compatible", "some-unknown-model", ()),
        ):
            with self.subTest(provider_type=provider_type, model=model):
                dialect = resolve_dialect(provider_type, model)
                self.assertEqual(expected, canonical_tiers_for(dialect, model))

    def test_reported_tiers_survive_their_family_clamp(self) -> None:
        # 报出来的档位必须落进该族真实枚举，且互不重合——否则 UI 上选了不同档
        # 却发出同一个值，用户看得见控件但改不动行为。
        for provider_type, model, clamp, allowed in (
            ("openai", "gpt-5", openai_effort, OPENAI_EFFORT_VALUES),
            (
                "openai",
                "gpt-5.6-sol",
                lambda tier: openai_effort(tier, extended=True),
                OPENAI_EXTENDED_EFFORT_VALUES,
            ),
            ("moonshot", "kimi-k2", kimi_glm_effort, KIMI_GLM_EFFORT_VALUES),
            ("zhipu", "glm-4.6", kimi_glm_effort, KIMI_GLM_EFFORT_VALUES),
        ):
            dialect = resolve_dialect(provider_type, model)
            tiers = canonical_tiers_for(dialect, model)
            self.assertTrue(tiers, f"{model} reported no tiers")
            sent = []
            for tier in tiers:
                with self.subTest(provider_type=provider_type, tier=tier):
                    value = clamp(tier)
                    self.assertIn(value, allowed)
                    sent.append(value)
            with self.subTest(model=model):
                self.assertEqual(len(set(sent)), len(sent), f"{model} collapses tiers: {sent}")

    def test_fast_mode_floor_is_accepted_by_its_generation(self) -> None:
        # 快速档的落地值也要在枚举内：gpt-5.6 用 minimal 会被 400 拒掉。
        for model, allowed in (
            ("gpt-5", OPENAI_EFFORT_VALUES),
            ("gpt-5.6-sol", OPENAI_EXTENDED_EFFORT_VALUES),
        ):
            with self.subTest(model=model):
                dialect = resolve_dialect("openai-compatible", model)
                self.assertIn(dialect.responses_fast_effort, allowed)
                self.assertIn(
                    openai_effort("minimal", extended=dialect.extended_openai_effort),
                    allowed,
                )

    def test_qwen_thinking_switch_from_tier(self) -> None:
        for requested, expected in (
            ("minimal", False),
            ("none", False),
            ("off", False),
            ("low", True),
            ("medium", True),
            ("high", True),
            ("max", True),
            (None, True),  # 没指定时保持默认开启
            ("auto", True),
        ):
            with self.subTest(requested=requested):
                self.assertEqual(expected, qwen_thinking_enabled(requested))


if __name__ == "__main__":
    unittest.main()
