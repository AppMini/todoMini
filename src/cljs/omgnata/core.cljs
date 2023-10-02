(ns omgnata.core
  (:require [reagent.core :as reagent :refer [atom dom-node]]
            [reagent.session :as session]
            [secretary.core :as secretary :include-macros true]
            [ajax.core :refer [ajax-request json-response-format url-request-format]]
            [cljs.core.async :refer [<! chan put! timeout]]
            [goog.net.cookies]
            [goog.events :as events]
            [goog.history.EventType :as EventType])
  (:require-macros 
    [cljs.core.async.macros :refer [go]])
  (:import goog.History))

(enable-console-print!)

(def href (-> js/document .-location .-href))

(def server (atom {:url (str (-> href
                                 (.split "#") (get 0)
                                 (.split "?") (get 0)
                                 (.replace ":3449" ":8000")) "server.php")
                   :poller-time 5}))

(secretary/set-config! :prefix "#")

(defonce poller-instance (atom 0))
(defonce todo-lists (atom {}))
(defonce todo-timestamps (atom {}))
(defonce last-timestamp (atom (when (not= (.indexOf href "?demo") -1) 0)))
(defonce sorter (atom nil))
(defonce app-has-focus (atom true))

(def re-todo-finder #"[\ \t]*\*[\ \t]*\[(.*?)\]")
(def re-todo-parser #"[\ \t]*\*[\ \t]*\[(.*?)\][\ \t]*(.*?)[\n$]([\s\S]*)")
(def re-only-spaces #"^[\s\t]*$")

;; -------------------------
;; Functions

(defn no-extension [s] (.replace s ".txt" ""))

(defn get-focus [this]
  (let [node (dom-node this)
        pos (.-length (.-value node))]
    ; focus on the textbox
    (.focus node)
    ; put the cursor at the end
    (.setSelectionRange node pos pos)))

; http://stackoverflow.com/a/5980031/2131094
(defn swap-elements
  "Swap two elements in a vector."
  [v i1 i2]
  (assoc v i2 (v i1) i1 (v i2)))

(defn get-index-of [v k vl]
  (first (remove nil? (map-indexed #(when (= (%2 k) vl) %1) v))))

(defn insert-at [v idx values]
  (let [[before after] (split-at idx v)]
    (vec (concat before values after))))

(defn csrftoken [] (.get goog.net.cookies "csrftoken"))

;***** todo parsing *****;

; http://stackoverflow.com/a/18737013/2131094
(defn re-pos
  "Find all the positions in a string s that a regular expression re matches."
  [re s]
  (let [re (js/RegExp. (.-source re) "g")]
    (loop [res {}]
      (if-let [m (.exec re s)]
        (recur (assoc res (.-index m) (first m)))
        res))))

(defn split-on-todos 
  "Split up some text by positions of TODO list markers: * [ ] "
  [todo-text]
  (let [slice-positions (sort (conj
                                ; find the position of all todos within the source text
                                (vec (map #(first %) (re-pos re-todo-finder todo-text)))
                                ; add the complete text length as the final marker
                                (.-length todo-text)))]
    ; add zero as the initial marker if not present
    (if (= (first slice-positions) 0) slice-positions (into [0] slice-positions))))

(defn parse-todo-chunk
  "Parse a chunk of text into a TODO list item: * [ ] My title... "
  [todo-chunk index]
  (let [[matched checked title details] (.exec (js/RegExp. re-todo-parser) todo-chunk)]
    (if matched
      {:matched true
       :checked (nil? (.exec (js/RegExp. re-only-spaces) checked))
       :title title
       :details details
       :source todo-chunk
       :index index}
      {:matched false
       :source todo-chunk
       :index index})))

(defn extract-todos
  "Turn a chunk of text into an array of TODO list state dictionaries."
  [text]
  (when text
    (let [slice-positions (split-on-todos text)
          chunks (partition 2 1 slice-positions)
          todo-items (vec (map-indexed (fn [idx t]
                               (parse-todo-chunk (.substr text (first t) (- (last t) (first t))) idx))
                             chunks))]
      todo-items)))

(defn transform-text-todos
  "Given a hash-map of {:filename text :filename-2 text-2}
  replace the text items with their parsed TODO list state dictionaries."
  [todo-text-items]
  (into {} (map (fn [[fname todo-text]] [(no-extension fname) (extract-todos todo-text)]) todo-text-items)))

(defn reassemble-todos
  "Take an array of TODO list state dictionaries and then them back into text blob."
  [todo-items]
  (apply str (map
               #(if (% :matched)
                  (str " * [" (if (% :checked) "x" " ") "] " (% :title) "\n" (% :details))
                  (let [txt (% :source)]
                    (print (type txt))
                    (if (= (type txt) (type (js/String)))
                      txt
                      (js/console.log "Ignoring spurious TODO data:" (clj->js txt)))))
               todo-items)))

;***** Manipulating data strcutures *****;

(defn re-compute-indices [todo-items fname]
  (update-in todo-items [fname] #(vec (map-indexed (fn [idx t] (assoc t :index idx)) %))))

(defn remove-completed [todo-items fname]
  (update-in todo-items [fname] #(remove :checked %)))

(defn remove-item [todo-items fname todo]
  (update-in todo-items [fname] (fn [todo-list] (remove #(= (% :index) (todo :index)) todo-list))))

(defn re-order-todo-list [todo-list start-index destination-index]
  (loop [todo-list-updated todo-list current-index start-index]
    (let [diff (- destination-index current-index)
          new-index (+ current-index (/ diff (js/Math.abs diff)))]
      (if (not (= diff 0))
        (recur (swap-elements todo-list-updated current-index new-index) new-index)
        todo-list-updated))))

;***** Network functions *****;

(defn get-files
  "Ask the server for a list of text files.
  Server blocks if none since timestamp.
  Returns a dictionary of :filename to text mappings."
  [timestamp]
  (let [c (chan)]
    (ajax-request {:uri (@server :url)
                   :method :get
                   :with-credentials true
                   :params {:timestamp (or timestamp 0)
                            :live_for (@server :poller-time)}
                   :response-format (json-response-format)
                   :handler #(put! c %)})
    c))

(defn update-file
  "Ask the server to update a particular text file with text contents."
  [fname text]
  (ajax-request {:uri (@server :url)
                 :method :post
                 :with-credentials true
                 :headers {"X-CSRFToken" (csrftoken)}
                 :format (url-request-format)
                 :params {:filename (str fname ".txt")
                          :content text}
                 :response-format (json-response-format)
                 :handler (fn [[ok result]]
                            (print "update-file result:" ok (clj->js result))
                            (when (and ok (not (nil? result)))
                              (reset! last-timestamp result)))}))

(defn delete-file
  "Ask the server to delete a single file."
  [fname]
  ; not RESTful because PHP doesn't support DELETE parameters well
  (ajax-request {:uri (@server :url)
                 :method :post
                 :with-credentials true
                 :headers {"X-CSRFToken" (csrftoken)}
                 :format (url-request-format)
                 :params {:delete (str fname ".txt")}
                 :response-format (json-response-format)
                 :handler (fn [[ok result]]
                            (print "delete-file result:" ok (clj->js result))
                            (when (and ok (not (nil? result)))
                              (reset! last-timestamp result)))}))

(defn long-poller
  "Continuously poll the server updating the todos atom when the textfile data changes."
  [todos file-timestamps instance-id]
  (go (loop [wait 1000]
        ; if we have fired off a new instance don't use this one
        (when (= instance-id @poller-instance)
          (print "Long poller initiated:" instance-id "timestamp:" @last-timestamp)
          ; don't fire off more than 1 time per second
          (let [[ok result] (if @app-has-focus (<! (get-files @last-timestamp)) [false {:failure "App lost focus. Skipping poll."}])]
            (js/console.log "Long-poller result:" (clj->js result))
            (let [new-wait (if @app-has-focus
                             (or (if (result :failure)
                                   (do (js/console.log "Long-poller ignoring bad data.")
                                       ; set timestamp to zero to show the demo if the server sent a parse error
                                       ; and hasn't yet had a successful response
                                       (when (and (nil? @last-timestamp) (= (result :failure) :parse))
                                         (reset! last-timestamp 0))
                                       nil)
                                   (do (if (>= (result "timestamp") @last-timestamp)
                                         (do
                                           (js/console.log "Long-poller new timestamp:" (result "timestamp"))
                                           (reset! last-timestamp (result "timestamp"))
                                           #_ (when (not ok)
                                             ; this happens with the poller timeout so we can't use it d'oh
                                             )
                                           (let [transformed-todos (transform-text-todos (result "files"))
                                                 timestamps (into {} (map (fn [[fname timestamp]] [(no-extension fname) timestamp]) (result "creation_timestamps")))]
                                             (when (and ok (not (= @file-timestamps timestamps)) timestamps (> (count timestamps) 0))
                                               (js/console.log "Long-poller creation timestamps:" (clj->js timestamps))
                                               (reset! file-timestamps timestamps))
                                             (when (and ok (result "files") (not (= @todos transformed-todos)))
                                               (js/console.log "long-poller result:" @last-timestamp ok (clj->js result))
                                               (reset! todos transformed-todos))))
                                         (js/console.log "Long-poller ignoring old data:" (clj->js result)))
                                       ; reset wait time
                                       1000))
                                 (min (* wait 2) 120000))
                             2000)]
              (js/console.log "Long-poller timeout wait:" new-wait)
              (<! (timeout new-wait))
              (recur new-wait)))))))

;***** event handlers *****;

(defn checkbox-handler
  "When the user clicks a checkbox, update the state."
  [todos fname todo]
  (update-file fname (reassemble-todos
                       ((swap! todos #(-> %
                                          (update-in [fname (todo :index) :checked] not)
                                          (re-compute-indices fname)))
                        fname))))

(defn delete-item-handler [todos fname todo]
  (update-file fname (reassemble-todos
                         ((swap! todos #(-> %
                              (remove-item fname todo)
                              (re-compute-indices fname)))
                          fname))))

(defn delete-completed-handler [todos fname]
  (update-file fname (reassemble-todos
                         ((swap! todos #(-> %
                              (remove-completed fname)
                              (re-compute-indices fname)))
                          fname))))

(defn update-item-handler [todos fname todo item-title]
  (update-file fname (reassemble-todos
                       ((swap! todos #(-> %
                                          (assoc-in [fname (todo :index) :title] @item-title)
                                          (re-compute-indices fname)))
                        fname))))

(defn add-todo-item-handler [todos fname new-item-title]
  (let [todo-list (get @todos fname)
        first-matched (get-index-of todo-list :matched true)]
    (print "first-matched" first-matched)
    (update-file fname (reassemble-todos
                         ((swap! todos #(-> %
                                            (assoc-in [fname]
                                                      (insert-at todo-list
                                                                 (if (= (get-index-of todo-list :matched false) 0) 1 0)
                                                                 [{:title @new-item-title :checked false :matched true}]))
                                            (re-compute-indices fname)))
                          fname))))
  (reset! new-item-title ""))

(defn finished-sorting-handler [todos filename ev]
  (when (and (aget ev "oldIndex") (aget ev "newIndex"))
    (let [old-idx (aget ev "oldIndex")
          new-idx (aget ev "newIndex")
          el (.-item ev)
          data-index (int (.getAttribute el "data-index"))
          todo-list (get @todos filename)
          start-index (get-index-of todo-list :index data-index)
          difference (- new-idx old-idx)
          destination-index (+ start-index difference)]
      (update-file filename
                   (reassemble-todos
                     ((swap! todos #(-> %
                                        (assoc-in [filename] (re-order-todo-list todo-list start-index destination-index))
                                        (re-compute-indices filename)))
                      filename))))
    true))

(defn apply-sortable [todos filename this]
  (js/console.log "Sortable wrapping.")
  (when @sorter
    (.call (aget @sorter "destroy") @sorter))
  (reset! sorter
          (.create js/Sortable
                   (dom-node this)
                   #js {:handle ".handle"
                        :animation 150
                        :onEnd (partial finished-sorting-handler todos filename)})))

(defn add-todo-list-handler [todos new-item add-mode]
  (update-file @new-item (swap! todos assoc @new-item []))
  (reset! new-item "")
  (swap! add-mode not))

(defn delete-todo-list-handler [todos fname _add-mode ev]
  (when (js/confirm (str "Really delete " fname " list?"))
    (swap! todos dissoc fname)
    (delete-file fname))
  (.preventDefault ev))

(defn switch-to-todo [fname ev]
  (.preventDefault ev)
  (secretary/dispatch! (str "/" fname))
  (.pushState js/history nil nil (str "#" fname)))

(defn go-home [ev]
  (.preventDefault ev)
  (secretary/dispatch! "/")
  (.pushState js/history nil nil (str js/window.location.pathname js/window.location.search)))

;; -------------------------
;; Views

(defn with-focus-wrapper []
  (with-meta identity {:component-did-mount (fn [this] (get-focus this))}))

(defn component-item-edit [item-title edit-mode item-done-fn]
  [(with-focus-wrapper)
   (fn []
     [:textarea.edit-item-text {:value @item-title
                                :placeholder "Item..."
                                :on-change #(reset! item-title (-> % .-target .-value))
                                :on-key-down (fn [ev] (when (= (.-which ev) 13) (item-done-fn ev) (.preventDefault ev)))
                                :on-blur (fn [] 
                                           ; Ugh - hack
                                           (js/setTimeout #(swap! edit-mode not) 100))}])])

(defn component-item-add [item-title _edit-mode item-done-fn]
  [(with-meta
     (fn []
       [:textarea.add-item-text {:auto-focus true
                                 :value @item-title
                                 :placeholder "Item..."
                                 :on-change #(reset! item-title (-> % .-target .-value))
                                 :on-key-down (fn [ev] (when (= (.-which ev) 13) (item-done-fn ev) (.preventDefault ev)))}])
     {:component-did-update (fn [this]
                              ; only get focus if they have just created a note
                              (let [node (dom-node this)
                                    content-length (.-length (.-value node))]
                                (when (= 0 content-length) (get-focus this))))})])

(defn component-todo-item [todos filename todo]
  (let [edit-mode (atom false)
        item-title (atom (todo :title))
        item-update-fn (partial update-item-handler todos filename todo item-title)]
    (fn [idx todo parent-add-mode]
      [:li.todo-line {:key (todo :index) :data-index (todo :index) :class (str "oddeven-" (mod idx 2))}
       (if @edit-mode
         [:span.edit-mode {}
          [component-item-edit item-title edit-mode item-update-fn]
          [:i.btn.update-item-done {:on-click item-update-fn :class "fa fa-check-circle"}]]
         [:span {}
          (when @parent-add-mode [:span
                                  [:i.btn.delete-item {:on-click (partial delete-item-handler todos filename todo) :class "fa fa-minus-circle"}]
                                  [:span.btn.handle.fa-stack [:i {:class "fa fa-circle fa-stack-2x"}] [:i {:class "fa fa-bars fa-stack-1x fa-inverse"}]]])
          [:i.checkbox.btn {:on-click (partial checkbox-handler todos filename todo) :class (if (todo :checked) "fa fa-check-circle" "fa fa-circle")}] 
          [:div.todo-text {:on-double-click #(swap! edit-mode not)} (todo :title)]])])))

(defn component-list-of-todos [todos filename add-mode]
  [(with-meta
     (fn []
       [:ul {:key filename}
        (doall (map-indexed (fn [idx todo] ^{:key (todo :index)} [(partial component-todo-item todos filename todo) idx todo add-mode])
                            (filter :matched (@todos filename))))])
     {:component-did-mount (partial apply-sortable todos filename)
      :component-did-update (partial apply-sortable todos filename)})])

(defn todo-page [todos filename]
  (let [add-mode (atom false)
        new-item-title (atom "")
        item-done-fn (partial add-todo-item-handler todos filename new-item-title add-mode)]
    (fn []
      (if (nil? @last-timestamp)
        [:div#loader [:div]]
        [:div.todo-page
         [:i#back.btn {:on-click go-home :class "fa fa-chevron-circle-left"}]
         [:h3.list-title filename]
         [:span#add-item.btn {:on-click #(swap! add-mode not) :class "fa fa-stack"}
          [:i {:class "fa fa-stack-2x fa-circle"}]
          (if @add-mode [:i {:class "fa fa-stack-1x fa-times fa-inverse"}] [:i {:class "fa fa-stack-1x fa-pencil fa-inverse"}])]
         (when (and @add-mode (> (count (filter :checked (@todos filename))) 0))
           [:i#clear-completed.btn {:on-click (partial delete-completed-handler todos filename) :class "fa fa-minus-circle"}])
         (when @add-mode
           [:div#add-item-container
            [component-item-add new-item-title add-mode item-done-fn]
            [:i#add-item-done.btn {:on-click item-done-fn :class "fa fa-check-circle"}]])
         (when (and (= (count (@todos filename)) 0) (not @add-mode))
           [:div.message
            [:p "Use the pencil icon to add a list item."]])
         [component-list-of-todos todos filename add-mode]
         (when (= @last-timestamp 0) [:p.warn "No server connected."])]))))

(defn lists-page [todos timestamps]
  (let [add-mode (atom false)
        new-item (atom "")
        update-fn (partial add-todo-list-handler todos new-item add-mode)]
    (fn []
      (if (nil? @last-timestamp)
        [:div#loader [:div]]
        [:div
         [:div#list-edit-container
          [:span#add-list.btn {:on-click #(swap! add-mode not) :class "fa fa-stack"}
           [:i {:class "fa fa-stack-2x fa-circle"}]
           (if @add-mode [:i {:class "fa fa-stack-1x fa-times fa-inverse"}] [:i {:class "fa fa-stack-1x fa-pencil fa-inverse"}])]
          (when @add-mode
            [:div#add-item-container
             [:input {:auto-focus true :on-change #(reset! new-item (-> % .-target .-value)) :on-key-down #(when (= (.-which %) 13) (update-fn %)) :value @new-item :placeholder "List name..."}]
             [:i#add-item-done.btn {:on-click update-fn :class "fa fa-check-circle"}]])]
         [:ul {}
          (if (> (count @todos) 0)
            (doall (map-indexed (fn [idx [filename todo-list]]
                                  (let [fname (no-extension filename)]
                                    [:li.todo-link {:key filename :class (str "oddeven-" (mod idx 2))}
                                     (when @add-mode [:i.delete-list.btn {:on-click (partial delete-todo-list-handler todos filename add-mode) :class "fa fa-minus-circle"}])
                                     [:span.unchecked-count (count (filter #(= (% :checked) false) todo-list))]
                                     [:span {:on-click (partial switch-to-todo fname)} fname]]))
                                ; sort by the creation time timestamps the server has sent, defaulting to infinity (for newly created files)
                                (sort #(compare (or (@timestamps (first %2)) js/Number.MAX_VALUE) (or (@timestamps (first %1)) js/Number.MAX_VALUE)) @todos)))
            (when (not @add-mode)
              [:li.message
               [:p "You don't have any TODO lists yet."]
               [:p "You can create lists like 'Shopping' or 'Work' using the pencil icon."]]))]
         (when (= @last-timestamp 0) [:p.warn "No server connected."])]))))

(defn current-page []
  [:div [(session/get :current-page)]])

;; -------------------------
;; Routes

(secretary/defroute "/" []
  (session/put! :current-page (partial #'lists-page todo-lists todo-timestamps)))

(secretary/defroute "/:fname" #_:clj-kondo/ignore [fname]
  (session/put! :current-page (partial #'todo-page todo-lists fname)))

;; -------------------------
;; Hooks

;; Quick and dirty history configuration.
(defn hook-browser-navigation! []
  (let [h (History.)]
    (goog.events/listen h EventType/NAVIGATE #(secretary/dispatch! (.-token %)))
    (doto h (.setEnabled true))))

(defn hook-focus-watcher! [f]
  (.addEventListener js/window "blur" #(reset! f false) false)
  (.addEventListener js/window "focus" #(reset! f true) false))

;; -------------------------
;; Initialize app

; initiate the long-poller
(long-poller todo-lists todo-timestamps (swap! poller-instance inc))

; tell react to handle touch events
(.initializeTouchEvents js/React true)

(defn mount-root []
  (reagent/render [current-page] (.getElementById js/document "app")))

(defn init! [& [prod]]
  (if prod
    (swap! server assoc
           :url "server.php"
           :poller-time 30)
    (js/console.log "dev mode"))
  (hook-browser-navigation!)
  (hook-focus-watcher! app-has-focus)
  (mount-root))
