(ns session
  (:require [re-frame.alpha :as rf]
            [re-frame.db :as db]
            [re-frame.flow.alpha]))


(defn floor [n] (js/Math.floor n))

(defn seconds-until
  ([then]
   (seconds-until (js/Date.) then))
  ([now then]
   (when (and then now)
     (floor (/ (- then now) 1000)))))

(rf/reg-event-db
 ::time-now
 (fn [db [k]]
   (assoc db k (js/Date.))))

;; Taken from: https://github.com/Day8/re-frame/blob/master/docs/FAQs/PollADatabaseEvery60.md
(defonce interval-handler         ;; notice the use of defonce
  (let [live-intervals (atom {})] ;; storage for live intervals
    (fn handler [{:keys [action id frequency event]}] ;; the effect handler
      (condp = action
        :clean   (doall ;; <--- new. clean up all existing
                  (map #(handler {:action :end  :id  %1}) (keys @live-intervals)))
        :start   (swap! live-intervals assoc id (js/setInterval #(rf/dispatch event) frequency))
        :end     (do (js/clearInterval (get @live-intervals id))
                     (swap! live-intervals dissoc id))))))

(rf/reg-fx :interval interval-handler)

(comment
  (interval-handler {:action :clean})
  )

;; If `(rf/reg-global-interceptor re-frame.flow.alpha/interceptor)` is not set
;; and I evaluate this witht the REPL. It the flow starts, but behaves
;; differently then with the global inspector.
(rf/reg-flow
 {:id     ::ttl-max
  :inputs {:max-age [:session :session-max-age]}
  :output :max-age
  :path   [::ttl-max]})

(comment
  (get-in @re-frame.db/app-db [::ttl-max])
  )

(defn ends-at-
  [{:keys [valid-from ttl-max]}]
  (when (and valid-from ttl-max)
    (js/Date. (+ (.getTime valid-from) (* ttl-max 1000)))))

(rf/reg-flow
 {:doc    "Frontend session ends at, returns `inst`."
  :id     ::ends-at
  :inputs {:valid-from [:session :session-valid-from]
           :ttl-max    (rf/flow<- ::ttl-max)}
  :output ends-at-
  :path   [::ends-at]})

(comment
  (get-in @re-frame.db/app-db [::ends-at])
  )

(defn ttl-
  [{:keys [ends-at time-now]}]
  (when (and ends-at time-now)
    (seconds-until time-now ends-at)))

(rf/reg-flow
 {:doc    "Seconds left on session."
  :id     ::ttl
  :inputs {:ends-at  (rf/flow<- ::ends-at)
           :time-now [::time-now]}
  :output ttl-
  :path   [::ttl]})

(comment
  (get-in @re-frame.db/app-db [::ttl])
  )

(defn renew-session?-
  [{:keys [ttl ttl-max expired?]}]
  (when (and ttl ttl-max)
    ;; Accept to renew session after ~ half session max ttl
    (and (not expired?)
         (< ttl (floor (* 0.5 ttl-max))))))

(rf/reg-flow
 {:id     ::renew-session?
  :inputs {:ttl      (rf/flow<- ::ttl)
           :ttl-max  (rf/flow<- ::ttl-max)
           :expired? (rf/flow<- ::expired?)}
  :output renew-session?-
  :path   [::renew-session?]})

(rf/reg-sub ::renew-session? :-> ::renew-session?)

(comment
  (get-in @re-frame.db/app-db [::renew-session?])
  @(rf/subscribe [::renew-session?])
  )

(defn about-to-expire?-
  [{:keys [ttl ttl-max expired?]}]
  (when (and ttl ttl-max)
    (and (not expired?)
         (< ttl (* 0.33 ttl-max)))))

(rf/reg-flow
 {:id     ::about-to-expire?
  :inputs {:ttl      (rf/flow<- ::ttl)
           :ttl-max  (rf/flow<- ::ttl-max)
           :expired? (rf/flow<- ::expired?)}
  :output about-to-expire?-
  :path   [::about-to-expire?]})

(rf/reg-sub ::about-to-expire? :-> ::about-to-expire?)

(comment
  (get-in @re-frame.db/app-db [::about-to-expire?])
  @(rf/subscribe [::about-to-expire?])
  )

(defn expired?- [{:keys [ttl]}]
  (when ttl
    (neg-int? ttl)))

(rf/reg-flow
 {:id     ::expired?
  :inputs {:ttl (rf/flow<- ::ttl)}
  :output expired?-
  :path   [::expired?]})

(rf/reg-sub ::expired? :-> ::expired?)

(comment
  (get-in @re-frame.db/app-db [::expired?])
  @(rf/subscribe [::expired?])
  )

(rf/reg-event-fx
 ::logout-when-expired
 (fn [{:keys [db]} _]
   (when (get db ::expired?)
     {:dispatch-n [[:logout]
                   [:message "Logged out due to inactivity."]]})))

(rf/reg-event-fx
 ::about-to-expire-message
 (fn [{:keys [db]} _]
   (when (get db ::about-to-expire?)
     {:dispatch [:message "Session is about to expire."]})))

(rf/reg-event-fx
 ::on-tick
 (constantly
  {:dispatch-n [[::time-now]
                [::about-to-expire-message]
                [::logout-when-expired]]}))

(rf/reg-event-fx
 ::start-tick
 (fn [_ _]
   {:interval {:action    :start
               :id        ::tick
               :frequency 1000
               :event     [::on-tick]}}))

(comment
  (rf/dispatch [::start-tick])
  )

;; Reg flow interceptor
;; When added, the flows works as I expect them to do.
(rf/reg-global-interceptor re-frame.flow.alpha/interceptor)
