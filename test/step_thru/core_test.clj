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
   window.__abortOn = null;
   window.__press = true;
   setInterval(function () {
     var s = document.getElementById('step-thru-caption');
     var st = window.__stepThruState;
     if (!s || !st || s.style.color !== 'rgb(249, 250, 251)') { return; }
     if (st.armedAt !== window.__lastArmed) {
       window.__lastArmed = st.armedAt;
       window.__seen.push(s.textContent);
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
