# RipenAI Question Worker

Worker ini menjadi satu-satunya pintu dari aplikasi Android ke layanan pertanyaan. Kunci Groq tetap berada di Worker, bukan di APK. Groq dicoba lebih dulu; Cloudflare Workers AI menjadi fallback.

```powershell
npm install
Copy-Item .dev.vars.example .dev.vars
# isi GROQ_API_KEY di .dev.vars
npm run typecheck
npm run dev
```

Endpoint aplikasi: `POST /v1/questions`. Setiap opsi mengembalikan `option_evidence` yang eksplisit: `UNRIPE`, `RIPE`, `OVERRIPE`, `NEUTRAL`, atau `UNSAFE`. Android tidak pernah menebak evidence dari urutan opsi. Worker selalu menyertakan satu pertanyaan keamanan; evidence `UNSAFE` untuk jamur, lendir/licin, kebocoran, atau bau busuk menghasilkan peringatan Busuk yang tidak boleh diturunkan oleh bukti kematangan lain. Untuk emulator Android saat development, build dengan:

```powershell
cd ..\android-app
.\gradlew.bat :app:assembleDebug -PQUESTION_API_URL="http://10.0.2.2:8787/v1/questions"
```

Deploy setelah login Wrangler dan set secret:

```powershell
npx wrangler login
npx wrangler secret put GROQ_API_KEY
npm run deploy
```

Lalu build APK memakai URL `https://<worker>.workers.dev/v1/questions`.
