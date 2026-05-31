# README candidates — pick one

You asked for two README directions (v1 and v3) to compare and choose from.
Both are complete (English + 简体中文) and ready to promote. Both fix the
**verified** issues in the current README:

- ❌ stale `MingkeVan/opendataworks` org → ✅ `opendata-lab/opendataworks`
  (badges, DeepWiki, issue links)
- ❌ `README_zh-CN.md` pointed to a dead `mingkevan.github.io` homepage and
  old local `docs/guide/*.md` links → ✅ live docs-site links, in sync with EN

> The Docker Compose instructions keep the **original, correct** form —
> `cp deploy/.env.example deploy/.env` and
> `docker compose -f deploy/docker-compose.dev.yml up -d`, run from the repo
> root — matching the actual repository layout.

## Option 1 — Fix + polish + restructure

Files: [`README.v1.md`](README.v1.md) · [`README.v1.zh-CN.md`](README.v1.zh-CN.md)

Keeps the current README's familiar shape and tone, but adds a table of
contents, an architecture diagram, a tech-stack table, an access-points
table, and tighter section flow. **Lower-risk, evolutionary.**

## Option 3 — Full rewrite

Files: [`README.v3.md`](README.v3.md) · [`README.v3.zh-CN.md`](README.v3.zh-CN.md)

A from-scratch narrative: a "problem → solution" framing, a "who it's for"
section, emoji-led capability cards, a screenshot gallery, and a punchy
3-step quick start. **Higher-impact, more marketing-forward.**

## How to choose

Tell me **"go with v1"** or **"go with v3"** and I'll:

1. Promote the chosen pair to `/README.md` and `/README_zh-CN.md`.
2. Delete this `readme-candidates/` folder.
3. Commit and push to `claude/focused-newton-9Ca2M`.
