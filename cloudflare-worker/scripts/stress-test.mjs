import { mkdir, writeFile } from "node:fs/promises";
import { resolve } from "node:path";

const endpoint = process.env.QUESTION_WORKER_URL ||
  "https://ripenai-question-worker.dbgaming679.workers.dev/v1/questions";
const outputDir = resolve(process.cwd(), "..", "outputs", "worker_analysis");
const fruits = ["apple", "banana", "mango", "orange", "papaya", "pineapple", "tomato", "avocado", "durian"];
const stages = ["unripe", "ripe", "overripe", "rotten"];
const forbidden = /\b(cut|potong|cicip|mencicipi|taste|tusuk|menusuk|rusak|merusak)\b/i;
const evidenceCodes = new Set(["UNRIPE", "RIPE", "OVERRIPE", "UNSAFE", "NEUTRAL"]);
const safetyCue = /jamur|bulu|lendir|licin|bocor|bau\s+busuk|bau\s+menyengat|tidak\s+layak|buang/i;

const validCases = fruits.flatMap((fruit) => stages.map((stage) => ({
  name: `${fruit}-${stage}`,
  expectedStatus: 200,
  body: {
    fruit_type: fruit,
    cv_stage: stage,
    cv_confidence: 0.72,
    top2_stage: stage === "ripe" ? "overripe" : "ripe",
    top2_confidence: 0.18,
    language: "id-ID",
  },
})));

const invalidCases = [
  { name: "missing-fruit", expectedStatus: 400, rawBody: JSON.stringify({ cv_stage: "ripe" }) },
  { name: "invalid-fruit", expectedStatus: 400, body: { fruit_type: "apple<script>" } },
  { name: "too-large", expectedStatus: 413, rawBody: JSON.stringify({ fruit_type: "apple", padding: "x".repeat(9_000) }) },
];

const validateQuestionPayload = (payload, expectedFruit) => {
  const questions = Array.isArray(payload?.questions) ? payload.questions : [];
  const errors = [];
  if (payload?.fruit_type !== expectedFruit) errors.push("fruit_type_mismatch");
  if (questions.length !== 3) errors.push("question_count");
  const ids = new Set();
  for (const question of questions) {
    if (!question || typeof question.id !== "string" || !question.id.trim()) errors.push("missing_id");
    if (ids.has(question.id)) errors.push("duplicate_id");
    ids.add(question.id);
    if (typeof question.text !== "string" || !question.text.trim() || question.text.length > 160) errors.push("invalid_text");
    if (forbidden.test(question.text || "")) errors.push("unsafe_text");
    if (!Array.isArray(question.options) || question.options.length < 2 || question.options.length > 4) errors.push("invalid_options");
    const options = Array.isArray(question.options) ? question.options : [];
    if (new Set(options.map((option) => String(option).toLowerCase())).size !== options.length) errors.push("duplicate_options");
    if (options.some((option) => !String(option).trim() || String(option).length > 80 || forbidden.test(String(option)))) errors.push("unsafe_option");
    if (!Array.isArray(question.option_evidence) || question.option_evidence.length !== options.length) errors.push("invalid_option_evidence");
    if (Array.isArray(question.option_evidence) && question.option_evidence.some((evidence) => !evidenceCodes.has(evidence))) errors.push("unknown_option_evidence");
  }
  const hasUnsafeOption = questions.some((question) =>
    Array.isArray(question.option_evidence) && question.option_evidence.some((evidence, index) =>
      evidence === "UNSAFE" && safetyCue.test(String(question.options?.[index] ?? "")),
    ),
  );
  const ripenessQuestionCount = questions.filter((question) =>
    Array.isArray(question.option_evidence) && question.option_evidence.some((evidence) => ["UNRIPE", "RIPE", "OVERRIPE"].includes(evidence)),
  ).length;
  if (!hasUnsafeOption) errors.push("missing_grounded_unsafe_option");
  if (ripenessQuestionCount < 2) errors.push("insufficient_ripeness_evidence");
  return [...new Set(errors)];
};

const runCase = async (testCase) => {
  const started = performance.now();
  try {
    const response = await fetch(endpoint, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: testCase.rawBody ?? JSON.stringify(testCase.body),
    });
    const raw = await response.text();
    let payload = null;
    try { payload = JSON.parse(raw); } catch { /* recorded below */ }
    const fruit = testCase.body?.fruit_type;
    const contractErrors = response.status === 200 && fruit
      ? validateQuestionPayload(payload, fruit)
      : [];
    return {
      name: testCase.name,
      expected_status: testCase.expectedStatus,
      status: response.status,
      ok: response.status === testCase.expectedStatus && contractErrors.length === 0,
      latency_ms: Math.round(performance.now() - started),
      provider: payload?.provider ?? null,
      request_id: payload?.request_id ?? null,
      questions: response.status === 200 ? payload?.questions ?? null : null,
      contract_errors: contractErrors,
      error: payload?.error ?? null,
      providers: payload?.providers ?? null,
      response_bytes: raw.length,
    };
  } catch (error) {
    return {
      name: testCase.name,
      expected_status: testCase.expectedStatus,
      status: null,
      ok: false,
      latency_ms: Math.round(performance.now() - started),
      provider: null,
      request_id: null,
      questions: null,
      contract_errors: [],
      error: error instanceof Error ? error.message : String(error),
      providers: null,
      response_bytes: 0,
    };
  }
};

const runPool = async (cases, concurrency = 4) => {
  const results = [];
  let cursor = 0;
  const worker = async () => {
    while (cursor < cases.length) {
      const index = cursor++;
      results[index] = await runCase(cases[index]);
    }
  };
  await Promise.all(Array.from({ length: Math.min(concurrency, cases.length) }, worker));
  return results;
};

const main = async () => {
  const started = new Date().toISOString();
  const validResults = await runPool(validCases, 4);
  const invalidResults = await runPool(invalidCases, 2);
  const optionsResponse = await fetch(endpoint.replace(/\/v1\/questions$/, ""), { method: "OPTIONS" });
  const results = [
    ...validResults.map((result) => ({ ...result, group: "valid" })),
    ...invalidResults.map((result) => ({ ...result, group: "invalid" })),
    {
      name: "cors-options",
      group: "protocol",
      expected_status: 204,
      status: optionsResponse.status,
      ok: optionsResponse.status === 204,
      latency_ms: null,
      provider: null,
      request_id: null,
      questions: null,
      contract_errors: [],
      error: null,
      providers: null,
      response_bytes: 0,
    },
  ];

  const validLatency = validResults.map((result) => result.latency_ms).sort((a, b) => a - b);
  const percentile = (values, fraction) => values.length ? values[Math.min(values.length - 1, Math.floor(values.length * fraction))] : null;
  const summary = {
    endpoint,
    started_at: started,
    finished_at: new Date().toISOString(),
    total_cases: results.length,
    valid_cases: validResults.length,
    valid_successes: validResults.filter((result) => result.ok).length,
    valid_contract_failures: validResults.filter((result) => result.contract_errors.length > 0).length,
    invalid_protocol_successes: invalidResults.filter((result) => result.ok).length,
    all_successes: results.filter((result) => result.ok).length,
    providers: Object.fromEntries(Object.entries(validResults.reduce((counts, result) => {
      const provider = result.provider || `error:${result.error || result.status}`;
      counts[provider] = (counts[provider] || 0) + 1;
      return counts;
    }, {}))),
    latency_ms: {
      min: validLatency[0] ?? null,
      p50: percentile(validLatency, 0.5),
      p95: percentile(validLatency, 0.95),
      max: validLatency.at(-1) ?? null,
      mean: validLatency.length ? Math.round(validLatency.reduce((sum, value) => sum + value, 0) / validLatency.length) : null,
    },
    failures: results.filter((result) => !result.ok),
  };

  await mkdir(outputDir, { recursive: true });
  await writeFile(resolve(outputDir, "worker_stress_results.json"), JSON.stringify({ summary, results }, null, 2));
  await writeFile(resolve(outputDir, "worker_stress_summary.json"), JSON.stringify(summary, null, 2));
  await writeFile(resolve(outputDir, "worker_stress_results.ndjson"), results.map((result) => JSON.stringify(result)).join("\n") + "\n");
  console.log(JSON.stringify(summary, null, 2));
};

await main();
