(ns omgnata.handler
  (:require [compojure.core :refer [GET defroutes]]
            [compojure.route :refer [not-found resources]]
            [hiccup.page :refer [include-js include-css html5]]
            [omgnata.middleware :refer [wrap-middleware]]
            [environ.core :refer [env]]))

(def mount-target
  [:div#app
   [:div#loader [:div]]])

(def loading-page
  (html5
   [:head
     [:meta {:charset "utf-8"}]
     [:meta {:name "viewport"
             :content "width=device-width, initial-scale=1"}]
     [:meta {:name "mobile-web-app-capable" :content "yes"}]
     [:meta {:name "apple-mobile-web-app-capable" :content "yes"}]
     [:meta {:name "apple-mobile-web-app-status-bar-style" :content "black"}]
     [:title "TODO"]
     [:link {:rel "apple-touch-icon-precomposed" :sizes "192x192" :href "img/icon.png"}]
     [:link {:rel "icon" :type "image/png" :href "img/icon.png"}]
     [:link {:rel "manifest" :href "manifest.json" :crossorigin "use-credentials"}]
     (include-css (if (= (env :prod) "1") "css/site.min.css" "css/site.css"))
     [:script {:type "application/javascript" :src "sortable/Sortable.min.js"}]]
   [:body
    mount-target
    (include-js "js/app.js")]))


(defroutes routes
  (GET "/" [] loading-page)
  (GET "/about" [] loading-page)
  
  (resources "/")
  (not-found "Not Found"))

(def app (wrap-middleware #'routes))

(defn index-html
  "output the HTML as a string"
  []
  (print (apply str loading-page)))
