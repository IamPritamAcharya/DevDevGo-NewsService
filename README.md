# 🧠 AI-Powered Tech News Aggregator — Backend Service

A **reactive Spring Boot** backend that fetches, deduplicates, ranks, and AI-summarizes tech articles every hour using Gemini, then stores them in Firebase for Flutter client consumption.

---

## 🏗️ Architecture

```
External Sources (HN + GNews + Reddit + RSS)
        ↓
Fetcher Layer (Reactive / Concurrent)
        ↓
Deduplication Engine (URL + Cosine Similarity + 24h Memory)
        ↓
Ranking Engine (Recency + Source + Engagement + Title + Keywords)
        ↓
LLM Summarization Engine (Gemini Flash-Lite)
        ↓
Storage Layer (Firebase Firestore)
        ↓
Client (Flutter App)
```

---

## 📦 Package Structure

```
com.devdevgo.news/
├── config/
│   ├── AppConfig.java            - WebClient + ObjectMapper beans
│   └── FirebaseConfig.java       - Firebase Admin SDK initialization
├── model/
│   ├── Article.java              - Unified raw article model
│   ├── NewsArticle.java          - Stored article (Firebase)
│   └── SystemState.java          - Pipeline state model
├── fetcher/
│   ├── ArticleFetcherService.java - Concurrent fetch orchestrator
│   ├── HackerNewsFetcher.java    - HN top stories (score ≥ 50)
│   ├── GNewsFetcher.java         - GNews technology feed
│   ├── RedditFetcher.java        - r/programming + r/technology
│   └── RssFetcher.java           - TechCrunch, Verge, Ars Technica
├── dedup/
│   └── DeduplicationEngine.java  - URL + Cosine + 24h memory dedup
├── ranking/
│   └── RankingEngine.java        - Multi-factor scoring + diversity filter
├── summarizer/
│   ├── ContentCleaner.java       - HTML stripping + content trimming
│   ├── GeminiClient.java         - Gemini API client (primary + fallback key)
│   └── SummarizationEngine.java  - LLM summarization with validation + retry
├── storage/
│   └── FirebaseStorageService.java - Firestore reads/writes
├── scheduler/
│   ├── NewsPipelineOrchestrator.java - Full pipeline execution
│   └── NewsPipelineScheduler.java    - Hybrid trigger system
└── controller/
    └── PipelineController.java   - REST API endpoints
```

---

## 🔥 Firebase Collections

| Collection | Purpose |
|---|---|
| `news/` | Stored summarized articles |
| `system_state/news_fetch` | Pipeline state (lastFetchedAt, status) |
| `recent_articles/` | 24-hour dedup memory |

---

## 🌐 REST Endpoints

| Method | Path | Description |
|---|---|---|
| GET | `/api/health` | Health check (Render keep-alive ping) |
| GET/POST | `/api/trigger` | External pipeline trigger (GitHub Actions) |
| GET | `/api/status` | Current pipeline state from Firebase |

---

## ⚙️ Setup

### 1. Firebase
1. Create a Firebase project at https://console.firebase.google.com
2. Enable **Firestore Database**
3. Go to Project Settings → Service Accounts → Generate new private key
4. Save as `firebase-service-account.json` in project root
5. Update `FIREBASE_DATABASE_URL` in environment

### 2. API Keys

| Variable | Source |
|---|---|
| `GNEWS_API_KEY` | https://gnews.io |
| `REDDIT_CLIENT_ID` | https://www.reddit.com/prefs/apps |
| `REDDIT_CLIENT_SECRET` | Same as above |
| `GEMINI_API_KEY_PRIMARY` | https://aistudio.google.com |
| `GEMINI_API_KEY_FALLBACK` | Separate project or key |

### 3. Local Development

```bash
# Set environment variables
export GNEWS_API_KEY=...
export REDDIT_CLIENT_ID=...
export REDDIT_CLIENT_SECRET=...
export GEMINI_API_KEY_PRIMARY=...
export GEMINI_API_KEY_FALLBACK=...
export FIREBASE_DATABASE_URL=https://your-project.firebaseio.com

# Run
./mvnw spring-boot:run
```

### 4. Deploy to Render
1. Push to GitHub
2. Create a new **Web Service** on Render
3. Set all environment variables in Render dashboard
4. Set `FIREBASE_CREDENTIALS_PATH` to `firebase-service-account.json`
5. Make sure `firebase-service-account.json` is committed (or use Render secret files)

### 5. GitHub Actions (External Trigger)
1. Go to your GitHub repo → Settings → Secrets
2. Add `RENDER_SERVICE_URL` = your Render service URL (e.g. `https://news-service.onrender.com`)
3. The workflow in `.github/workflows/trigger.yml` will fire every hour

---

## 📊 Ranking Formula

```
Final Score = (Recency × 0.4) + (Source × 0.2) + (Engagement × 0.2)
            + (Title Quality × 0.1) + (Keyword Boost × 0.1)
```

- **Recency (0–10):** Linear decay, 0h=10, 24h=0
- **Source (0–10):** Trusted domain scoring, default 5.0
- **Engagement (0–10):** Log-scaled upvotes + comments
- **Title Quality (−5 to +5):** Clickbait penalty, length reward
- **Keyword Boost (0–3):** AI, Flutter, Security, etc.

---

## 🔁 Deduplication

1. **URL normalization** (remove query params, lowercase)
2. **URL dedup** across current batch
3. **24-hour Firebase memory** check (seen URLs + titles)
4. **Cosine similarity** on titles (threshold > 0.7 = duplicate)

---

## 🤖 Summarization

- Model: **Gemini 1.5 Flash**
- Max output: **60 words**
- Rate limit: **1.5s between requests**
- Key strategy: **Primary → Fallback** key
- Validation: 10–70 words
- Retry: once on invalid output
- Fallback: article description → title

---

## 📈 Performance Model

| Stage | Count |
|---|---|
| Fetched | ~80–120 raw articles |
| After dedup | ~50–80 unique articles |
| After ranking | Top 30 |
| LLM calls | 30 per run |
| Per day | ~720 summaries |
