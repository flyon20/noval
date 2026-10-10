"""Per-family request dialects for the OpenAI-compatible transport.

One registry model may point at any of several provider families. They agree on
the OpenAI request skeleton but disagree on reasoning controls, output-cap field
names and temperature handling -- and each of them answers an unknown field with
HTTP 400 rather than ignoring it.

Every family therefore declares only what it is known to accept, and a family
with no known reasoning contract declares ``REASONING_OMIT`` so it degrades to a
plain request instead of risking a 400.

Family contracts and where they come from:

* ``openai`` -- OpenAI's effort enum, which changed between model generations.
  The gpt-5 and o-series models additionally drop ``temperature`` and rename the
  output cap to ``max_completion_tokens``. See ``OPENAI_EFFORT_VALUES``.
* ``deepseek`` -- its own ``thinking`` block on chat/completions plus an effort
  enum that includes ``max``; this is what the pre-existing client already sent
  and its tests pin.
* ``moonshot`` / ``zhipu`` / ``qwen`` -- treated as OpenAI-format **on the
  operator's instruction** that everything but Claude is OpenAI-compatible with
  an equivalent effort control. Not verified against vendor documentation here.
  If one of them answers 400 on ``reasoning_effort``, that family's row is the
  single place to correct.
* ``anthropic`` -- deliberately omits reasoning. Its native surface is
  ``/v1/messages`` with a different request shape, so no OpenAI-style control
  applies unless a gateway translates.
"""

from __future__ import annotations

from dataclasses import dataclass

# Output-cap field names.
OUTPUT_CAP_MAX_TOKENS = "max_tokens"
OUTPUT_CAP_MAX_COMPLETION_TOKENS = "max_completion_tokens"

# Reasoning control styles.
REASONING_OMIT = "omit"
REASONING_OPENAI_EFFORT = "openai_effort"
REASONING_KIMI_GLM_EFFORT = "kimi_glm_effort"
REASONING_QWEN_ENABLE_THINKING = "qwen_enable_thinking"
REASONING_DEEPSEEK_THINKING = "deepseek_thinking"

# OpenAI's effort enum for the gpt-5 / o-series generation, lowest to highest.
OPENAI_EFFORT_VALUES = ("minimal", "low", "medium", "high")

# gpt-5.6 and later reshaped the enum at both ends: "none" replaced "minimal" as
# the floor, and "xhigh"/"max" were added above "high". Verified 2026-09-02
# against a live gpt-5.6-sol endpoint, which rejected "minimal" on
# chat/completions with "Supported values are: 'none', 'low', 'medium', 'high',
# 'xhigh', and 'max'."
OPENAI_EXTENDED_EFFORT_VALUES = ("none", "low", "medium", "high", "xhigh", "max")

# Kimi / GLM effort enum (no minimal/medium, adds max).
KIMI_GLM_EFFORT_VALUES = ("low", "high", "max")


@dataclass(frozen=True, slots=True)
class ProviderDialect:
    """What one provider family is known to accept, on either wire."""

    family: str
    reasoning_style: str = REASONING_OMIT
    # Only OpenAI's own reasoning models renamed this field.
    output_cap_field: str = OUTPUT_CAP_MAX_TOKENS
    accepts_temperature: bool = True
    # gpt-5.6 and later accept the wider enum; older gpt-5 / o-series do not.
    extended_openai_effort: bool = False
    # Fast-mode effort on the Responses wire. OpenAI's enum bottoms out at
    # "minimal" ("none" from gpt-5.6 on); DeepSeek accepts "none". Meaningful
    # only when emits_reasoning.
    responses_fast_effort: str = "minimal"

    @property
    def emits_reasoning(self) -> bool:
        """Whether any reasoning control may be sent; gates both wires."""
        return self.reasoning_style != REASONING_OMIT


# OpenAI reasoning models: no temperature, and max_tokens was replaced by
# max_completion_tokens. Non-reasoning OpenAI models keep both.
_OPENAI_REASONING = ProviderDialect(
    family="openai",
    reasoning_style=REASONING_OPENAI_EFFORT,
    output_cap_field=OUTPUT_CAP_MAX_COMPLETION_TOKENS,
    accepts_temperature=False,
    responses_fast_effort="minimal",
)
# gpt-5.6 and later: same request shape, wider effort enum with "none" as floor.
_OPENAI_REASONING_EXTENDED = ProviderDialect(
    family="openai",
    reasoning_style=REASONING_OPENAI_EFFORT,
    output_cap_field=OUTPUT_CAP_MAX_COMPLETION_TOKENS,
    accepts_temperature=False,
    extended_openai_effort=True,
    responses_fast_effort="none",
)
# gpt-4o and friends are not reasoning models, so no effort control on either wire.
_OPENAI_CHAT = ProviderDialect(family="openai")

# DeepSeek's thinking block plus its own effort enum, which includes "max".
# DeepSeek 只在 deep 模式（thinking 开启）时不接受 temperature，fast 模式仍接受。
_DEEPSEEK = ProviderDialect(
    family="deepseek",
    reasoning_style=REASONING_DEEPSEEK_THINKING,
    responses_fast_effort="none",
)

# Kimi / GLM are OpenAI-compatible but use reasoning_effort with a different enum:
# "low" / "high" / "max" (no "minimal" or "medium").
_MOONSHOT = ProviderDialect(
    family="moonshot",
    reasoning_style=REASONING_KIMI_GLM_EFFORT,
    responses_fast_effort="low",
)
_ZHIPU = ProviderDialect(
    family="zhipu",
    reasoning_style=REASONING_KIMI_GLM_EFFORT,
    responses_fast_effort="low",
)

# Qwen uses enable_thinking (boolean) + thinking_budget (int), not reasoning_effort.
_QWEN = ProviderDialect(
    family="qwen",
    reasoning_style=REASONING_QWEN_ENABLE_THINKING,
    responses_fast_effort="low",
)

# Anthropic is the documented exception: its native surface is /v1/messages with
# a different request shape, so no OpenAI reasoning control is emitted.
_ANTHROPIC = ProviderDialect(family="anthropic")

# Unrecognized vendor behind an OpenAI-compatible wire. Nothing is known about
# its reasoning contract, so send the plain request.
_GENERIC = ProviderDialect(family="openai-compatible")

# providerType values that describe the wire format instead of a vendor. The
# registry default is one of these, so they must defer to the model name.
_WIRE_ONLY_PROVIDER_TYPES = frozenset({
    "openai-compatible",
    "openai-compatibles",
    "compatible",
    "custom",
    "generic",
    "unspecified",
})

# providerType (as stored on the registry model) -> family, matched on a
# normalized prefix so "azure-openai" and "openai" agree.
_PROVIDER_TYPE_FAMILIES: tuple[tuple[str, str], ...] = (
    ("deepseek", "deepseek"),
    ("anthropic", "anthropic"),
    ("claude", "anthropic"),
    ("moonshot", "moonshot"),
    ("kimi", "moonshot"),
    ("zhipu", "zhipu"),
    ("glm", "zhipu"),
    ("bigmodel", "zhipu"),
    ("qwen", "qwen"),
    ("dashscope", "qwen"),
    ("tongyi", "qwen"),
    ("azure-openai", "openai"),
    ("openai", "openai"),
)

# Model-name prefixes, used when providerType is generic (the registry default
# is "openai-compatible", which names a wire format rather than a vendor).
_MODEL_NAME_FAMILIES: tuple[tuple[str, str], ...] = (
    ("deepseek", "deepseek"),
    ("claude", "anthropic"),
    ("kimi", "moonshot"),
    ("moonshot", "moonshot"),
    ("glm", "zhipu"),
    ("qwen", "qwen"),
    ("qwq", "qwen"),
    ("gpt-", "openai"),
    ("o1", "openai"),
    ("o3", "openai"),
    ("o4", "openai"),
)

# OpenAI families whose models take the reasoning contract.
_OPENAI_REASONING_PREFIXES = ("gpt-5", "o1", "o3", "o4")

# Model-name prefixes verified to accept the wider gpt-5.6-era effort enum.
# Opting in explicitly is the safe direction: a newer model misread as legacy
# only loses the xhigh/max tiers, while the reverse sends values it answers 400
# on. Add a prefix here only after a real probe confirms the enum.
_OPENAI_EXTENDED_EFFORT_PREFIXES = ("gpt-5.6",)

_FAMILY_DIALECTS: dict[str, ProviderDialect] = {
    "deepseek": _DEEPSEEK,
    "anthropic": _ANTHROPIC,
    "moonshot": _MOONSHOT,
    "zhipu": _ZHIPU,
    "qwen": _QWEN,
    "openai-compatible": _GENERIC,
}


def _normalize(value: str | None) -> str:
    return str(value or "").strip().lower()


def resolve_family(provider_type: str | None, model: str | None) -> str:
    """Name the provider family, preferring the registry's declared providerType."""
    normalized_type = _normalize(provider_type).replace("_", "-")
    # These name the wire format rather than a vendor, so they must not be read
    # as OpenAI even though "openai-compatible" shares its prefix.
    if normalized_type not in _WIRE_ONLY_PROVIDER_TYPES:
        for prefix, family in _PROVIDER_TYPE_FAMILIES:
            if normalized_type.startswith(prefix):
                return family
    # Fall back to the model name before giving up on a specific family.
    normalized_model = _normalize(model)
    for prefix, family in _MODEL_NAME_FAMILIES:
        if normalized_model.startswith(prefix):
            return family
    return "openai-compatible"


def resolve_dialect(provider_type: str | None, model: str | None) -> ProviderDialect:
    """Pick the request dialect for one (providerType, model) pair."""
    family = resolve_family(provider_type, model)
    if family == "openai":
        normalized_model = _normalize(model)
        if normalized_model.startswith(_OPENAI_EXTENDED_EFFORT_PREFIXES):
            return _OPENAI_REASONING_EXTENDED
        return (
            _OPENAI_REASONING
            if normalized_model.startswith(_OPENAI_REASONING_PREFIXES)
            else _OPENAI_CHAT
        )
    return _FAMILY_DIALECTS.get(family, _GENERIC)


def openai_effort(
    reasoning_effort: str | None,
    *,
    extended: bool = False,
    default: str = "high",
) -> str:
    """Clamp a requested effort onto OpenAI's enum for this model generation.

    The two generations disagree at the floor, and the disagreement is not
    cosmetic: gpt-5.6 answers ``minimal`` with HTTP 400 on chat/completions
    ("Supported values are: 'none', 'low', 'medium', 'high', 'xhigh', and
    'max'.", verified 2026-09-02 against a live endpoint) even though the
    Responses wire still accepts it. So the canonical floor tier is translated
    to whichever value the target generation actually honors, and ``max`` /
    ``xhigh`` only survive on the generation that has them.
    """
    allowed = OPENAI_EXTENDED_EFFORT_VALUES if extended else OPENAI_EFFORT_VALUES
    value = _normalize(reasoning_effort)
    if value in allowed:
        return value
    if value in {"minimal", "none", "off"}:
        # Floor tier: "none" from gpt-5.6 on, "minimal" before it.
        return "none" if extended else "minimal"
    # "max"/"xhigh" exist only on the wider enum; older generations top out at high.
    if value in {"max", "xhigh"}:
        return "high"
    return default


def kimi_glm_effort(reasoning_effort: str | None, *, default: str = "high") -> str:
    """Clamp a requested effort onto Kimi/GLM's enum (no minimal/medium)."""
    value = _normalize(reasoning_effort)
    if value in KIMI_GLM_EFFORT_VALUES:
        return value
    # Map OpenAI's minimal/medium to nearest Kimi/GLM values
    if value == "minimal":
        return "low"
    if value == "medium":
        return "high"
    if value in {"xhigh"}:
        return "max"
    return default


# ---------------------------------------------------------------------------
# Canonical tiers
#
# The UI offers one scale for every model so the operator does not have to
# remember each vendor's enum. A tier is translated to the family's own value
# only here; a family that supports fewer tiers reports the subset it can honor
# and the UI renders just those, so no selectable tier is ever silently
# widened or narrowed on the way down.
# ---------------------------------------------------------------------------

# Canonical scale, lowest to highest. Matches the gpt-5 / o-series enum by
# construction -- it was the widest documented set of distinct effort levels
# when the scale was introduced.
CANONICAL_TIERS = ("minimal", "low", "medium", "high")

# gpt-5.6-era OpenAI models honor two more levels above "high", so they report a
# six-tier scale. The floor stays "minimal" for the UI and is translated to the
# provider's "none" in openai_effort; "xhigh"/"max" pass through unchanged.
OPENAI_EXTENDED_CANONICAL_TIERS = ("minimal", "low", "medium", "high", "xhigh", "max")

# Per-family canonical tiers, in ascending order. A family absent from this map
# either omits reasoning entirely (anthropic, non-reasoning openai, generic) or
# is boolean-only (qwen), and reports () / a single switch instead.
_FAMILY_CANONICAL_TIERS: dict[str, tuple[str, ...]] = {
    # DeepSeek's enum is the widest: none/low/medium/high/max. All four
    # canonical tiers land on a distinct value.
    "deepseek": CANONICAL_TIERS,
    # low/high/max only -- "minimal" and "medium" would collide onto low/high,
    # so they are not offered.
    "moonshot": ("low", "high", "max"),
    "zhipu": ("low", "high", "max"),
}


def canonical_tiers_for(dialect: ProviderDialect, model: str | None = None) -> tuple[str, ...]:
    """Tiers the UI may offer for this dialect, ascending; () when none apply.

    Qwen is the boolean case: it has no effort enum, so it reports the two
    endpoints and ``qwen_thinking_enabled`` decides the switch from them.
    """
    if not dialect.emits_reasoning:
        return ()
    if dialect.reasoning_style == REASONING_QWEN_ENABLE_THINKING:
        return ("minimal", "high")
    if dialect.reasoning_style == REASONING_OPENAI_EFFORT:
        return (
            OPENAI_EXTENDED_CANONICAL_TIERS
            if dialect.extended_openai_effort
            else CANONICAL_TIERS
        )
    return _FAMILY_CANONICAL_TIERS.get(dialect.family, CANONICAL_TIERS)


def qwen_thinking_enabled(reasoning_effort: str | None, *, default: bool = True) -> bool:
    """Qwen has no effort enum, so any tier above the floor turns thinking on."""
    value = _normalize(reasoning_effort)
    if value in {"", "auto"}:
        return default
    return value not in {"none", "off", "minimal"}
