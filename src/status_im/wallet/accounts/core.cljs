(ns status-im.wallet.accounts.core
  (:require [re-frame.core :as re-frame]
            [status-im.constants :as constants]
            [status-im.ethereum.core :as ethereum]
            [status-im.ethereum.eip55 :as eip55]
            [status-im.ethereum.eip681 :as eip681]
            [status-im.ethereum.json-rpc :as json-rpc]
            [status-im.i18n :as i18n]
            [status-im.multiaccounts.update.core :as multiaccounts.update]
            [status-im.native-module.core :as status]
            [status-im.ui.components.colors :as colors]
            [status-im.ui.components.list-selection :as list-selection]
            [status-im.ui.screens.navigation :as navigation]
            [status-im.utils.fx :as fx]
            [status-im.utils.types :as types]
            [status-im.wallet.core :as wallet]
            [clojure.string :as string]
            [status-im.utils.security :as security]
            [status-im.multiaccounts.recover.core :as recover]
            [status-im.ethereum.mnemonic :as mnemonic]))

(fx/defn start-adding-new-account
  {:events [:wallet.accounts/start-adding-new-account]}
  [{:keys [db] :as cofx} {:keys [type] :as add-account}]
  (let [{:keys [latest-derived-path]} (:multiaccount db)
        path-num (inc latest-derived-path)
        account  (merge
                  {:color (rand-nth colors/account-colors)}
                  (when (= type :generate)
                    {:name (str "Account " path-num)}))]
    (fx/merge cofx
              {:db (assoc db :add-account (assoc add-account :account account))}
              (navigation/navigate-to-cofx :add-new-account nil))))

(fx/defn new-account-error
  {:events [::new-account-error]}
  [{:keys [db]} error-key error]
  {:db (update db :add-account merge {error-key error
                                      :step nil})})

(defn account-stored [path type]
  (fn [result]
    (let [{:keys [error publicKey address]} (types/json->clj result)]
      (if error
        (re-frame/dispatch [::new-account-error :account-error error])
        (re-frame/dispatch [:wallet.accounts/account-stored
                            {:address    address
                             :public-key publicKey
                             :type       type
                             :path       path}])))))

(def dec-pass-error "could not decrypt key with given password")

(defn normalize-path [path]
  (if (string/starts-with? path "m/")
    (str constants/path-wallet-root
         "/" (last (string/split path "/")))
    path))

(defn derive-and-store-account [path hashed-password type]
  (fn [value]
    (let [{:keys [id error]} (types/json->clj value)]
      (if error
        (re-frame/dispatch [::new-account-error :password-error error])
        (status/multiaccount-derive-addresses
         id
         [path]
         (fn [_]
           (status/multiaccount-store-derived
            id
            [path]
            hashed-password
            (fn [result]
              (let [{:keys [error] :as result} (types/json->clj result)
                    {:keys [publicKey address]} (get result (keyword path))]
                (if error
                  (re-frame/dispatch [::new-account-error :account-error error])
                  (re-frame/dispatch
                   [:wallet.accounts/account-stored
                    {:address    address
                     :public-key publicKey
                     :type       type
                     :path       (normalize-path path)}])))))))))))

(def pass-error "cannot retrieve a valid key for a given account: could not decrypt key with given password")

(defn store-account [path hashed-password type]
  (fn [value]
    (let [{:keys [id error]} (types/json->clj value)]
      (if error
        (re-frame/dispatch [::new-account-error
                            (if (= error pass-error) :password-error :account-error)
                            error])
        (status/multiaccount-store-account
         id
         hashed-password
         (account-stored path type))))))

(re-frame/reg-fx
 ::verify-password
 (fn [{:keys [address hashed-password]}]
   (status/verify
    address hashed-password
    #(re-frame/dispatch [:wallet.accounts/add-new-account-password-verifyied % hashed-password]))))

(re-frame/reg-fx
 ::generate-account
 (fn [{:keys [derivation-info hashed-password]}]
   (let [{:keys [address path]} derivation-info]
     (status/multiaccount-load-account
      address
      hashed-password
      (derive-and-store-account path hashed-password :generated)))))

(re-frame/reg-fx
 ::import-account-seed
 (fn [{:keys [passphrase hashed-password]}]
   (status/multiaccount-import-mnemonic
    (mnemonic/sanitize-passphrase (security/unmask passphrase))
    ""
    (derive-and-store-account constants/path-default-wallet hashed-password :seed))))

(re-frame/reg-fx
 ::import-account-private-key
 (fn [{:keys [private-key hashed-password]}]
   (status/multiaccount-import-private-key
    (string/trim (security/unmask private-key))
    (store-account constants/path-default-wallet hashed-password :key))))

(fx/defn generate-new-account
  [{:keys [db]} hashed-password]
  (let [wallet-root-address (get-in db [:multiaccount :wallet-root-address])
        path-num            (inc (get-in db [:multiaccount :latest-derived-path]))]
    {:db                (assoc-in db [:add-account :step] :generating)
     ::generate-account {:derivation-info (if wallet-root-address
                                            ;; Use the walllet-root-address for stored on disk keys
                                            ;; This needs to be the RELATIVE path to the key used to derive
                                            {:path    (str "m/" path-num)
                                             :address wallet-root-address}
                                            ;; Fallback on the master account for keycards, use the absolute path
                                            {:path    (str constants/path-wallet-root "/" path-num)
                                             :address (get-in db [:multiaccount :address])})
                         :hashed-password hashed-password}}))

(fx/defn import-new-account-seed
  [{:keys [db]} passphrase hashed-password]
  {:db                         (assoc-in db [:add-account :step] :generating)
   ::recover/validate-mnemonic [(security/safe-unmask-data passphrase)
                                #(re-frame/dispatch [:wallet.accounts/seed-validated
                                                     % passphrase hashed-password])]})

(fx/defn new-account-seed-validated
  {:events [:wallet.accounts/seed-validated]}
  [cofx phrase-warnings passphrase hashed-password]
  (let [error (:error (types/json->clj phrase-warnings))]
    (if-not (string/blank? error)
      (new-account-error cofx :account-error error)
      {::import-account-seed {:passphrase      passphrase
                              :hashed-password hashed-password}})))

(fx/defn import-new-account-private-key
  [{:keys [db]} private-key hashed-password]
  {:db                          (assoc-in db [:add-account :step] :generating)
   ::import-account-private-key {:private-key     private-key
                                 :hashed-password hashed-password}})

(fx/defn save-new-account
  [{:keys [db] :as cofx}]
  (let [{:keys [latest-derived-path]} (:multiaccount db)
        {:keys [account type]} (:add-account db)
        accounts (:multiaccount/accounts db)
        new-accounts (conj accounts account)]
    (when account
      (fx/merge cofx
                {::json-rpc/call [{:method     "accounts_saveAccounts"
                                   :params     [[account]]
                                   :on-success #()}]
                 :db (-> db
                         (assoc :multiaccount/accounts new-accounts)
                         (dissoc :add-account))}
                (when (= type :generate)
                  (multiaccounts.update/multiaccount-update
                   :latest-derived-path (inc latest-derived-path)
                   {}))))))

(fx/defn account-generated
  {:events [:wallet.accounts/account-stored]}
  [{:keys [db] :as cofx} {:keys [address] :as account}]
  (let [accounts (:multiaccount/accounts db)]
    (if (some #(when (= (:address %) address) %) accounts)
      (new-account-error cofx :account-error (i18n/label :t/account-exists-title))
      (fx/merge cofx
                {:db (update-in db [:add-account :account] merge account)}
                (save-new-account)
                (wallet/update-balances nil)
                (wallet/update-prices)
                (navigation/navigate-back)))))

(fx/defn add-watch-account
  [{:keys [db] :as cofx}]
  (let [address (get-in db [:add-account :address])]
    (account-generated cofx {:address (eip55/address->checksum (ethereum/normalized-hex address))
                             :type    :watch})))

(fx/defn add-new-account-password-verifyied
  {:events [:wallet.accounts/add-new-account-password-verifyied]}
  [{:keys [db] :as cofx} result hashed-password]
  (let [{:keys [error]} (types/json->clj result)]
    (if (not (string/blank? error))
      (new-account-error cofx :password-error error)
      (let [{:keys [type step seed private-key]} (:add-account db)]
        (case type
          :seed
          (import-new-account-seed cofx seed hashed-password)
          :key
          (import-new-account-private-key cofx private-key hashed-password)
          nil)))))

(fx/defn add-new-account-verify-password
  [{:keys [db]} hashed-password]
  {:db               (assoc-in db [:add-account :step] :generating)
   ::verify-password {:address         (get-in db [:multiaccount :wallet-root-address])
                      :hashed-password hashed-password}})

(fx/defn add-new-account
  {:events [:wallet.accounts/add-new-account]}
  [{:keys [db] :as cofx} hashed-password]
  (let [{:keys [type step]} (:add-account db)]
    (when-not step
      (case type
        :watch
        (add-watch-account cofx)
        :generate
        (generate-new-account cofx hashed-password)
        (:seed :key)
        (add-new-account-verify-password cofx hashed-password)
        nil))))

(fx/defn save-account
  {:events [:wallet.accounts/save-account]}
  [{:keys [db] :as cofx} account {:keys [name color]}]
  (let [accounts (:multiaccount/accounts db)
        new-account  (cond-> account
                       name (assoc :name name)
                       color (assoc :color color))
        new-accounts (replace {account new-account} accounts)]
    {::json-rpc/call [{:method     "accounts_saveAccounts"
                       :params     [[new-account]]
                       :on-success #()}]
     :db (assoc db :multiaccount/accounts new-accounts)}))

(fx/defn delete-account
  {:events [:wallet.accounts/delete-account]}
  [{:keys [db] :as cofx} account]
  (let [accounts (:multiaccount/accounts db)
        new-accounts (vec (remove #(= account %) accounts))
        deleted-address (get-in account [:address])]
    (fx/merge cofx
              {::json-rpc/call [{:method     "accounts_deleteAccount"
                                 :params     [(:address account)]
                                 :on-success #()}]
               :db (-> db
                       (assoc :multiaccount/accounts new-accounts)
                       (assoc-in [:wallet :accounts deleted-address] nil))}
              (navigation/navigate-to-cofx :wallet nil))))

(fx/defn view-only-qr-scanner-result
  {:events [:wallet.add-new/qr-scanner-result]}
  [{db :db :as cofx} data _]
  (let [address (or (when (ethereum/address? data) data)
                    (:address (eip681/parse-uri data)))]
    (fx/merge cofx
              (merge {:db (-> db
                              (assoc-in [:add-account :scanned-address] address)
                              (assoc-in [:add-account :address] address))}
                     (when-not address
                       {:utils/show-popup {:title   (i18n/label :t/error)
                                           :content (i18n/label :t/invalid-address-qr-code)}}))
              (navigation/navigate-back))))

(re-frame/reg-fx
 :list.selection/open-share
 (fn [obj]
   (list-selection/open-share obj)))

(fx/defn wallet-accounts-share
  {:events [:wallet.accounts/share]}
  [_ address]
  {:list.selection/open-share {:message (eip55/address->checksum address)}})