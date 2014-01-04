(ns examples.sortable.core
  (:refer-clojure :exclude [chars])
  (:require-macros [cljs.core.async.macros :refer [go alt!]])
  (:require [cljs.core.async :as async :refer [put! chan sliding-buffer alts!]]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [clojure.string :as string]
            [goog.events :as events]
            [goog.dom :as gdom]
            [goog.style :as gstyle])
  (:import [goog.ui IdGenerator]
           [goog.events EventType]))

(enable-console-print!)

;; =============================================================================
;; Utilities

(defn guid []
  (.getNextUniqueId (.getInstance IdGenerator)))

(def chars (into [] "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"))

(defn rand-char []
  (nth chars (rand-int (count chars))))

(defn rand-word []
  (apply str (take (inc (rand-int 10)) (repeatedly rand-char))))

;; =============================================================================
;; Generic Sortable

(defn sortable-item [item owner opts]
  (om/component
    (if-not (= (:type item) ::spacer)
      (dom/li
        #js {:onMouseDown (fn [e] (println "mouse down"))
             :onMouseUp   (fn [e] (println "mouse up"))
             :onMouseMove (fn [e] (println "mouse move"))}
        (om/build (:view opts) item {:opts opts}))
      (dom/li #js {:style #js {:visibility "hidden" :height (:height item)}}))))

(defn sortable [{:keys [items sort]} owner opts]
  (reify
    om/IInitState
    (init-state [_] {:sort sort})
    om/IWillMount
    (will-mount [_]
      ;; sadly need to listen to window events, too
      (let [cancel (chan)
            [mdc muc mmc] (take 3 (repeatedly #(chan (sliding-buffer 1))))
            mouse-down #(put! mdc %)
            mouse-up   #(put! muc %)
            mouse-move #(put! mmc %)]
        (om/set-state! owner :window-listener
          [mouse-down mouse-up mouse-move])
        (doto js/window
          (events/listen EventType.MOUSEDOWN mouse-down)
          (events/listen EventType.MOUSEUP mouse-up)
          (events/listen EventType.MOUSEMOVE mouse-move))
        (go (loop []
              (let [[v c] (alts! [cancel mdc muc mmc] :priority true)]
                (if (= c cancel)
                  :ok
                  (condp = c
                    mdc (recur)
                    muc (recur)
                    mmc (recur))))))))
    om/IWillUnmount
    (will-unmount [_]
      ;; clean up window event handlers
      (let [[mouse-down mouse-up mouse-move]
            (om/get-state! owner :window-listeners)]
        (doto js/window
          (events/unlisten EventType.MOUSEDOWN mouse-down)
          (events/unlisten EventType.MOUSEUP mouse-up)
          (events/unlisten EventType.MOUSEMOVE mouse-move))))
    om/IDidUpdate
    (did-update [_ _ _ _]
      (let [cell-height (om/get-state owner :cell-height)]
        (when-not cell-height
          (let [node (om/get-node owner "list")
                xs   (gdom/getChildren node)]
            (when (pos? (alength xs))
              (om/set-state! owner :cell-height
                (.-height (gstyle/getSize (aget xs 0)))))))))
    om/IRender
    (render [_]
      (dom/div #js {:className "om-sortable"}
        (when-let [item (om/get-state owner :dragging)]
          (om/build sortable-item (items item)
            {:fn (fn [x] (assoc x :dragging true))
             :opts opts}))
        (dom/ul #js {:key "list" :ref "list"}
          (om/build-all sortable-item (om/get-state owner :sort)
            {:fn (fn [id]
                   (if (= id ::spacer)
                     {:type ::spacer :height 10}
                     (items id)))
             :key :id :opts opts}))))))

;; =============================================================================
;; Example

(def app-state
  (let [items (->> (take 10 (repeatedly guid))
                (map (fn [id] [id {:id id :text (rand-word)}]))
                (into {}))]
    (atom {:items items
           :sort (into [] (keys items))})))

(defn item [the-item owner opts]
  (om/component (dom/span nil (str "Item " (:text the-item)))))

(om/root app-state
  (fn [app owner]
    (reify
      om/IWillMount
      (will-mount [_]
        (let [{:keys [start drag stop] :as chans}
              (zipmap [:start :drag :stop]
                      (repeatedly #(chan (sliding-buffer 1))))]
          (om/set-state! owner :chans chans)
          (go (while true
                (alt!
                  start ([v c] (println "start"))
                  drag  ([v c] (println "drag"))
                  stop  ([v c] (println "stop")))))))
      om/IRender
      (render [_]
        (dom/div nil
          (dom/h2 nil "Sortable example")
          (om/build sortable app
            {:opts {:view item :chans (om/get-state owner :chans)}})))))
  (.getElementById js/document "app"))
