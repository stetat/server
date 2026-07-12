# MENTOR_PLAN.md — Java HTTP Server From Scratch

> Drop this at the root of the project repo. It carries the full context of a mentoring
> session started in the Claude app so any fresh Claude Code session can pick up exactly
> where we left off. **If you are a Claude instance reading this: read the "Instructions
> for the mentor" section first — the rules there override the default urge to just write code.**

---

## The project

Build an HTTP server from scratch in **Java**, to actually understand the concepts that
frameworks (Spring, Tomcat, Netty) hide: sockets, blocking vs non-blocking I/O, the accept
loop, thread pools, the C10K problem, request parsing/framing, the reactor pattern, and
virtual threads (Project Loom).

This is a **learning project first, portfolio piece second.** The point is comprehension,
not a finished product. The finished server is a nice resume line ("built an HTTP server and
a tiny web framework from scratch in Java, including NIO event-loop and virtual-thread
versions with benchmarks"), but that only counts if the understanding is real.

## Ground rules (non-negotiable)

- **Pure JDK only.** No Maven, no Gradle, no Spring, no Netty, no external libraries.
  `javac` and `java` are enough. Libraries would hide exactly what we are here to see.
- **Java 21+.** A late phase uses virtual threads (Loom), so keep them available.
- **Test with `nc`/`telnet` and `curl`, not a browser** (at least early). Browsers add hidden
  magic that obscures what the server is actually doing.

## The mentoring contract (the most important part)

- **The student writes the code. The mentor does NOT paste finished solutions.**
- The loop is: mentor gives a concept + a challenge → student implements and hits the wall →
  student brings code back → mentor reviews (bugs, style, and the *why*) → go one level deeper.
- When the student is stuck, the mentor gives **hints and points at the exact JDK API** — not
  the answer. Reading the JavaDocs is part of the skill being built.
- Reviewing broken/ugly code is where the real teaching happens — bring it anyway.
- The student should push back if explanations are too hand-wavy.

---

## The phase map

Each phase unlocks one core concept. Don't skip ahead.

1. **Echo server** — raw sockets, the accept loop, blocking I/O.
   *Unlocks: a server is just a program that reads and writes bytes on a port.*

2. **Speak HTTP** — parse the request line + headers by hand; send a valid HTTP response.
   *Unlocks: HTTP is just text over TCP.*

3. **Routing** — map (method + path) → handler; return proper 404s.
   *Unlocks: how a framework dispatches a request.*

4. **Concurrency** — first *feel* the pain (one slow client freezes everyone), then
   thread-per-connection, then a bounded thread pool.
   *Unlocks: the C10K problem, thread pools, why blocking I/O has a ceiling.* **This is the heart.**

5. **Connection lifecycle** — keep-alive, Content-Length, reading request bodies correctly, timeouts.
   *Unlocks: request framing and why parsing Content-Length matters.*

6. **Static files** — serve files, set MIME types, block path-traversal (`../`).
   *Unlocks: streaming I/O and a real security bug (directory traversal).*

7. **The modern fork** — rebuild the server two ways and benchmark them:
   (a) an NIO single-threaded **event loop** (the reactor pattern — how Node/Netty work), and
   (b) thread-per-connection on **virtual threads** (Loom).
   *Unlocks: non-blocking I/O, the reactor pattern, virtual threads, and the real tradeoff.*

8. **Tiny framework (optional)** — a clean `server.get("/path", handler)` API plus a
   thread-safe in-memory store to revisit race conditions; then load-test it.
   *Unlocks: API design, and a loop back to the consistency/concurrency problems.*

---

## Current status

- **Phase:** 7 — The modern fork (event loop + virtual threads + benchmark) — THE FINALE
- **Goal of current phase:** Rebuild the server's I/O model two ways and benchmark all three against
  the C10K load. (a) **NIO single-threaded event loop** — the reactor pattern (Selector/kqueue),
  one thread juggling thousands of sockets, non-blocking. (b) **Virtual threads (Loom)** —
  thread-per-connection but on virtual threads, keeping the simple blocking code. Then benchmark
  thread-pool vs event-loop vs virtual-threads and write up the tradeoffs.
- **Suggested order:** do **virtual threads first** (tiny change, huge payoff — reuse ALL current
  blocking code, swap `Executors.newFixedThreadPool(n)` → `Executors.newVirtualThreadPerTaskExecutor()`,
  watch it blow past the ~2020 wall). Then the **NIO event loop** as a separate, harder rewrite
  (can't reuse blocking read/write — needs non-blocking channels + per-connection state machines
  for partial reads/writes). Then benchmark.
- **APIs in play:** (b) `Executors.newVirtualThreadPerTaskExecutor()`. (a) `java.nio.channels`:
  `ServerSocketChannel`, `SocketChannel` (configureBlocking(false)), `Selector`, `SelectionKey`
  (OP_ACCEPT/OP_READ/OP_WRITE), `ByteBuffer`. Load-test with `tests/load.py`.
- **Constraints:** pure JDK; the event loop must never block (one slow op freezes everyone); track
  per-connection read/write state since data arrives in fragments.
- **Test with:** `tests/load.py` at 3000–10000 connections against each version; measure how many
  connections each sustains and throughput. Virtual threads + event loop should both pass the 2020
  wall; thread pool should not.
- **Questions to answer when reviewing:** Why do virtual threads escape the `kern.num_taskthreads`
  cap (they're not OS threads)? What does the Selector actually do (one thread, many sockets — how)?
  Why must an event loop handle partial reads/writes with explicit state, when blocking code didn't?
  Which model wins for which workload, and why?

### Phase 6 — DONE
- **Built:** static file serving from a `www/` web root — map path→file, `Files.readAllBytes`, write
  body as raw bytes via `OutputStream` (headers as text→bytes), correct Content-Length from file
  size. MIME types via extension→type map (default `application/octet-stream`), threaded through
  `sendResponse`. Path-traversal defense: `WEB_ROOT.resolve(path).normalize()` + `startsWith(WEB_ROOT)`
  check, ordered **containment-first** then existence (closes the 403-vs-404 existence oracle).
- **Understood:** binary bodies force raw-byte output (no char reader/PrintWriter, no appended
  `\r\n`); MIME Content-Type drives browser rendering vs download (octet-stream = download);
  path traversal (CWE-22) reads any file the process can — `../` escapes `www`; blocklisting `..`
  fails (URL-encoding etc.) — validate the *resolved destination* (`startsWith`), don't sanitize the
  *spelling*; validate containment BEFORE any filesystem op (no existence oracle). Fixed `+1`
  substring bug on extension extraction along the way.

### Phase 5 — DONE
- **Built:** keep-alive inner loop (serve many requests per connection, break on `null`/EOF or
  `Connection: close`); `Content-Length` parsing + short-read accumulation loop to read POST
  bodies exactly; `POST /echo` returns the body; `Socket.setSoTimeout(5000)` idle timeout with a
  targeted `catch (SocketTimeoutException)` that closes quietly.
- **Understood:** `null` request line = client closed (normal loop exit, not an error); can't
  `readLine()` a body (no delimiter — Content-Length frames it); read the body from the SAME
  BufferedReader (it buffered ahead); `read()` returns per-call count so you must accumulate
  (`count += n`) and handle `-1`; idle timeout is a Slowloris/DoS defense, not a nicety; NEVER call
  `.close()` on a try-with-resources-managed socket (redundant, IDE warns). Lingering char-vs-byte
  body limitation noted (ASCII only for now).

### Phase 4 — DONE (the heart)
- **Built:** thread-per-connection (`new Thread(...).start()`) → bounded pool
  (`Executors.newFixedThreadPool(n)` + `pool.submit(...)`), per-connection `try(socket)`/`catch`
  so one bad request can't kill the server. Added `/slow` route + Python asyncio `tests/load.py`
  load generator.
- **Understood (by empirical experiment):** single-threaded = one slow client freezes everyone
  (throughput hostage to slowest request); thread-per-connection fixes that but hits a HARD wall:
  macOS `kern.num_taskthreads: 2048`, minus ~28 JVM threads ≈ **2020 connections** → `OutOfMemoryError:
  unable to create native thread` kills the accept loop. That's C10K. Bounded pool survives the
  flood (2500 conns, 0 crashes) but a small pool causes head-of-line blocking (curl timeouts). A
  pool sized near 2048 just moves the wall (proved: 2000 works, 2020 breaks). Real frameworks:
  thread pools (Tomcat) vs event loops (Nginx/Node/Netty, epoll/kqueue) vs virtual threads
  (Go/Loom) — motivates Phase 7.

### Phase 3 — DONE
- **Built:** method+path routing (checks path exists AND method allowed → 200/201, else 405, else
  404), route data in static maps, `sendResponse(writer, status, statusText, body)` helper,
  Content-Length from actual bytes via single `responseBody` string (no magic `+2`).
- **Understood:** routing = a lookup on (method, path); 404 vs 405; single-source-of-truth for
  Content-Length (measure exactly the bytes you send — too-small silently truncates, too-large
  hangs the client, curl exit 18); PrintWriter swallows IOException (needs checkError).
- **Known deferred bug:** still crashes on null/malformed request line — address in Phase 4 with
  per-connection try/catch. Route data is spread across 5 maps (revisit in Phase 8 framework).

### Phase 2 — DONE
- **Built:** parse request line (`readLine` + `split(" ")` → method/path/version), read headers
  until blank line, hand-built `200 OK` response with explicit `\r\n`, `Content-Length`, and
  `flush()`.
- **Understood:** HTTP is text over TCP; `readLine()` strips terminators (blank line = `""`,
  EOF = `null`); `PrintWriter` autoFlush covers `println` not `print` (must `flush()`); keep-alive
  ("connection left intact"); Content-Length must match body byte count.
- **Known deferred bug:** malformed/empty request line (`null.split`, `request[2]` OOB) crashes
  the whole server — fix with per-connection try/catch in Phase 4.

### Phase 1 — DONE
- **Built:** `src/EchoServer.java` — `ServerSocket` on 8080, `while(true)` accept loop,
  per-client `try`-with-resources `Socket`, `BufferedReader`/`PrintWriter`, echo loop using
  `while ((line = reader.readLine()) != null)`.
- **Understood:** why `accept()` blocks (OS suspends the process, zero CPU), the read/echo loop,
  the assignment-in-condition idiom, per-client socket closing to avoid leaks, Ctrl+C to stop.

---

## Progress log

_Update this at the end of each session so the next one has context._

- **2026-07-08 — Phase 1 done.** Built the echo server (`src/EchoServer.java`): accept loop,
  per-client socket, read/echo with `readLine()`. Student is "learning Java too," so mechanics
  get explained slowly. Moved on to Phase 2 (speak HTTP) same session.
- **2026-07-08 — Phase 2 started.** Parse the HTTP request line + headers by hand; send a valid
  HTTP response that `curl -v` accepts. Next: student writes the parser + response.
- **2026-07-10 — Phase 2 done, Phase 3 started.** Student got a valid `200 OK` back via curl,
  then parsed the request line into method/path/version. Debugged along the way: readLine strips
  terminators, autoFlush-vs-print, unflushed buffer causing hangs, a `printl` typo. Now on
  Phase 3 (routing): dispatch on method+path, real 404s, compute Content-Length from body.
- **2026-07-10 — Phase 3 done, Phase 4 started.** Routing works (200/201/404/405, verified live
  with curl). Fought through two Content-Length bugs (off-by-2 both directions) and landed on a
  single-source-of-truth `responseBody`. Now on Phase 4 (concurrency, the heart): step 1 is to
  make the single-threaded pain visible with a slow handler before threading anything.
- **2026-07-11 — Phase 4 DONE, Phase 5 started.** Went through all three steps + empirically
  reproduced C10K: thread-per-connection crashes at ~2020 conns (macOS 2048 thread cap − JVM
  threads), bounded pool survives the flood, and a pool sized ~2020 recreates the crash (student
  tested 2000=ok, 2020=break). Built `tests/load.py` (asyncio) as the load generator. Discussed
  the framework landscape (thread pool vs event loop vs virtual threads). Now on Phase 5:
  keep-alive, reading POST bodies via Content-Length, and socket read timeouts.
- **2026-07-12 — Phase 5 DONE, Phase 6 started.** Keep-alive loop, POST body reading (short-read
  loop), `POST /echo`, and 5s idle timeout all working and tested (idle nc drops after 5s).
  Cleared up curl `-v` vs plain, null-vs-empty request line, and redundant close-in-catch. Now on
  Phase 6: serve static files from a web root, MIME types, and defend against path traversal.
- **2026-07-12 — Phase 6 DONE, Phase 7 started.** Static files serve with raw-byte bodies + correct
  MIME (verified html renders, png intact). Reproduced path-traversal live (read /etc/passwd!), then
  fixed with resolve+normalize+startsWith, and closed the existence oracle by checking containment
  before existence. Discussed resume framing (project already strong; Phase 7 benchmark is the
  differentiator). Now on Phase 7 finale: virtual threads first (easy win past the 2020 wall), then
  the NIO event loop, then benchmark all three. NOTE: pre-Phase-7 cleanup still pending (class still
  named EchoServer, debug System.out noise, route data in 5 maps) — do before showcasing.

<!--
Template for future entries:
- [date] — Phase N: what I built, what I understood, what confused me, what's next.
-->

---

## Open calibration question

Student's Java comfort level (solid Java, or partly a "learn Java properly" project too?) —
this changes how much the mentor explains vs. assumes. **Answer (2026-07-08): This is partly a
"learn Java properly" project. Explain language mechanics (streams, `AutoCloseable`,
try-with-resources, exceptions) as they come up, more slowly — don't assume the language.**

---

## Instructions for the mentor (Claude Code, read this)

You are mentoring, not delivering. Do **not** write the server for the student. Give one
concept and one concrete challenge at a time, let them implement it, then review what they
bring back — pointing out bugs, explaining the *why*, and asking the "questions to sit with."
When they're stuck, hint and name the exact JDK API; don't hand over working code. Keep them
on the current phase; don't leak ahead. The whole value of this project evaporates if you
solve it for them.