import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { buildReviewDossier, REVIEW_CHECKS, reviewFeedback, validateReview } from "./fiction-review.mjs";

const fixture = JSON.parse(readFileSync(new URL("../../tests/fixtures/fiction_review_v1.json", import.meta.url), "utf8"));
const clean = () => ({ checks: Object.fromEntries(REVIEW_CHECKS.map(check => [check, "ok"])), findings: [] });
function problem(check = "world_rules") {
  const report = clean();
  report.checks[check] = "issue";
  report.findings.push({ check, kind: "conflict", draftQuote: "看守看见林澈脸色苍白",
    references: [{ entryId: "user.current", quote: "双目失明的看守" }],
    explanation: "人物直接凭视觉判断脸色，与给定失明条件冲突。", correction: "用听到呼吸变化表现察觉。" });
  return report;
}
const dossier = buildReviewDossier(fixture.cases[0]);

test("host dossier distinguishes corrections, proposals, summary and historical evidence", () => {
  const result = buildReviewDossier({ question: "改为三个人。", history: [
    { role: "user", content: "只能两个人。" }, { role: "assistant", content: "只能用一次能力。" }],
    contextSummary: "摘要称已经归还。", draft: "草稿", sources: [
      { preview: "尚未归还。", material: "尚未归还。", historicalReference: true, chapterVersion: 4 },
      { title: "仅有元数据不能当作正文" }], userId: 7 });
  assert.deepEqual(result.entries.map(entry => entry.kind), ["user_instruction", "prior_proposal", "user_instruction", "summary", "source_excerpt"]);
  assert.equal(result.entries[2].text, "改为三个人。");
  assert.equal(result.entries[4].text, "尚未归还。");
  assert.equal(result.entries[4].historical, true);
  assert.equal(result.entries[4].provenance.chapterVersion, 4);
  assert.equal(Object.hasOwn(result, "userId"), false);
});

test("grounded finding and complete no-issue report are valid", () => {
  assert.equal(validateReview(JSON.stringify(problem()), dossier).findingCount, 1);
  assert.equal(validateReview(JSON.stringify(clean()), dossier).findingCount, 0);
});

test("fabricated draft or source quotations never reach revision", () => {
  for (const mutate of [
    report => { report.findings[0].draftQuote = "草稿里不存在的视觉描写"; },
    report => { report.findings[0].references[0].quote = "看守拥有正常视力"; },
    report => { report.findings[0].references[0].entryId = "source.99"; },
  ]) {
    const report = problem(); mutate(report);
    const result = validateReview(JSON.stringify(report), dossier);
    assert.equal(result.status, "invalid");
    assert.ok(!reviewFeedback(result).includes(report.findings[0].explanation));
  }
});

test("old assistant rules and summaries cannot establish canon", () => {
  const input = buildReviewDossier({ question: "继续。", history: [{ role: "assistant", content: "每天只能回溯一次。" }],
    contextSummary: "每天只能回溯一次。", sources: [], draft: "他今天第二次回溯。" });
  const report = problem();
  report.findings[0].draftQuote = input.draft;
  report.findings[0].references = [{ entryId: "history.1", quote: "每天只能回溯一次。" }];
  assert.equal(validateReview(JSON.stringify(report), input).code, "proposal_as_canon");
  report.findings[0].references[0].entryId = "summary";
  assert.equal(validateReview(JSON.stringify(report), input).code, "summary_as_evidence");
});

test("a prior proposal can anchor the scene the user selected", () => {
  const input = buildReviewDossier(fixture.cases.find(item => item.id === "selected-scene-drift"));
  const report = problem("continuity");
  report.findings[0].draftQuote = "正午，店铺的窗帘被风吹起。";
  report.findings[0].references = [{ entryId: "history.1", quote: "第二场：深夜江序独自在钟楼齿轮箱前，刚发现一枚碎表壳。" }];
  assert.equal(validateReview(JSON.stringify(report), input).status, "succeeded");
});

test("missing requirement needs a user reference but no invented draft quotation", () => {
  const report = problem("requirements");
  report.findings[0].kind = "missing_requirement";
  report.findings[0].draftQuote = "";
  assert.equal(validateReview(JSON.stringify(report), dossier).status, "succeeded");
  report.findings[0].kind = "conflict";
  assert.equal(validateReview(JSON.stringify(report), dossier).status, "invalid");
});

test("coverage cannot silently omit a dimension or contradict a finding", () => {
  const incomplete = clean(); delete incomplete.checks.causality;
  assert.equal(validateReview(JSON.stringify(incomplete), dossier).status, "invalid");
  const unsupported = clean(); unsupported.checks.world_rules = "issue";
  assert.equal(validateReview(JSON.stringify(unsupported), dossier).code, "coverage_mismatch");
  const hidden = problem(); hidden.checks.world_rules = "ok";
  assert.equal(validateReview(JSON.stringify(hidden), dossier).code, "coverage_mismatch");
});

test("uncertain assessment is retained separately from a clean review", () => {
  const report = clean(); report.checks.evidence = "uncertain";
  const result = validateReview(JSON.stringify(report), dossier);
  assert.deepEqual(result.uncertainChecks, ["evidence"]);
  assert.match(reviewFeedback(result), /uncertain/u);
});

test("plain reassurance, malformed JSON and excessive output are incomplete reviews", () => {
  for (const raw of ["没有问题。", "{", "x".repeat(16001), JSON.stringify({ ...clean(), verdict: "perfect" })]) {
    assert.equal(validateReview(raw, dossier).status, "invalid");
  }
});

test("fixture retains balanced defect and valid cases without exposing labels to reviewer", () => {
  for (const pair of new Set(fixture.cases.map(item => item.pair))) {
    const cases = fixture.cases.filter(item => item.pair === pair);
    assert.equal(cases.length, 2);
    assert.equal(cases.filter(item => item.expectedChecks.length === 0).length, 1);
    for (const item of cases) {
      const input = buildReviewDossier(item);
      assert.equal(Object.hasOwn(input, "expectedChecks"), false);
      assert.equal(Object.hasOwn(input, "reason"), false);
      assert.equal(Object.hasOwn(input, "pair"), false);
    }
  }
});
