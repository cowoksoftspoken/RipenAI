import { readFile, writeFile } from "node:fs/promises";
import { resolve } from "node:path";

const root = resolve(process.cwd(), "..");
const input = resolve(root, "outputs", "worker_analysis", "worker_stress_results.json");
const outputDir = resolve(root, "outputs", "worker_analysis");

const cues = {
  apple: /tangkai|bercak|memar|warna|kulit|keras|lunak/i,
  banana: /bercak|tangkai|ujung|warna|kulit|keras|lunak/i,
  mango: /tangkai|bercak|aroma|warna|kulit|keras|lunak/i,
  orange: /pori|berat|aroma|warna|kulit|keras|lunak/i,
  papaya: /tangkai|getah|bercak|warna|kulit|keras|lunak/i,
  pineapple: /mata|daun|mahkota|warna|kulit|keras|lunak/i,
  tomato: /tangkai|retak|bercak|warna|kulit|keras|lunak/i,
  avocado: /tangkai|tekan|lentur|berat|warna|kulit|keras|lunak/i,
  durian: /duri|tangkai|bunyi|warna|kulit|keras|lunak/i,
};

const labels = {
  apple: "apel",
  banana: "pisang",
  mango: "mangga",
  orange: "jeruk",
  papaya: "pepaya",
  pineapple: "nanas",
  tomato: "tomat",
  avocado: "alpukat",
  durian: "durian",
};

const generic = /\b(buah ini|buah tersebut|buahnya)\b/i;
const forbidden = /\b(cut|potong|cicip|mencicipi|taste|tusuk|menusuk|rusak|merusak)\b/i;
const englishFruit = /\b(apple|banana|mango|orange|papaya|pineapple|tomato|avocado)\b/i;

const allText = (questions) => questions.flatMap((question) => [question.text, ...(question.options || [])]).join(" ");

const main = async () => {
  const payload = JSON.parse(await readFile(input, "utf8"));
  const valid = payload.results.filter((result) => result.group === "valid" && result.status === 200 && Array.isArray(result.questions));
  const rows = valid.map((result) => {
    const fruit = result.name.split("-")[0];
    const text = allText(result.questions);
    const row = {
      name: result.name,
      fruit,
      stage: result.name.slice(fruit.length + 1),
      provider: result.provider,
      cue_present: cues[fruit]?.test(text) ?? false,
      generic_wording: generic.test(text),
      english_fruit_wording: englishFruit.test(text),
      unsafe_wording: forbidden.test(text),
      question_count: result.questions.length,
    };
    return row;
  });

  const count = (key, value = true) => rows.filter((row) => row[key] === value).length;
  const byFruit = Object.fromEntries(Object.keys(cues).map((fruit) => {
    const fruitRows = rows.filter((row) => row.fruit === fruit);
    return [fruit, {
      label: labels[fruit],
      cases: fruitRows.length,
      cue_coverage: fruitRows.filter((row) => row.cue_present).length,
      generic_wording: fruitRows.filter((row) => row.generic_wording).length,
      english_fruit_wording: fruitRows.filter((row) => row.english_fruit_wording).length,
      unsafe_wording: fruitRows.filter((row) => row.unsafe_wording).length,
    }];
  }));

  const summary = {
    source: input,
    valid_successes_audited: rows.length,
    cue_coverage: { pass: count("cue_present"), total: rows.length, rate: rows.length ? count("cue_present") / rows.length : 0 },
    generic_wording: { cases: count("generic_wording"), rate: rows.length ? count("generic_wording") / rows.length : 0 },
    english_fruit_wording: { cases: count("english_fruit_wording"), rate: rows.length ? count("english_fruit_wording") / rows.length : 0 },
    unsafe_wording: { cases: count("unsafe_wording"), rate: rows.length ? count("unsafe_wording") / rows.length : 0 },
    by_fruit: byFruit,
    warnings: rows.filter((row) => !row.cue_present || row.generic_wording || row.english_fruit_wording || row.unsafe_wording),
  };

  await writeFile(resolve(outputDir, "worker_quality_summary.json"), JSON.stringify(summary, null, 2));
  await writeFile(resolve(outputDir, "worker_quality_cases.json"), JSON.stringify(rows, null, 2));

  const lines = [
    "# RipenAI Question Worker — Question Quality",
    "",
    `Audited valid responses: **${summary.valid_successes_audited}**`,
    "",
    "| Check | Result |",
    "|---|---:|",
    `| Fruit-specific cue coverage | ${summary.cue_coverage.pass}/${summary.cue_coverage.total} (${(summary.cue_coverage.rate * 100).toFixed(1)}%) |`,
    `| Generic wording (buah ini) | ${summary.generic_wording.cases}/${summary.valid_successes_audited} |`,
    `| English fruit wording | ${summary.english_fruit_wording.cases}/${summary.valid_successes_audited} |`,
    `| Unsafe action wording | ${summary.unsafe_wording.cases}/${summary.valid_successes_audited} |`,
    "",
    "## Per fruit",
    "",
    "| Fruit | Cue coverage | Generic | English | Unsafe |",
    "|---|---:|---:|---:|---:|",
    ...Object.entries(byFruit).map(([fruit, value]) => `| ${value.label} | ${value.cue_coverage}/${value.cases} | ${value.generic_wording} | ${value.english_fruit_wording} | ${value.unsafe_wording} |`),
    "",
    summary.warnings.length ? `Warnings found: **${summary.warnings.length}**. See worker_quality_cases.json.` : "No quality warnings found.",
  ];
  await writeFile(resolve(outputDir, "worker_quality_report.md"), lines.join("\n") + "\n");
  console.log(JSON.stringify(summary, null, 2));
};

await main();
