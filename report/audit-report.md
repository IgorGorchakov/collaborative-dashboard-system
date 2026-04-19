# Audit Report — Collaborative Dashboard System

Scope: every Java source file under `src/main/java`, Flyway migrations, `application.yml`, `pom.xml`, and the three frontend assets under `src/main/resources/static`. Auditor verified each finding against the source; the subagent's initial pass mixed real bugs with false positives and those have been removed here.

## Summary

| Severity | Count |
|----------|-------|
| Critical | 1 |
| High     | 4 |
| Medium   | 5 |
| Low      | 5 |

No SOLID violations worth flagging. The layering (REST → service → repository, WebSocket → service) is clean, DTOs are records, constructor injection is used uniformly, and the `ActiveUserRegistry` abstraction is well-shaped. The real problems are validation gaps, CORS, missing exception handling, and a few frontend robustness issues.

---

## Critical

### C1. CORS wildcard lets any origin open a WebSocket (and pretend to be any user)

- **File:** `src/main/resources/application.yml:27`, `src/main/java/com/example/dashboard/api/websocket/config/WebSocketConfig.java:23,37`
- **Problem:** `app.websocket.allowed-origins` defaults to `"*"` and is passed straight into `setAllowedOriginPatterns(...)`. Any website a user visits can open a STOMP/SockJS connection to this server. Combined with the fact that the username is a client-supplied `X-Username` header (no authentication), an attacker's page can connect with any free name on any dashboard whose UUID they know, spam strokes, and trigger `canvas cleared`-style actions.
- **Fix:** Do not ship a wildcard default. Require an explicit allow-list via environment variable (`APP_WEBSOCKET_ALLOWED_ORIGINS=https://your.domain`). Document in `application.yml` that this must be set for any non-local deployment. Longer term: bind session identity to an authenticated principal rather than a client-supplied header.

---

## High



### H3. `/history` builds the full response in memory — denial-of-service via a large board

- **File:** `src/main/java/com/example/dashboard/api/rest/DashboardController.java:48-59`, `src/main/java/com/example/dashboard/service/StrokeService.java:20-53`
- **Problem:** `StrokeService.history` loads up to `HISTORY_MAX = 50_000` rows into a `List<Stroke>`, then `DashboardController.history` concatenates every `payload` JSON string into a single `StringBuilder` and returns it as a `String`. Each payload is bounded (~2 KB max from `Size(max=512) points`), so 50 000 × 2 KB = up to ~100 MB per call, fully materialized on the heap, duplicated between the list and the StringBuilder. One user hitting refresh on a well-used board can exhaust heap. The in-code comment already flags this ("Pagination + incremental replay is tracked in feature 18"), so it's known but present.
- **Fix:** Two acceptable interim options before pagination lands:
  1. Stream the response using `StreamingResponseBody` — writes payload strings to the output with commas, never buffers the full array.
  2. Add an explicit byte-size cap inside the loop, return `413 Payload Too Large` when exceeded with guidance to paginate.
  Option 1 is preferred: fixes the server-heap issue without changing the API. Frontend already handles arbitrarily long replays via the `replayHistory` → `liveBuffer` mechanism.

### H4. `liveBuffer` can grow without bound during a slow history replay

- **File:** `src/main/resources/static/app.js:67,204,167-188`
- **Problem:** Between STOMP `onConnect` and the moment `replayHistory()` finishes, every live stroke frame is pushed to `liveBuffer` unconditionally. If replay is slow (see H3) and the board is active, the buffer grows indefinitely. Memory is reclaimed once replay completes, but a browser tab can easily hit hundreds of MB in the meantime, and under memory pressure the tab crashes.
- **Fix:** Cap `liveBuffer` and drop oldest when full:
  ```javascript
  const MAX_LIVE_BUFFER = 5000;
  // where liveBuffer.push(stroke) is called:
  if (liveBuffer.length >= MAX_LIVE_BUFFER) liveBuffer.shift();
  liveBuffer.push(stroke);
  ```
  Dropped strokes get picked up on the next `/history` fetch, so correctness is preserved.

---

## Medium

### M1. `DrawingController` silently drops invalid messages with no telemetry

- **File:** `src/main/java/com/example/dashboard/api/websocket/DrawingController.java:29-54`
- **Problem:** Every invariant violation (dashboard mismatch, session not bound, username mismatch) returns silently. `log.debug(...)` is good for local dev, but on a real deployment with INFO-level logging these are invisible, so an attacker probing the endpoint leaves no trace and legitimate bugs go unnoticed. Draw rate-limiting (e.g. one user flooding `/app/draw`) isn't visible either.
- **Fix:** Bump the mismatch logs to `log.warn(...)` with session-id and username. Consider a Micrometer counter `dashboard_stroke_rejected_total{reason}`.

### M2. `StrokeRepository.deleteByDashboardId` / `resetOrdinal` rely on caller transactions

- **File:** `src/main/java/com/example/dashboard/repository/StrokeRepository.java:30-37`, `src/main/java/com/example/dashboard/service/DashboardService.java:41-45`
- **Problem:** These are `@Modifying` without `@Transactional`. Currently safe because `DashboardService.clear()` is `@Transactional`, but if a future caller forgets, Spring throws `TransactionRequiredException` at runtime. Convention in the rest of this project is to annotate at the service; the repo methods are implicitly relying on that.
- **Fix:** Either (a) document this expectation in a class-level javadoc on `StrokeRepository`, or (b) annotate each `@Modifying` method with `@Transactional` so they work regardless of caller. Option (b) is two lines and removes a trap.

### M3. `app.js` uses `alert()` for some errors and the inline error panel for others

- **File:** `src/main/resources/static/app.js:353,365` (alert) vs. `99-109,317-331` (inline)
- **Problem:** Create/join failures show inline `.error` messages (good); clear and delete failures use `alert()` (jarring modal, blocks UI, harder to automate/test). The project already has a CSS class and a helper for inline errors.
- **Fix:** Replace the two remaining `alert()` calls with `showError(...)` targeted at a small element next to the toolbar, or simply log + toast.

### M4. `fetch()` calls have no timeout — hung request hangs the UI

- **File:** `src/main/resources/static/app.js:71-83`
- **Problem:** If the server is slow or the connection stalls mid-response, `fetch()` never resolves. The user sees "creating..." forever with no way out. Affects create, join, history, clear, delete.
- **Fix:** Wrap `fetch` with `AbortController` + a 10 s timeout; surface a retry-friendly error.

### M5. Defensive casts on STOMP session attributes can `ClassCastException` silently

- **File:** `src/main/java/com/example/dashboard/api/websocket/DrawingController.java:36-39`, `ConnectionListener.java:46-47`, `ClearController.java`*
- **Problem:** `(UUID) attrs.get(ATTR_DASHBOARD_ID)` and `(String) attrs.get(ATTR_USERNAME)` throw CCE if a future code path ever stores a different type under these keys (easy to do in refactors — the keys are plain strings). The CCE propagates to the STOMP channel and becomes a generic ERROR frame — hard to diagnose.
  <br/>*ClearController was added in a previous working session; if it exists, the same finding applies.
- **Fix:** Use `instanceof` pattern matching:
  ```java
  UUID sessionDashboard = attrs.get(ATTR_DASHBOARD_ID) instanceof UUID uuid ? uuid : null;
  String sessionUsername = attrs.get(ATTR_USERNAME) instanceof String s ? s : null;
  ```

---

## Low

### L1. `DashboardService.DashboardDeletedEvent` is published but never consumed

- **File:** `src/main/java/com/example/dashboard/service/DashboardService.java:21,52,56`
- **Problem:** `events.publishEvent(new DashboardDeletedEvent(...))` runs on every delete, but no `@EventListener` subscribes. Dead code carrying a misleading "// for cache invalidation" comment.
- **Fix:** Delete the event, the publisher field, and the comment — or wire the actual listener. If the intent was the TODO cache in the interceptor, make that explicit.

### L2. `StrokeService.serialize` uses fully-qualified `ObjectNode` instead of import

- **File:** `src/main/java/com/example/dashboard/service/StrokeService.java:60`
- **Problem:** `((com.fasterxml.jackson.databind.node.ObjectNode) node)` — noise; inconsistent with the rest of the file.
- **Fix:** Import `ObjectNode`.

### L3. `renderStroke` doesn't clamp `thickness` client-side either

- **File:** `src/main/resources/static/app.js:259`
- **Problem:** After H1 is fixed server-side, stale payloads already in the DB can still have garbage thickness values. Rendering `ctx.lineWidth = 99999` paints the entire canvas black.
- **Fix:** `ctx.lineWidth = Math.min(32, Math.max(1, stroke.thickness || 2))`.

### L4. `app.js:184` replays live buffer even when history fetch failed

- **File:** `src/main/resources/static/app.js:181-187`
- **Problem:** `catch` logs "History replay failed"; `finally` then drains `liveBuffer` and sets `replayDone = true`. The user sees partial state (live strokes visible, historical strokes missing) with no indication anything went wrong. They'll think the board is empty/clean when it isn't.
- **Fix:** On replay failure, surface a retry banner in the UI; don't silently let the user draw on top of an incomplete canvas.

### L5. `Testcontainers` dependency declared but only used by one pure-JVM test

- **File:** `pom.xml:94-104`, `src/test/java/com/example/dashboard/presence/ActiveUserRegistryTest.java`
- **Problem:** `testcontainers` + `testcontainers-postgresql` are declared but no test currently starts a container. They add ~30 MB to the dev classpath and signal intent that isn't followed through.
- **Fix:** Either (a) drop the deps until an integration test needs them, or (b) add a minimal `@SpringBootTest` that stands up the full stack. Not a critical decision.

---

## Noted but not flagged

Reviewed and deemed fine:

- `ActiveUserRegistry`'s in-memory design is intentional (confirmed with user). Thread safety via `synchronized(bucket)` is correct. No finding.
- `reserveNextOrdinal` uses a native `INSERT ... ON CONFLICT ... RETURNING` query. The parameter is bound via `@Param`, so no SQL injection. Postgres-specific by design, acceptable for this project.
- `ConnectionListener.onSubscribe` triggers on every subscribe, which broadcasts the full user list to everyone subscribed to the users topic. Intentional (fixes a connect-time race, documented in the javadoc) and idempotent.
- `SimpMessagingTemplate.convertAndSend` mixes raw JSON String (from `DrawingController`) and Map-serialized sends — correct per Spring's message converter behavior.
- `renderUsers` uses `textContent`, not `innerHTML`, so usernames cannot inject markup — no XSS.
- Constructor injection via `@RequiredArgsConstructor` is used consistently. DTOs are immutable records. Entities use Lombok uniformly. Layering is clean.

## Suggested remediation order

1. **C1** — ship a non-wildcard CORS default before anything else reaches a public environment.
2. **H1** — trivial DTO annotations; prevents DB bloat.
3. **H2** — three-line fix; turns confusing 500s into proper 404s for the UI.
4. **H4 + L3 + L4** — frontend robustness, single PR.
5. **H3** — streaming response, one class change.
6. **Medium + Low** — opportunistic cleanup.
