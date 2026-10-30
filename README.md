# step-thru

A runbook for your UI. Wrap any Playwright test in `demo!` and it becomes a live
walkthrough: a caption at each step, Space to go on, Escape to stop. No edits to the test.
Built for demos, screen recordings and showing a colleague how something works.

<!-- GIF goes here -->

## Example

```clojure
(require '[step-thru.core :as st])

(defn sign-in [page] ...)            ; your existing test helpers
(defn open-report [page name] ...)
(defn my-test [page] (sign-in page) (open-report page "Q3") :ok)

;; Every call to a helper in :vars pauses first, captioned with the helper's name.
(st/demo! {:page page :vars [#'sign-in #'open-report]}
          #(my-test page))
```

Or write the captions yourself. `step!` costs nothing outside a demo, so the captions can
stay in the test for good:

```clojure
(defn my-test [page]
  (st/step! "Sign in as the manager")
  (sign-in page)
  (st/step! "Open the Q3 report")
  (open-report page "Q3"))

(st/demo! {:page page} #(my-test page))
```

## How a demo runs

- It starts on "Demo armed - press SPACE to begin", so you can start the screen recorder.
- **Space** goes on. The caption stays white while it waits for you, and turns light green
  while the step's work runs.
- **Escape** stops the demo. `demo!` throws an ex-info with `:reason :demo-aborted`.
- At the end a caption shows the result, "Demo aborted" or the error. One more Space
  clears it, or it clears itself after 60 seconds.
- The overlay and the key listener are always removed at the end.

## Options

| Key | Meaning |
|---|---|
| `:page` | A Playwright `Page`, or a no-arg fn (or var) that returns one. Required. |
| `:vars` | Vars to wrap so that each call pauses first. Default `[]`. |
| `:on-phase` | Optional fn, called with `:stepping`, `:awaiting-dismiss` and `:done`. |
| `:auto-advance-ms` | Optional. Each step goes on by itself after this long. |

## Recording a video

The same script that you step through can make a video for your user docs. `record!` runs
the demo on a phone-sized screen, with each step going on by itself, and saves a WebM:

```clojure
(require '[step-thru.record :as rec])

(rec/record! {:browser browser :file "videos/start-an-order.webm"}
             (fn [page]
               (.navigate page "https://my-app.example/")
               (my-test page)))
```

| Key | Meaning |
|---|---|
| `:browser` | A Playwright `Browser`. Required. |
| `:file` | Where to save the video. Required. |
| `:device` | `rec/phone` (the default), `rec/desktop`, or your own map of the same shape. |
| `:auto-advance-ms` | How long each caption stays. Default 2500. |
| `:vars` | As for `demo!`. |

The test starts in a fresh browser context on a blank page, so it navigates and signs in
itself. The video is the size of the screen, 390 × 844 for `rec/phone`: Playwright records
at one pixel per CSS pixel.

## Things to know

- **Keys go to whatever has focus.** The listener is in the page. If Space does nothing,
  click once on the page.
- **Don't press Ctrl-C to stop a demo run from a REPL.** Interrupting a Playwright call
  can break the nREPL session. Press Escape instead.
- **Wrap the helpers or caption them, not both.** Otherwise every helper call pauses twice.
  A wrapped helper that calls another wrapped helper also pauses twice; that is expected.
- **A page navigation removes the caption** until the next step shows it again.
- Each step waits at most 30 minutes, then throws `:demo-step-timeout`.

## Install

Playwright for Java must already be on your classpath; step-thru adds no version of its
own.

```clojure
io.github.chrismurrph/step-thru {:git/sha "..."}
```

## Tests

```bash
clojure -M:test
```

The tests run a headless Chromium. A script in the test page plays the presenter and
presses the keys.

## Licence

MIT
