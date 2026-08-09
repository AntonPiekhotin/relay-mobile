# How this documentation set is organised

Two tiers, designed so an AI agent loads only what a task needs.

```
CLAUDE.md              ← always in context. Invariants, routing, commands. ~150 lines.
docs/
├── PROTOCOL.md        ← wire contract. MIRROR THIS FILE IN THE BACKEND REPO.
├── ARCHITECTURE.md    ← layering, modules, DI, concurrency, testing
├── SYNC.md            ← outbox, acks, catch-up, DB schema. The hard part.
├── IOS.md             ← lifecycle, PushKit, CallKit, Xcode
├── ANDROID.md         ← foreground service, FCM, Doze, permissions
├── UI.md              ← Compose conventions, chat list, state
└── SETUP.md           ← toolchain, Gradle, dependencies, CI
```

## Why two tiers

A single large file means every task loads irrelevant context — an agent wiring up PushKit shouldn't be reading Compose theming conventions. A flat pile of files means the agent doesn't know what exists and misses cross-cutting rules.

`CLAUDE.md` stays small and always loaded, carrying the rules that apply everywhere plus a routing table. Topic files are loaded on demand and are self-contained.

## Setup

1. Copy `CLAUDE.md` to the mobile repo root.
2. Copy `docs/` to the mobile repo.
3. **Copy `docs/PROTOCOL.md` into the backend repo too.** It is the contract between them; if they diverge, the protocol is broken.
4. Rename `CLAUDE.md` to `AGENTS.md` if your tooling expects that instead.

## Keeping it useful

**`CLAUDE.md` must stay under ~150 lines.** When it grows, move detail into a topic file and leave a routing entry. A bloated root file defeats the whole structure.

**Update the phase checklist** in `CLAUDE.md` as you progress. Agents use it to know what exists and what doesn't.

**Update the "Backend reality check" section** when backend capabilities change — particularly when the notification service ships, since that's what makes iOS background delivery work.

**When the protocol changes**, update `PROTOCOL.md` in both repos in the same change. Consider the shared `relay-protocol` DTO module described in §10 of that file — since both sides are Kotlin, it turns field-name drift into a compile error instead of a runtime bug.

## What each file assumes

Every topic file is written to be read standalone alongside the code being changed. There is deliberate light repetition of critical rules (the `clientMsgId` idempotency rule appears in `CLAUDE.md`, `PROTOCOL.md`, and `SYNC.md`) because an agent reading only one of them still needs to get it right.
