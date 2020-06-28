(ns tsca-webapp.ledger.effects
  (:require
   [re-frame.core :as re-frame]
   [day8.re-frame.tracing :refer-macros [fn-traced]]
   ["buffer" :as buffer]
   ["@ledgerhq/hw-transport-u2f" :as u2f]
   ["@ledgerhq/logs" :as lglog]
   ["/hw-app-xtz/tezos.js" :as xtz]
   ["@ledgerhq/errors" :as ledger-error]
   [oops.core :refer [oget+]]))

(defonce _
  (lglog/listen (fn [log] (js/console.log log))))

(def scramble-key "XTZ")
(def water-mark "03")
(def tezos-path "44'/1729'/0'/0'")
(def timeout 60000)
(def tz-app (atom nil))

(defn- open-app []
  (if-let [app @tz-app]
    (js/Promise.resolve app)

    (-> (.-default u2f)
        (.open)
        (.then (fn [transport]
                 (.setScrambleKey transport scramble-key)
                 (.setUnwrap transport true)
                 (.setExchangeTimeout transport timeout)
                 (let [app (xtz/Tezos. transport buffer)]
                   (reset! tz-app app)
                   app)))
        (.catch (fn [ex]
                  (js/console.log "failure!" ex))))))

(defn- version []
  (-> (open-app)
      (.then #(.getVersion %))))

(defn- address []
  (-> (open-app)
      (.then #(.getAddress % tezos-path true))
      (.then #(.-publicKey %))))

(defn- sign [operation-text]
  (-> (open-app)
      (.then #(.signOperation % tezos-path (str water-mark operation-text) 0))
      (.then #(.-signature %))))

(defn- error-code [err-key]
  (-> ledger-error .-StatusCodes (oget+ err-key)))

(defn- parse-ledger-error [ex]
  (let [type (if-let [code (.-statusCode ex)]
               (if (= code (error-code :CONDITIONS_OF_USE_NOT_SATISFIED))
                 :denied-by-user
                 :transport-status-error)

               (if-let [err-id (.-id ex)]
                 (case err-id
                   "TransportLocked" :busy
                   "U2F_4" :time-out
                   "U2F_5" :time-out
                   :transport-error)

                 :unknown-error-completely))]
    {:type type}))

(comment
  (defn- show [p]
    (-> p
        (.then #(js/console.log "success" %))
        (.catch #(js/console.log (js/Date.) %))))
  (show (version))
  (show (sign "ef3ccca0cf0a4c1706cdd89d30f3ac3cef34fc78c23f140231c969501bc387d16c001e44b16562327dd33068e4731764191461bcccbe830af2ef25c35000a08d0600000d3c0b644fdf9081e80f73dd3a4735c5632c81da00") ))

(defn- callback [{:keys [success-id error-id]} promise]
  (-> promise
      (.then #(re-frame/dispatch [success-id %]))
      (.catch #(do
                 (js/console.error %)
                 (re-frame/dispatch [error-id (parse-ledger-error %)])))))

(re-frame/reg-fx
 :ledger-ready?
 (fn [m]
   (callback m (-> (version)
                   (.then (fn [] true))))))

(re-frame/reg-fx
 :ledger-pk
 (fn [m]
   (callback m (address))))


(re-frame/reg-fx
 :ledger-sign
 (fn [{:keys [operation-text] :as m}]
   (callback m (sign operation-text))))
