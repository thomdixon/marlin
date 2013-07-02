(ns marlin.handler
  (:use compojure.core
        [overtone.at-at :only [at now mk-pool]])
  (:require [compojure.handler :as handler]
            [compojure.route :as route]
            [cheshire.core :refer :all]
            [marlin.fs :as fs]
            [marlin.db :as db]))

(def at-pool (mk-pool))

(defn- json-200
  [raw]
  { :status 200
    :body (generate-string raw)
    :headers { "Content-Type" "application/json" } })

(defn- text-200
  [raw]
  { :status 200
    :body raw
    :headers { "Content-Type" "text/plain" } })

(defn- set-all-attributes
  [filename size filehash]
  (db/lock-file filename)
  (db/set-file-attributes filename "size" size "hash" filehash ))

(defn sync-db-with-fs
  "Clears the database and loads it with fully correct data about what it's watching
  on the filesystem"
  []
  (db/flushdb)
  (fs/file-walk
    #(let [fullname (fs/full-name %)
           fhash (fs/file-hash fullname)
           fsize (fs/file-size fullname)]
      (set-all-attributes % fsize fhash))))

(defroutes app-routes
  (GET "/" [] "Some info would probably go here")

  (PUT "/:fn" {{ filename :fn filehash :hash } :params body :body}
    (if-not (db/lock-file filename)

      ;If we can't lock the file then 400
      {:status 400 :body "File already exists"}

      ;If we can then try to put the file
      (let [path (fs/full-path filename)
            fullname (fs/path-join path filename)]
        (.mkdirs (java.io.File. path))
        (if-let [size (with-open [fileout (java.io.FileOutputStream. fullname)]
                        (fs/safe-read-to-write body fileout filehash))]

            ;If the write was successful we save stuff in db and send back 200
            (do (set-all-attributes filename size filehash)
                {:status 200})

            ;If it wasn't we delete what we just wrote and send back 400
            (do (.delete (java.io.File. fullname))
                (db/unlock-file filename)
                {:status 400 :body "File hash doesn't match"})))))

  (GET "/all" {{ json :json } :params}
    (let [all (db/get-all-files)]
      (if (and json (not (= json "0")))
        (json-200 all)
        (text-200 (apply str (interpose \newline all))))))

  ;; TODO this is not atomic, might be better to just take it out
  (GET "/allattributes" {}
    (json-200
      (reduce #(assoc %1 %2 (db/get-all-file-attributes %2)) {} (db/get-all-files))))

  (GET "/sync" {}
    (future (sync-db-with-fs))
    {:status 200})

  (GET "/:fn" {{ filename :fn } :params}
    (let [fullname (fs/full-name filename)]
      (when (.exists (java.io.File. fullname))
        { :status 200
          :body (slurp fullname)
          :headers { "Content-Type" "application/octet-stream" }})))

  (GET "/:fn/all" {{ filename :fn } :params}
    (when-let [all (db/get-all-file-attributes filename)]
      (json-200 all)))

  (GET "/:fn/:attr" {{ filename :fn attr :attr} :params}
    (when-let [value (db/get-file-attribute filename attr)]
      (text-200 value)))

  (DELETE "/:fn" {{ filename :fn delay-amnt :delay } :params}
    (let [dodel (fn [] (.delete (java.io.File. (fs/full-name filename)))
                       (db/del-file filename)
                       (db/unlock-file filename)
                       {:status 200}) ]
      (if-not (nil? delay-amnt)
        (do (at (+ (now) (Integer/valueOf delay-amnt)) dodel at-pool) {:status 200})
        (dodel))))

  (route/resources "/")
  (route/not-found ""))

(def app
  (handler/api app-routes))
