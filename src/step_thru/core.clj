(ns step-thru.core
  "A runbook for the UI. Wrap a Playwright test in demo! and it becomes a live
   walkthrough: a caption at each step, SPACE to go on, ESCAPE to stop.

   Needs only a Playwright Java Page, reached by interop; the caller supplies
   Playwright itself.")

(defonce ^:private active
  ;; The running demo's options (see demo!), or nil when no demo runs.
  ;; step! is a no-op while this is nil.
  (atom nil))

(defn- page
  "The running demo's page. :page is either a Page or a no-arg fn (or var)
   returning one, called afresh at each use so a restarted browser is picked up."
  []
  (let [p (:page @active)]
    (if (ifn? p) (p) p)))

(defn- phase!
  "Tells the caller's :on-phase fn (if any) the demo's phase: :stepping,
   :awaiting-dismiss or :done. Swallows everything - reporting the phase must
   never affect the demo itself."
  [phase]
  (when-let [f (:on-phase @active)]
    (try
      (f phase)
      (catch Exception _ nil))))

(def ^:private inject-js
  (str "cap => {"
       " if (!window.__stepThruState) { window.__stepThruState = {advance: false, abort: false}; }"
       " window.__stepThruState.advance = false;"
       " window.__stepThruState.armedAt = Date.now();"
       " if (!window.__stepThruListener) {"
       "   window.__stepThruListener = function(e) {"
       "     if (e.repeat) { return; }"
       "     if (e.code === 'Space') {"
       "       e.preventDefault(); e.stopPropagation();"
       "       if (Date.now() - (window.__stepThruState.armedAt || 0) >= 400) { window.__stepThruState.advance = true; }"
       "     }"
       "     if (e.code === 'Escape') { window.__stepThruState.abort = true; }"
       "   };"
       "   window.addEventListener('keydown', window.__stepThruListener, true);"
       " }"
       " var o = document.getElementById('step-thru-overlay');"
       " if (!o) {"
       "   o = document.createElement('div');"
       "   o.id = 'step-thru-overlay';"
       "   document.body.appendChild(o);"
       " }"
       " o.style.cssText = 'position:fixed;left:0;right:0;top:0;z-index:2147483647;pointer-events:none;background:none;font:600 18px/1.4 system-ui,sans-serif;padding:12px 0;text-align:center;display:block;';"
       " var s = document.getElementById('step-thru-caption');"
       " if (!s) {"
       "   s = document.createElement('span');"
       "   s.id = 'step-thru-caption';"
       "   o.appendChild(s);"
       " }"
       " s.style.cssText = 'display:inline-block;max-width:calc(100% - 24px);box-sizing:border-box;background:rgba(17,24,39,0.85);color:#f9fafb;padding:10px 18px;border-radius:6px;';"
       " s.textContent = cap;"
       "}"))

(def ^:private poll-js
  (str "() => { var s = window.__stepThruState;"
       " return s ? (s.abort ? 'abort' : (s.advance ? 'advance' : 'wait')) : null; }"))

(defn remove-overlay!
  "Removes the caption overlay from page, disarms the keydown listener (so SPACE
   stops being swallowed once the demo is over) and clears the page-side state,
   so a stale abort/advance flag cannot leak into the next run. Safe to call
   when none of them exist. demo! calls it in its finally; call it by hand only
   to tidy a page after a JVM crash mid-demo."
  [page]
  (try
    (.evaluate page
               (str "var o = document.getElementById('step-thru-overlay'); if (o) { o.remove(); }"
                    " if (window.__stepThruListener) { window.removeEventListener('keydown', window.__stepThruListener, true); window.__stepThruListener = null; }"
                    " window.__stepThruState = null;"
                    " undefined"))
    (catch Exception _ nil)))

(defn step!
  "Step gate, taking a caption for the audience. When no demo runs (a normal
   test run) it returns nil at once, so a test can carry step! captions
   permanently at no cost. Inside demo!: injects the caption overlay and a
   keydown listener into the page (again at EVERY step, and self-healed
   mid-poll, because a page navigation wipes them), shows the caption, and
   polls until the human presses SPACE (advance; preventDefault keeps the page
   from scrolling) or ESCAPE (abort - throws a clean :demo-aborted ex-info;
   NEVER Ctrl-C a demo run from a REPL, interrupting a hung Playwright call
   corrupts the nREPL session). A SPACE within 400ms of the caption appearing
   is ignored, so a double-tap cannot fly through back-to-back steps. On
   advance the caption stays, turning light green while the step's work runs;
   only demo!'s finally removes it. The poll carries its own 30-minute cap that
   throws a named :demo-step-timeout instead of letting an nREPL transport time
   out silently.

   The optional second arity takes timeout-ms: after that long with no SPACE
   the step returns nil instead of waiting on. Mid-demo steps never pass it
   (waiting on the human is the point); the terminal caption passes 60000 so it
   dismisses itself rather than holding the REPL until someone presses SPACE.

   When the demo has :auto-advance-ms, the step advances by itself after that
   long, as if SPACE were pressed. SPACE and ESCAPE still work."
  ([caption] (step! caption nil))
  ([caption timeout-ms]
   (when-let [{:keys [auto-advance-ms]} @active]
     (let [p (page)]
       (.evaluate p inject-js caption)
       (loop [waited 0]
         (let [state (.evaluate p poll-js)]
           (cond
             (nil? state)
             (do (.evaluate p inject-js caption)
                 (Thread/sleep 150)
                 (recur (+ waited 150)))

             (= "abort" state)
             (throw (ex-info "demo aborted" {:reason :demo-aborted :caption caption}))

             (or (= "advance" state)
                 (and auto-advance-ms (>= waited auto-advance-ms)))
             (do (.evaluate p
                            (str "cap => { window.__stepThruState.advance = false;"
                                 " var s = document.getElementById('step-thru-caption');"
                                 " if (s) { s.textContent = cap; s.style.color = '#86efac'; }"
                                 "}")
                            caption)
                 nil)

             (and timeout-ms (>= waited timeout-ms))
             nil

             (>= waited 1800000)
             (throw (ex-info "demo step timed out waiting for SPACE"
                             {:reason :demo-step-timeout :caption caption :waited-ms waited}))

             :else
             (do (Thread/sleep 150)
                 (recur (+ waited 150))))))))))

(defn- finish!
  "Shows a terminal caption (finished/aborted/failed) and waits for one last
   SPACE - or 60 seconds, whichever comes first - so the audience sees how the
   run ended and the presenter can stop the recorder before the overlay goes,
   without a finished demo holding the REPL for ever. Clears the page-side
   abort flag first, since after an ESCAPE step! would otherwise rethrow at
   once. Swallows everything - a failure to show the outcome must not mask the
   run's own result or exception."
  [caption]
  (try
    (phase! :awaiting-dismiss)
    (.evaluate (page) "if (window.__stepThruState) { window.__stepThruState.advance = false; window.__stepThruState.abort = false; } undefined")
    (step! caption 60000)
    (catch Exception _ nil)))

(defn demo!
  "Runs test-fn as a step-through demonstration. opts:

     :page      a Playwright Page, or a no-arg fn (or var) returning one. Required.
     :vars      vars to wrap, so each call pauses first with the fn's name as
                its caption. Default []: only the test's own step! calls pause.
     :on-phase  optional fn called with :stepping, :awaiting-dismiss and :done,
                for a dashboard that wants to show how the demo stands.
     :auto-advance-ms
                optional. Each step goes on by itself after this long, so the
                demo runs with nobody at the keyboard - for recording a video
                (see step-thru.record). There is then no press-SPACE-to-begin
                step and no finished caption: the last step's work stays on
                screen for this long instead. A failure or abort still shows
                its caption, for this long.

   Arms every step! inside the test, wraps :vars with with-redefs (no edits to
   the test or its helpers), and shows a press-SPACE-to-begin step first so the
   presenter can start a screen recorder. Brings the page to the front and
   resets the page-side state first, so a stale abort flag from an earlier
   ESCAPE cannot kill the new run - but keys go to whatever has OS focus, so if
   SPACE does nothing, click the page. A wrapped fn that calls another wrapped
   fn pauses twice; that is expected. Pass a test's helpers in :vars, or give
   it its own step! captions, not both, or every helper call pauses twice.

   Ends with a terminal caption (finished with the test's return value,
   aborted, or failed with the error message) that waits for one last SPACE,
   dismissing itself after 60 seconds. Returns test-fn's value, or rethrows
   its exception (ESCAPE throws :demo-aborted). The finally removes the
   overlay either way."
  [{:keys [vars auto-advance-ms] :as opts} test-fn]
  (when-not (:page opts)
    (throw (ex-info "step-thru/demo! needs :page" {:opts opts})))
  (reset! active opts)
  (try
    (phase! :stepping)
    (let [p (page)]
      (.bringToFront p)
      (.evaluate p "window.__stepThruState = {advance: false, abort: false}; undefined"))
    (when-not auto-advance-ms
      (step! "Demo armed - press SPACE to begin"))
    (let [result (with-redefs-fn
                   (into {} (map (fn [v]
                                   (let [orig @v
                                         cap (str (:name (meta v)))]
                                     [v (fn [& args] (step! cap) (apply orig args))]))
                                 vars))
                   test-fn)
          shown (let [s (pr-str result)]
                  (if (> (count s) 200) (str (subs s 0 200) "…") s))]
      (if auto-advance-ms
        (Thread/sleep (long auto-advance-ms))
        (finish! (str "Demo finished - " shown)))
      result)
    (catch Throwable e
      (finish! (if (= :demo-aborted (:reason (ex-data e)))
                 "Demo aborted"
                 (str "Demo failed - " (ex-message e))))
      (throw e))
    (finally
      (remove-overlay! (try (page) (catch Exception _ nil)))
      (phase! :done)
      (reset! active nil))))
