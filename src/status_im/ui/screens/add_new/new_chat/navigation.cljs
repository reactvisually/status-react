(ns status-im.ui.screens.add-new.new-chat.navigation
  (:require [status-im.ui.screens.navigation :as navigation]))

(defmethod navigation/preload-data! :new-chat
  [db _]
  (dissoc db :contacts/new-identity))
