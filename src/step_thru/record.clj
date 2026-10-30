(ns step-thru.record
  "Records a step-through as a video, for user docs. The demo advances by
   itself, so nobody needs to be at the keyboard, and the screen can be a
   phone's."
  (:require [step-thru.core :as st])
  (:import [com.microsoft.playwright Browser$NewContextOptions]
           [java.nio.file Files Paths]
           [java.nio.file.attribute FileAttribute]))

(def phone
  "A phone screen: an iPhone 14's viewport, pixel density, touch and user agent."
  {:viewport [390 844]
   :scale 3
   :mobile? true
   :touch? true
   :user-agent "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"})

(def desktop
  "A desktop screen, 1280 by 720."
  {:viewport [1280 720]})

(defn- context-options
  "The video is the viewport's size: Playwright records at 1x, and a larger
   size only pads the frame with grey."
  [{[w h] :viewport :keys [scale mobile? touch? user-agent]} video-dir]
  (cond-> (doto (Browser$NewContextOptions.)
            (.setViewportSize (int w) (int h))
            (.setRecordVideoDir video-dir)
            (.setRecordVideoSize (int w) (int h)))
    scale (.setDeviceScaleFactor (double scale))
    mobile? (.setIsMobile true)
    touch? (.setHasTouch true)
    user-agent (.setUserAgent user-agent)))

(defn record!
  "Runs test-fn as a demo in a fresh browser context that records video, and
   saves the video (WebM) to file. test-fn takes the new page, and starts from
   a blank page, so it navigates and signs in itself. opts:

     :browser          a Playwright Browser. Required.
     :file             where to save the video, e.g. \"videos/sign-in.webm\". Required.
     :device           phone (the default) or desktop, or a map of the same shape.
     :auto-advance-ms  how long each caption stays before the demo goes on.
                       Default 2500.
     :vars             as for step-thru.core/demo!.

   Returns file. A failure or abort still saves the video (it ends on the
   failure caption) and then rethrows."
  [{:keys [browser file device auto-advance-ms vars]
    :or {device phone auto-advance-ms 2500}}
   test-fn]
  (let [video-dir (Files/createTempDirectory "step-thru" (make-array FileAttribute 0))
        context (.newContext browser (context-options device video-dir))
        page (.newPage context)]
    (try
      (st/demo! {:page page :vars (or vars []) :auto-advance-ms auto-advance-ms}
                #(test-fn page))
      file
      (finally
        (.close context)
        (let [video (.video page)
              target (Paths/get file (make-array String 0))]
          (some-> (.getParent target) (Files/createDirectories (make-array FileAttribute 0)))
          (.saveAs video target)
          (.delete video))))))
