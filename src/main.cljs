(ns main
  (:require
   [reagent.dom.client :as rdc]
   [re-frame.core :as rf]
   [zprint.core :as zp]
   [session]
   [re-frame.db :refer [app-db]]))

(def events-
  [{:n :session}
   {:n :message}])

(doseq [{:keys [n s e]} events-]
  (rf/reg-sub n (or s (fn [db [n']] (get db n'))))
  (rf/reg-event-db n (or e (fn [db [_ e]] (assoc db n e)))))

(defn debug-app-db []
  [:pre
   (some-> app-db
           deref
           (zp/zprint-str {:style :justified}))])

(defn create-session []
  {:session-valid-from (js/Date.)
   :session-max-age    30})

(rf/reg-event-db
 :login
 (fn [db _]
   (-> db
       (assoc :session (create-session))
       (dissoc :message))))

(rf/reg-event-db
 :logout
 (fn [db _]
   (dissoc db :session)))

(defn root []
  (let [session?         @(rf/subscribe [:session])
        message          @(rf/subscribe [:message])
        renew-session? @(rf/subscribe [::session/renew-session?])]
    [:<>
     [debug-app-db]
     (when message
       [:<>
        [:hr]
        [:p message]])
     [:hr]
     [:div {:style {:display "flow"}}
      [:button
       {:on-click #(rf/dispatch [(if session? :logout :login)])}
       (if session? "Logout" "Login")]
      (when renew-session?
        [:button
         {:on-click #(rf/dispatch [:login])}
         "Renew session"])]]))

(rf/reg-event-db
 ::init
 (constantly {}))

(defonce root-container
  (rdc/create-root (js/document.getElementById "app")))

(defn init
  []
  (rf/dispatch-sync [::init])
  (rf/dispatch [::session/start-tick])
  (rdc/render root-container [root]))
