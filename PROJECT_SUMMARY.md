# step-thru - Project Summary

A small open-source Clojure library: **"Demo PWs", a runbook for the UI.** It turns any
Playwright test into a live demonstration that a person steps through. Each step shows a
caption, Space goes on, Escape stops.

It came out of fulcro-app's Playwright harness (`playwright.session`, the `step!` / `demo!`
forms). The plan and its reasons are in `~/programs/PW_DEMO_LIBRARY.md`.

## Layout

| Path | What |
|---|---|
| `/src/step_thru/core.clj` | The core: `step!`, `demo!`, `remove-overlay!`. Interop only, no imports |
| `/src/step_thru/record.clj` | `record!`: runs a demo with auto-advance in a fresh context that records video. Imports Playwright |
| `/test/step_thru/core_test.clj` | Headless Chromium tests |
| `/README.md` | The public face: pitch, example, options |

## Design

- **No app knowledge.** The caller passes the page (`:page`, a `Page` or a fn returning
  one), the vars to wrap (`:vars`) and an optional `:on-phase` callback. fulcro-app uses
  `:on-phase` to write the marker file that mdserve's dashboard reads.
- **Playwright is the caller's dependency.** The library reaches the `Page` by interop
  only, so it pins no Playwright version. The `:test` alias brings in Playwright 1.58.0.
- **One global "running demo" atom.** `step!` is a no-op while it is nil, so tests carry
  `step!` captions permanently.
- **Two uses of one script.** A person steps through it (Space, Escape), or `record!`
  runs it with `:auto-advance-ms` and saves a video for user docs. With auto-advance there
  is no "press SPACE to begin" step and no finished caption.
- **Video resolution is the viewport's.** Playwright records at 1x: a larger
  `recordVideoSize` only pads the frame with grey (checked 2026-10-30). So a phone video
  is 390 × 844.
- **Page-side names** are `#step-thru-overlay`, `#step-thru-caption`,
  `window.__stepThruState` and `window.__stepThruListener`.

## Tests

`clojure -M:test` from the repo root. A script in the test page plays the presenter: it
records each caption as it starts to wait, then sends a real keydown (Space, or Escape on
the caption named in `window.__abortOn`). Playwright prints a host-dependency warning on
Fedora; it does not affect the run.

## Users

- **fulcro-app**: switched over 2026-10-30, as a git dependency. `playwright.session`
  keeps an adapter `demo!` that passes its page, its default vars and an `:on-phase` hook.
- **POSES family**: not yet using it.
