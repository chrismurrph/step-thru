(ns step-thru.core-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [step-thru.core :as st]
            [step-thru.record :as rec])
  (:import [com.microsoft.playwright Playwright BrowserType$LaunchOptions]))

;; A headless browser plays the presenter: a script in the page records each
;; caption as it starts waiting, then presses SPACE (or ESCAPE on the caption
;; named in window.__abortOn) with real keydown events, so the demo runs
;; through the same listener a human's key presses go through.

(def ^:private presenter-html
  "<html><body><p>step-thru test page</p><script>
   window.__seen = [];
   window.__pos = [];
   window.__look = [];
   window.__abortOn = null;
   window.__press = true;
   setInterval(function () {
     var s = document.getElementById('step-thru-caption');
     var st = window.__stepThruState;
     if (!s || !st || s.style.color !== 'rgb(249, 250, 251)') { return; }
     if (st.armedAt !== window.__lastArmed) {
       window.__lastArmed = st.armedAt;
       window.__seen.push(s.textContent);
       var r = s.getBoundingClientRect();
       window.__pos.push([Math.round(r.top), Math.round(r.bottom), window.innerHeight]);
       window.__look.push([s.style.fontStyle, s.style.background, s.querySelectorAll('.step-thru-mark').length]);
     }
     if (!window.__press) { return; }
     var code = (s.textContent === window.__abortOn) ? 'Escape' : 'Space';
     window.dispatchEvent(new KeyboardEvent('keydown', {code: code, bubbles: true}));
   }, 100);
   </script></body></html>")

(def ^:dynamic *browser* nil)

(use-fixtures :once
  (fn [run]
    (with-open [pw (Playwright/create)]
      (binding [*browser* (.launch (.chromium pw) (doto (BrowserType$LaunchOptions.) (.setHeadless true)))]
        (run)))))

(defn- fresh-page []
  (doto (.newPage *browser*)
    (.setContent presenter-html)))

(defn- seen [page]
  (vec (.evaluate page "() => window.__seen")))

(defn- greet [n] (str "hello " n))

(deftest step-outside-a-demo-is-a-no-op
  (let [p (fresh-page)]
    (is (nil? (st/step! "nothing happens")))
    (is (nil? (.evaluate p "() => document.getElementById('step-thru-overlay')")))))

(deftest explicit-captions-step-through-in-order
  (let [p (fresh-page)
        phases (atom [])]
    (is (= :ok (st/demo! {:page p :on-phase #(swap! phases conj %)}
                         (fn []
                           (st/step! "one")
                           (st/step! "two")
                           :ok))))
    (is (= ["Demo armed - press SPACE to begin" "one" "two" "Demo finished - :ok"]
           (seen p)))
    (testing "phases reach the caller in order"
      (is (= [:stepping :awaiting-dismiss :done] @phases)))
    (testing "the finally leaves the page clean"
      (is (nil? (.evaluate p "() => document.getElementById('step-thru-overlay')")))
      (is (nil? (.evaluate p "() => window.__stepThruState")))
      (is (nil? (.evaluate p "() => window.__stepThruListener"))))))

(deftest wrapped-vars-pause-with-their-names
  (let [p (fresh-page)]
    (is (= ["hello a" "hello b"]
           (st/demo! {:page (fn [] p) :vars [#'greet]}
                     (fn [] [(greet "a") (greet "b")]))))
    (is (= ["Demo armed - press SPACE to begin" "greet" "greet"
            "Demo finished - [\"hello a\" \"hello b\"]"]
           (seen p)))))

(deftest escape-aborts-cleanly
  (let [p (fresh-page)]
    (.evaluate p "window.__abortOn = 'two'")
    (let [e (try
              (st/demo! {:page p} (fn [] (st/step! "one") (st/step! "two") (st/step! "three") :ok))
              nil
              (catch clojure.lang.ExceptionInfo e e))]
      (is (= :demo-aborted (:reason (ex-data e)))))
    (is (= ["Demo armed - press SPACE to begin" "one" "two" "Demo aborted"] (seen p)))
    (is (nil? (st/step! "after the demo, step! is a no-op again")))))

(deftest a-failing-test-shows-its-error-and-rethrows
  (let [p (fresh-page)]
    (is (thrown-with-msg? Exception #"boom"
                          (st/demo! {:page p} (fn [] (st/step! "one") (throw (Exception. "boom"))))))
    (is (= ["Demo armed - press SPACE to begin" "one" "Demo failed - boom"] (seen p)))))

(deftest page-is-required
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"needs :page"
                        (st/demo! {} (fn [] :never)))))

(deftest auto-advance-needs-no-keys
  (let [p (fresh-page)]
    (.evaluate p "window.__press = false")
    (is (= :ok (st/demo! {:page p :auto-advance-ms 300}
                         (fn [] (st/step! "one") (st/step! "two") :ok))))
    (testing "no begin or finished caption when advancing by itself"
      (is (= ["one" "two"] (seen p))))))

(deftest record-saves-a-phone-video
  (let [file (str (System/getProperty "java.io.tmpdir") "/step-thru-test/phone.webm")]
    (.delete (java.io.File. file))
    (is (= [file ["one" "two"]]
           (let [captions (atom nil)]
             [(rec/record! {:browser *browser* :file file :auto-advance-ms 300}
                           (fn [page]
                             (.setContent page presenter-html)
                             (.evaluate page "window.__press = false")
                             (st/step! "one")
                             (st/step! "two")
                             (reset! captions (seen page))))
              @captions])))
    (is (pos? (.length (java.io.File. file))))))

(deftest each-step-places-its-caption
  (let [p (fresh-page)]
    (st/demo! {:page p}
              (fn []
                (st/step! "top")
                (st/step! "bottom" {:at :bottom})
                (st/step! "middle" {:at :middle})
                (st/step! "a quarter down" {:at 25})))
    ;; [caption top, caption bottom, screen height] per caption, after the begin caption
    (let [[top bottom middle quarter] (->> (.evaluate p "() => window.__pos") (map vec) rest)
          near? (fn [a b] (<= (abs (- a b)) 2))]
      (testing ":top is the default"
        (is (< (first top) 30)))
      (testing ":bottom ends at the bottom edge, less the overlay's 12px padding"
        (is (near? (+ (second bottom) 12) (nth bottom 2))))
      (testing ":middle is centred"
        (is (near? (/ (+ (first middle) (second middle)) 2) (/ (nth middle 2) 2))))
      (testing "25 is a quarter of the way down"
        (is (< (first top) (first quarter) (first middle)))))))

(deftest a-tap-shows-a-circle-where-it-lands
  (let [p (fresh-page)
        circles #(vec (.evaluate p "() => Array.from(document.querySelectorAll('.step-thru-tap')).map(d => { var r = d.getBoundingClientRect(); return [Math.round(r.left + r.width / 2), Math.round(r.top + r.height / 2)]; })"))]
    (st/demo! {:page p}
              (fn []
                (st/step! "tap the text")
                (let [box (.boundingBox (.locator p "p"))
                      centre [(Math/round (+ (.x box) (/ (.width box) 2)))
                              (Math/round (+ (.y box) (/ (.height box) 2)))]]
                  (.click (.locator p "p"))
                  (testing "a circle appears, centred on the click"
                    (is (= [centre] (mapv (partial mapv long) (circles)))))
                  (Thread/sleep 1200)
                  (testing "and is gone after a second"
                    (is (= [] (circles)))))))))

(deftest marked-labels-keep-their-colours
  (let [p (fresh-page)
        spans #(mapv vec (.evaluate p "() => Array.from(document.querySelectorAll('#step-thru-caption .step-thru-mark')).map(e => [e.textContent, e.style.color])"))
        caption-text #(.evaluate p "() => document.getElementById('step-thru-caption').textContent")]
    (st/demo! {:page p
               :marks [{:open "[" :close "]" :color "rgb(253, 224, 71)"}
                       {:open "“" :close "”" :color "rgb(96, 165, 250)"}]}
              (fn []
                (st/step! "Tap [BILL (3)], then “Bill Out” [unclosed")
                (testing "each marked label is its own span in its colour, marks included"
                  (is (= [["[BILL (3)]" "rgb(253, 224, 71)"] ["“Bill Out”" "rgb(96, 165, 250)"]]
                         (spans))))
                (testing "the caption reads as written, an unclosed mark left plain"
                  (is (= "Tap [BILL (3)], then “Bill Out” [unclosed" (caption-text))))))))

(deftest a-marked-label-points-at-its-control
  (let [p (fresh-page)
        spans #(mapv str (.evaluate p "() => Array.from(document.querySelectorAll('#step-thru-caption .step-thru-mark')).map(e => e.textContent)"))]
    (.evaluate p "document.body.insertAdjacentHTML('beforeend', '<button style=\"position:fixed;top:5px;left:5px\">Top Btn</button><button style=\"position:fixed;bottom:5px;left:5px\">Low Btn</button>')")
    (st/demo! {:page p :marks [{:open "“" :close "”" :color "rgb(96, 165, 250)"}]}
              (fn []
                (st/step! "Tap “Top Btn”, then “Low Btn”, not “Missing”" {:at :middle})
                (is (= ["“Top Btn”▲" "“Low Btn”▼" "“Missing”"] (spans)))))))

(deftest a-held-tap-keeps-its-circle-until-let-go
  (let [p (fresh-page)
        circles #(long (.evaluate p "() => document.querySelectorAll('.step-thru-tap').length"))
        clicked #(boolean (.evaluate p "() => !!window.__clicked"))]
    (.evaluate p "document.querySelector('p').addEventListener('click', () => { window.__clicked = true; })")
    (st/demo! {:page p}
              (fn []
                (st/step! "tap the text")
                (let [tapping (future (st/tap! (.locator p "p") 1500))]
                  (Thread/sleep 1200)
                  (testing "while the press is held: the circle shows, and the app has not had the tap yet"
                    (is (= 1 (circles)))
                    (is (false? (clicked))))
                  @tapping
                  (testing "after the press: the app has the tap"
                    (is (true? (clicked))))
                  (Thread/sleep 800)
                  (testing "and the circle is gone soon after"
                    (is (= 0 (circles)))))))))

(deftest an-event-looks-different-and-takes-longer
  (let [p (fresh-page)
        timed (fn [f] (let [t0 (System/currentTimeMillis)] (f) (- (System/currentTimeMillis) t0)))]
    (.evaluate p "window.__press = false")
    (st/demo! {:page p :auto-advance-ms 400 :marks [{:open "“" :close "”" :color "rgb(96, 165, 250)"}]}
              (fn []
                (let [step-ms (timed #(st/step! "Tap “Pay”"))
                      event-ms (timed #(st/event! "The customer hands over “1000”"))
                      long-ms (timed #(st/event! "Later, the customer asks for the bill" {:ms 1200}))]
                  (testing "a step shows marks; an event has its own look and none"
                    (is (= [["" "rgba(17, 24, 39, 0.85)" 1]
                            ["italic" "rgba(146, 64, 14, 0.92)" 0]
                            ["italic" "rgba(146, 64, 14, 0.92)" 0]]
                           (mapv vec (.evaluate p "() => window.__look")))))
                  (testing "an event stays one and a half times as long as a step, or its own :ms"
                    (is (< step-ms 600))
                    (is (<= 600 event-ms 900))
                    (is (<= 1200 long-ms 1500))))))))
