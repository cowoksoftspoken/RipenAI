interface Env {
  AI: Ai;
  GROQ_API_KEY?: string;
  GROQ_MODEL?: string;
  CF_FALLBACK_MODEL?: string;
}

interface QuestionRequest {
  fruit_type: string;
  cv_stage?: string;
  cv_confidence?: number;
  top2_stage?: string | null;
  top2_confidence?: number;
  language?: string;
}

const answerEvidenceValues = ["UNRIPE", "RIPE", "OVERRIPE", "UNSAFE", "NEUTRAL"] as const;
type AnswerEvidence = (typeof answerEvidenceValues)[number];

interface QuestionPayload {
  fruit_type: string;
  questions: Array<{ id: string; text: string; options: string[]; option_evidence: AnswerEvidence[] }>;
}

const questionResponseFormat = {
  type: "json_schema",
  json_schema: {
    type: "object",
    additionalProperties: false,
    properties: {
      fruit_type: { type: "string" },
      questions: {
        type: "array",
        minItems: 3,
        maxItems: 3,
        items: {
          type: "object",
          additionalProperties: false,
          properties: {
            id: { type: "string" },
            text: { type: "string" },
            options: {
              type: "array",
              minItems: 2,
              maxItems: 4,
              items: { type: "string" },
            },
            option_evidence: {
              type: "array",
              minItems: 2,
              maxItems: 4,
              items: { type: "string", enum: answerEvidenceValues },
            },
          },
          required: ["id", "text", "options", "option_evidence"],
        },
      },
    },
    required: ["fruit_type", "questions"],
  },
} as const;

const fruitGuidance: Record<string, { label: string; cues: string; required: RegExp }> = {
  apple: { label: "apel", cues: "warna kulit, kekerasan, tangkai, memar, atau bercak", required: /tangkai|bercak|memar/i },
  banana: { label: "pisang", cues: "warna kulit, bercak coklat, ujung, tangkai, atau aroma", required: /bercak|tangkai|ujung/i },
  mango: { label: "mangga", cues: "warna kulit, kelenturan, aroma di tangkai, atau bercak", required: /tangkai|bercak|aroma/i },
  orange: { label: "jeruk", cues: "warna kulit, pori kulit, berat, kekencangan, atau aroma", required: /pori|berat|aroma/i },
  papaya: { label: "pepaya", cues: "warna kulit, kelenturan, tangkai, getah, atau bercak", required: /tangkai|getah|bercak/i },
  pineapple: { label: "nanas", cues: "warna kulit, mata nanas, daun mahkota, aroma, atau kelenturan", required: /mata|daun|mahkota/i },
  tomato: { label: "tomat", cues: "warna kulit, kelenturan, tangkai, retak, atau bercak", required: /tangkai|retak|bercak/i },
  avocado: { label: "alpukat", cues: "warna kulit, tangkai, kelenturan saat ditekan ringan, atau berat", required: /tangkai|tekan|lentur|berat/i },
  durian: { label: "durian", cues: "duri, tangkai, aroma, atau bunyi saat diketuk ringan", required: /duri|tangkai|bunyi/i },
};

const jsonHeaders = {
  "content-type": "application/json; charset=utf-8",
  "access-control-allow-origin": "*",
  "access-control-allow-methods": "POST, OPTIONS",
  "access-control-allow-headers": "content-type",
  "access-control-max-age": "86400",
};

const response = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: jsonHeaders });

const noContent = () => new Response(null, { status: 204, headers: jsonHeaders });

const asPercent = (value: number | undefined) => {
  const normalised = Number.isFinite(value) ? Number(value) : 0;
  return Math.round(
    normalised >= 0 && normalised <= 1 ? normalised * 100 : normalised,
  );
};

const isSpoilageCandidate = (stage: string | null | undefined) =>
  ["rotten", "busuk", "spoiled"].includes(stage?.trim().toLowerCase() ?? "");

const promptFor = (input: QuestionRequest) =>
  `
Kamu adalah pendamping pemilihan buah RipenAI. Buat pertanyaan konfirmasi dalam Bahasa Indonesia untuk memastikan tahap kematangan buah.
Buah: ${input.fruit_type}
Nama buah yang harus dipakai dalam pertanyaan: ${fruitGuidance[input.fruit_type]?.label ?? input.fruit_type}.
Prediksi visual awal: ${input.cv_stage ?? "unknown"} (${asPercent(input.cv_confidence)}%).
Kandidat kedua: ${input.top2_stage ?? "none"} (${asPercent(input.top2_confidence)}%).

Panduan tanda khusus buah: ${fruitGuidance[input.fruit_type]?.cues ?? "warna, tekstur luar, aroma, tangkai, atau bercak"}.
Minimal satu dari tiga pertanyaan wajib memakai tanda khusus tersebut. Jangan memakai kata "buah ini" jika nama buah dapat disebutkan.

Aturan:
- keluarkan tepat 3 pertanyaan tertutup yang mudah dijawab dari pengamatan langsung;
- setiap pertanyaan memiliki 2 sampai 4 opsi jawaban;
- setiap opsi WAJIB memiliki satu kode pada option_evidence dengan jumlah dan urutan yang sama seperti options. Kode yang sah hanya UNRIPE, RIPE, OVERRIPE, NEUTRAL, dan UNSAFE;
- kode evidence harus menjelaskan arti opsi itu sendiri, BUKAN posisi opsinya. Urutan options boleh bebas;
- gunakan NEUTRAL untuk jawaban yang tidak membawa bukti kematangan (contoh: "Tidak ada jamur");
- gunakan UNSAFE hanya untuk tanda tidak aman yang nyata: jamur/bulu, lendir atau licin, kebocoran, bau busuk/menyengat, atau tidak layak konsumsi;
- gunakan tanda yang aman: warna, tekstur luar, aroma, tangkai, atau bercak;
- satu pertanyaan WAJIB selalu memeriksa keamanan permukaan. Pertanyaan tersebut harus memiliki setidaknya satu opsi UNSAFE yang menyebut jamur/bulu, lendir/licin, kebocoran, atau bau busuk/menyengat secara eksplisit;
- sedikitnya dua pertanyaan harus memiliki opsi kematangan (UNRIPE, RIPE, atau OVERRIPE) agar Android dapat menggabungkan observasi;
- ${isSpoilageCandidate(input.cv_stage) || isSpoilageCandidate(input.top2_stage) ? "prediksi visual memiliki kandidat busuk, jadi gunakan pertanyaan keamanan yang sangat eksplisit." : "tetap tanyakan keamanan walaupun prediksi visual tidak mencurigakan."}
- jangan meminta pengguna memotong, mencicipi, menusuk, atau merusak buah;
- jangan memberi hasil akhir atau probabilitas, hanya pertanyaan;
- jawab JSON valid saja dengan format {"fruit_type":"...","questions":[{"id":"...","text":"...","options":["..."],"option_evidence":["..."]}]}.
`.trim();

const extractJsonObject = (text: string): string | null => {
  const start = text.indexOf("{");
  if (start < 0) return null;

  let depth = 0;
  let inString = false;
  let escaped = false;
  for (let index = start; index < text.length; index += 1) {
    const character = text[index];
    if (inString) {
      if (escaped) escaped = false;
      else if (character === "\\") escaped = true;
      else if (character === '"') inString = false;
      continue;
    }
    if (character === '"') {
      inString = true;
      continue;
    }
    if (character === "{") depth += 1;
    if (character === "}") {
      depth -= 1;
      if (depth === 0) return text.slice(start, index + 1);
    }
  }
  return null;
};

const safetyCue = /jamur|bulu|lendir|licin|bocor|bau\s+busuk|bau\s+menyengat|tidak\s+layak|buang/i;
const genericFruitReference = /\b(buah ini|buah tersebut|buahnya)\b/i;

const parseJson = (text: string, expectedFruit: string): QuestionPayload | null => {
  const clean = text
    .trim()
    .replace(/^```(?:json)?\s*/i, "")
    .replace(/\s*```$/, "");
  try {
    const value = JSON.parse(extractJsonObject(clean) ?? clean) as Partial<QuestionPayload>;
    if (
      typeof value.fruit_type !== "string" ||
      !Array.isArray(value.questions) ||
      value.questions.length !== 3
    )
      return null;
    const questions = value.questions.map((item) => ({
      id: typeof item?.id === "string" ? item.id.trim() : "",
      text: typeof item?.text === "string" ? item.text.trim() : "",
      options: Array.isArray(item?.options)
        ? item.options.map((option) => String(option).trim())
        : [],
      option_evidence: Array.isArray(item?.option_evidence)
        ? item.option_evidence.map((evidence) => String(evidence).trim().toUpperCase() as AnswerEvidence)
        : [],
    }));
    const forbidden = /\b(cut|potong|cicip|mencicipi|taste|tusuk|menusuk|rusak|merusak)\b/i;
    if (
      questions.some(
        (item) =>
          !item.id ||
          !item.text ||
          item.text.length > 160 ||
          item.options.length < 2 ||
          item.options.length > 4 ||
          item.option_evidence.length !== item.options.length ||
          item.option_evidence.some((evidence) => !answerEvidenceValues.includes(evidence)) ||
          new Set(item.options.map((option) => option.toLowerCase())).size !== item.options.length ||
          genericFruitReference.test([item.text, ...item.options].join(" ")) ||
          forbidden.test(item.text) ||
          item.options.some((option) => !option || option.length > 80 || forbidden.test(option)),
      )
    )
      return null;
    const requiredCue = fruitGuidance[expectedFruit]?.required;
    const allQuestionText = questions
      .flatMap((item) => [item.text, ...item.options])
      .join(" ");
    if (requiredCue && !requiredCue.test(allQuestionText)) return null;
    const hasGroundedUnsafeOption = questions.some((question) =>
      question.option_evidence.some((evidence, index) =>
        evidence === "UNSAFE" && safetyCue.test(question.options[index] ?? ""),
      ),
    );
    const ripenessQuestionCount = questions.filter((question) =>
      question.option_evidence.some((evidence) => evidence === "UNRIPE" || evidence === "RIPE" || evidence === "OVERRIPE"),
    ).length;
    if (!hasGroundedUnsafeOption || ripenessQuestionCount < 2) return null;
    return { fruit_type: expectedFruit, questions };
  } catch {
    return null;
  }
};

const ruleBasedFallback = (input: QuestionRequest): QuestionPayload => {
  const fruit = fruitGuidance[input.fruit_type]?.label ?? input.fruit_type;
  const cues = fruitGuidance[input.fruit_type]?.cues ?? "warna kulit atau bercak";
  return {
    fruit_type: input.fruit_type.trim(),
    questions: [
      {
        id: "ripeness_cue",
        text: `Bagaimana ${fruit} dilihat dari ${cues}?`,
        options: ["Masih pucat atau hijau", "Sudah menunjukkan warna matang", "Sangat gelap atau banyak bercak"],
        option_evidence: ["UNRIPE", "RIPE", "OVERRIPE"],
      },
      {
        id: "texture",
        text: `Bagaimana tekstur luar ${fruit} saat ditekan sangat ringan?`,
        options: ["Keras", "Sedikit memberi", "Sangat lembek"],
        option_evidence: ["UNRIPE", "RIPE", "OVERRIPE"],
      },
      {
        id: "safety_surface",
        text: `Bagaimana keamanan permukaan ${fruit} saat diamati?`,
        options: [
          "Bersih, kering, dan utuh",
          "Ada bagian lembek atau bercak, tetapi tidak licin dan tidak berbau busuk",
          "Ada jamur atau bulu, lendir atau licin, kebocoran, atau bau busuk",
        ],
        option_evidence: ["NEUTRAL", "OVERRIPE", "UNSAFE"],
      },
    ],
  };
};

type ProviderResult = {
  payload: QuestionPayload | null;
  failure: string | null;
  latencyMs: number;
};

const withDeadline = async <T>(work: Promise<T>, timeoutMs: number): Promise<T> => {
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    return await Promise.race([
      work,
      new Promise<T>((_, reject) => {
        timer = setTimeout(() => reject(new Error("provider_timeout")), timeoutMs);
      }),
    ]);
  } finally {
    if (timer !== undefined) clearTimeout(timer);
  }
};

const modelOutputToText = (output: unknown): string | null => {
  if (typeof output === "string") return output;
  if (!output || typeof output !== "object") return null;
  const responseValue = (output as { response?: unknown }).response;
  if (typeof responseValue === "string") return responseValue;
  if (responseValue && typeof responseValue === "object") return JSON.stringify(responseValue);
  return JSON.stringify(output);
};

const callGroq = async (
  env: Env,
  prompt: string,
  fruitType: string,
): Promise<ProviderResult> => {
  const started = Date.now();
  if (!env.GROQ_API_KEY) {
    return { payload: null, failure: "not_configured", latencyMs: Date.now() - started };
  }
  try {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort("provider_timeout"), 12_000);
    const result = await fetch("https://api.groq.com/openai/v1/chat/completions", {
      method: "POST",
      headers: {
        authorization: `Bearer ${env.GROQ_API_KEY}`,
        "content-type": "application/json",
      },
      body: JSON.stringify({
        model: env.GROQ_MODEL || "openai/gpt-oss-20b",
        temperature: 0,
        max_tokens: 900,
        response_format: { type: "json_object" },
        messages: [
          { role: "system", content: "Jawab hanya JSON yang sesuai format. Jangan sertakan reasoning atau markdown." },
          { role: "user", content: prompt },
        ],
      }),
      signal: controller.signal,
    });
    clearTimeout(timer);
    if (!result.ok) {
      console.warn(JSON.stringify({ event: "provider_failure", provider: "groq", status: result.status }));
      return { payload: null, failure: `http_${result.status}`, latencyMs: Date.now() - started };
    }
    const body = (await result.json()) as {
      choices?: Array<{ message?: { content?: string } }>;
    };
    const content = body.choices?.[0]?.message?.content;
    const payload = content ? parseJson(content, fruitType) : null;
    if (!payload) {
      console.warn(JSON.stringify({ event: "provider_parse_failure", provider: "groq" }));
      return { payload: null, failure: "invalid_model_json", latencyMs: Date.now() - started };
    }
    return { payload, failure: null, latencyMs: Date.now() - started };
  } catch (error) {
    const failure = error instanceof Error ? error.message : "exception";
    console.error(JSON.stringify({ event: "provider_exception", provider: "groq", failure }));
    return { payload: null, failure, latencyMs: Date.now() - started };
  }
};

const callCloudflare = async (
  env: Env,
  prompt: string,
  fruitType: string,
): Promise<ProviderResult> => {
  const started = Date.now();
  try {
    const result = await withDeadline(
      env.AI.run(
        env.CF_FALLBACK_MODEL || "@cf/meta/llama-3.1-8b-instruct-fast",
        {
          prompt,
          temperature: 0,
          max_tokens: 900,
          response_format: questionResponseFormat,
        },
      ),
      15_000,
    );
    const text = modelOutputToText(result);
    const payload = text ? parseJson(text, fruitType) : null;
    if (!payload) {
      console.warn(JSON.stringify({ event: "provider_parse_failure", provider: "cloudflare" }));
      return { payload: null, failure: "invalid_model_json", latencyMs: Date.now() - started };
    }
    return { payload, failure: null, latencyMs: Date.now() - started };
  } catch (error) {
    const failure = error instanceof Error ? error.message : "exception";
    console.error(JSON.stringify({ event: "provider_exception", provider: "cloudflare", failure }));
    return { payload: null, failure, latencyMs: Date.now() - started };
  }
};

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (request.method === "OPTIONS") return noContent();
    const url = new URL(request.url);
    if (url.pathname !== "/v1/questions" || request.method !== "POST")
      return response({ error: "not_found" }, 404);

    let input: QuestionRequest;
    try {
      const raw = await request.text();
      if (raw.length > 8_192)
        return response({ error: "payload_too_large" }, 413);
      input = JSON.parse(raw) as QuestionRequest;
    } catch {
      return response({ error: "invalid_json" }, 400);
    }
    if (
      !input ||
      typeof input.fruit_type !== "string" ||
      !/^[\p{L}0-9 _-]{1,40}$/u.test(input.fruit_type.trim())
    ) {
      return response({ error: "invalid_fruit_type" }, 400);
    }

    const requestId = crypto.randomUUID();
    const prompt = promptFor(input);
    console.log(JSON.stringify({
      event: "question_request",
      request_id: requestId,
      fruit_type: input.fruit_type,
      cv_stage: input.cv_stage ?? null,
    }));

    const groq = await callGroq(env, prompt, input.fruit_type.trim());
    if (groq.payload) {
      return response({
        ...groq.payload,
        provider: "groq",
        request_id: requestId,
        diagnostics: { latency_ms: groq.latencyMs },
      });
    }

    let cloudflare = await callCloudflare(env, prompt, input.fruit_type.trim());
    if (!cloudflare.payload && cloudflare.failure === "invalid_model_json") {
      cloudflare = await callCloudflare(
        env,
        `${prompt}\n\nRETRY STRICT: satu pertanyaan wajib menyebut ciri khusus buah secara eksplisit, bukan "buah ini". Selalu sertakan pertanyaan keamanan dengan opsi UNSAFE yang menyebut jamur, lendir/licin, kebocoran, atau bau busuk/menyengat. Setiap opsi wajib memiliki option_evidence yang tepat. Keluarkan JSON saja.`,
        input.fruit_type.trim(),
      );
    }
    if (cloudflare.payload) {
      return response({
        ...cloudflare.payload,
        provider: "cloudflare",
        request_id: requestId,
        diagnostics: { latency_ms: cloudflare.latencyMs },
      });
    }

    console.error(JSON.stringify({
      event: "question_request_failed",
      request_id: requestId,
      providers: { groq: groq.failure, cloudflare: cloudflare.failure },
    }));
    const fallback = ruleBasedFallback(input);
    return response({
      ...fallback,
      provider: "rule_based_fallback",
      request_id: requestId,
      diagnostics: {
        latency_ms: groq.latencyMs + cloudflare.latencyMs,
        provider_failures: { groq: groq.failure, cloudflare: cloudflare.failure },
      },
    });
  },
};
