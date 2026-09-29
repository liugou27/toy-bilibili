# toy-bilibili

**English** | [简体中文](README.zh-CN.md)

[![Java 21](https://img.shields.io/badge/Java-21-orange)]() [![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5-green)]() [![Spring Cloud Alibaba](https://img.shields.io/badge/Spring%20Cloud%20Alibaba-2025.0.0.0-red)]() [![License: MIT](https://img.shields.io/badge/License-MIT-blue)]()

> A microservices video platform built for learning — modeled after the core pipeline of Bilibili/YouTube: chunked upload (direct-to-storage / instant dedup / resumable) → risk scoring (typed violations / blacklist samples / hot-reloadable sensitive words) → manual review (claim-based) → transcoding (distributed segment-parallel HLS) → playback (Artplayer + danmaku) → interactions (like / favorite / comment / danmaku / follow / penalties). 6 services, 324 unit tests, 22-step end-to-end regression.

A microservices video platform MVP covering the full pipeline: **upload → auto screening → manual review → transcode → playback**.

The focus is hands-on practice with the whole microservices stack: service registry & config center (Nacos), unified gateway auth (Spring Cloud Gateway + JWT), inter-service calls (OpenFeign), async event-driven flow (RocketMQ), object storage (MinIO/S3), and schema-per-service (PostgreSQL).

## Architecture

```
Frontend  Vue3 + Vite + Element Plus + hls.js
        │
   Gateway :8080 ── routing / JWT verification / CORS / traceId / unified error handling
        │
 user:8101     video:8102      moderation:8103    media:8104(×N)
 auth & user   upload/state     auto + manual      transcode cluster
        │        machine         review            (segment-parallel)
        └── PostgreSQL (schema-per-service) ── MinIO (videos/hls) ── RocketMQ events ── Redis ─┘
```

- video-service is the single writer of the video state machine; screening/transcode services only request transitions through internal APIs, events act purely as triggers.
- State flow: `UPLOADED → AUTO_SCREENING → UNDER_REVIEW → APPROVED → TRANSCODING → PUBLISHED`, with branches to `REJECTED` / `TRANSCODE_FAILED` (uploader can retry).
- Transcode output is multi-bitrate HLS (master.m3u8 + 480p/720p/1080p) plus a poster, served through the gateway `/media/**` proxy from a private bucket.

## Distributed Transcoding Cluster

The transcode service is built as a horizontally scalable worker cluster:

- **Lease-based claiming** — every job/segment claims work via a single atomic conditional `UPDATE` (`PENDING/FAILED`, or `RUNNING` with an expired lease). Only one instance in the cluster can win.
- **Segment-parallel transcoding** — long videos are split at keyframes (lossless `-c copy`, default 60s per segment; short videos skip splitting). Segments become independent sub-jobs dispatched through RocketMQ and transcoded in parallel across instances, then assembled into the final playlists (`EXT-X-DISCONTINUITY` at segment boundaries).
- **Scale in/out without disruption** — a heartbeat renews the lease while working. If an instance dies or is scaled down, only the segment it was currently transcoding gets re-run (verified: kill -9 mid-transcode re-runs exactly one segment; completed segments keep `attempts=1`). `LeaseReaper` sweeps expired leases as a safety net alongside MQ redelivery.
- **Fenced writes** — terminal writes are conditioned on the instance's ownership, so a superseded late writer can never corrupt state.

## Repository Layout

| Directory | Contents |
|---|---|
| `toys-common` | Shared response/error model, global exceptions, traceId, JWT, snowflake IDs, Python script runner, idempotency SDK |
| `toys-api` | Cross-service contracts: Feign interfaces, event topics and DTOs |
| `toys-gateway` | Gateway: routing, auth filter, CORS, error sink |
| `toys-user-service` | Register / login / user profile / follow / penalties |
| `toys-video-service` | Upload (streamed to MinIO), state machine, listings/detail, play counts (Redis-buffered), search & recommendation |
| `toys-moderation-service` | Auto screening (FFprobe + frame rules), manual review admin API, sensitive-word management |
| `toys-media-service` | Transcode cluster worker (lease claiming, segment parallelism, assembly, virtual threads) |
| `toys-risk-service` | Stateless risk scoring engine (typed violations, action PASS/REVIEW/BLOCK) |
| `frontend/` | Vue3 SPA (home / watch / upload / my videos / review admin / auth) |
| `scripts/` | Media scripts in pure Python stdlib: probe, screening, split, segment transcode, merge |
| `deploy/` | docker-compose, Nacos config bootstrap, broker.conf, run scripts, ops manual |
| `docs/` | Design docs, implementation plan, architecture deep-dive |

## Quick Start

Requirements: Java 21, Maven, Node 22, Python 3, ffmpeg, Docker (middleware), PostgreSQL.

See **[deploy/README.md](deploy/README.md)** for the full walkthrough, accounts, acceptance checklist, and failure drills.

```bash
# Middleware (Nacos / RocketMQ / Redis)
cd deploy && docker compose up -d && cd .. && ./deploy/start-minio.sh &

# Push shared config into the Nacos config center (optional but recommended)
./deploy/nacos-init.sh

# Backend (JAVA_HOME must point to a Java 21 JDK)
mvn -s deploy/maven-settings.xml -DskipTests package
for svc in user-service gateway video-service moderation-service media-service; do
  java -jar toys-$svc/target/toys-$svc-0.1.0-SNAPSHOT.jar &
done

# Frontend
cd frontend && npm install && npm run dev   # http://localhost:5173
```

Built-in admin account: `admin / admin123`.

## Documentation

- Design spec: [docs/superpowers/specs/2026-09-25-video-platform-microservices-design.md](docs/superpowers/specs/2026-09-25-video-platform-microservices-design.md) (Chinese)
- Implementation plan: [docs/superpowers/plans/2026-09-25-video-platform-microservices.md](docs/superpowers/plans/2026-09-25-video-platform-microservices.md) (Chinese)
- Backend architecture deep-dive: [docs/backend-architecture.md](docs/backend-architecture.md) (Chinese)

## Branching Model

- `main`: stable branch, kept CI-green (tests + build run on every push)
- `dev`: development branch; finished work is merged back to main
- feature branches: cut `feat/xxx` from dev, then PR → dev → main

```bash
git checkout dev && git pull
git checkout -b feat/your-feature   # develop
# ...commit, then
git push -u origin feat/your-feature  # open a PR into dev on GitHub
```

## License

[MIT](LICENSE)
