(ns new-db
  "A minimal version of the new logseq.db.sqlite.db"
  (:require ["better-sqlite3$default" :as sqlite]
            #_:clj-kondo/ignore
            [datascript.core :as d]
            [datascript.storage :refer [IStorage]]
            [goog.object :as gobj]
            [clojure.edn :as edn]))

(defn query
  [db sql]
  (let [stmt (.prepare db sql)]
    (.all ^object stmt)))

(defn upsert-addr-content!
  "Upsert addr+data-seq"
  [db data]
  (let [insert (.prepare db "INSERT INTO kvs (addr, content) values (@addr, @content) on conflict(addr) do update set content = @content")
        insert-many (.transaction ^object db
                                  (fn [data]
                                    (doseq [item data]
                                      (.run ^object insert item))))]
    (insert-many data)))

(defn restore-data-from-addr
  [db addr]
  (-> (query db (str "select content from kvs where addr = " addr))
      first
      (gobj/get "content")))

(defn new-sqlite-storage
  "Creates a datascript storage for sqlite. Should be functionally equivalent to db-worker/new-sqlite-storage"
  [db]
  (reify IStorage
    (-store [_ addr+data-seq]
      (let [data (->>
                  (map
                   (fn [[addr data]]
                     #js {:addr addr
                          :content (pr-str data)})
                   addr+data-seq)
                  (to-array))]
        (upsert-addr-content! db data)))
    (-restore [_ addr]
      (let [content (restore-data-from-addr db addr)]
        (edn/read-string content)))))

(defn create-storage
  "For a given database name, opens a sqlite db connection for it, creates
  needed sqlite tables if not created and returns a datascript connection that's
  connected to the sqlite db"
  [db-full-path]
  (let [db (new sqlite db-full-path nil)]
    (.exec db "create table if not exists kvs (addr INTEGER primary key, content TEXT)")
    (new-sqlite-storage db)))

(ns migrate-storage
  "This script migrates the sqlite db from the old version last used at
  https://github.com/logseq/logseq/pull/9858/commits/5f7c902b5d0e8c2778fd47f58f00b489173dacc5
  to the new datascript.storage based storage introduced at
  https://github.com/logseq/logseq/commit/4b520a9806f06fb110065a6bf0f5036d5b67c407"
  (:require [logseq.db.sqlite.cli :as sqlite-cli]
            [logseq.db.sqlite.db :as sqlite-db]
            [logseq.db.frontend.schema :as db-schema]
            [new-db]
            [datascript.core :as d]
            [clojure.string :as string]
            [nbb.core :as nbb]
            ["fs" :as fs]
            ["path" :as node-path]
            ["os" :as os]))

(defn -main [args]
  (when (< (count args) 2)
    (println "Usage: $0 GRAPH-DIR NEW-GRAPH-DIR")
    (js/process.exit 1))
  (let [[graph-dir new-graph] args
        dry-run? (contains? (set args) "-n")
        [dir db-name] (if (string/includes? graph-dir "/")
                        ((juxt node-path/dirname node-path/basename) graph-dir)
                        [(node-path/join (os/homedir) "logseq" "graphs") graph-dir])
        new-db-file (node-path/join dir db-name (str new-graph ".sqlite"))
        _ (sqlite-db/open-db! dir db-name)
        conn (sqlite-cli/read-graph db-name)
        datoms (d/datoms @conn :eavt)]
    (if dry-run?
      (println "Would copy db" graph-dir "with" (count datoms) "datoms")
      (let [;; Delete any existing file or else new storage won't be written
            _ (when (fs/existsSync new-db-file) (fs/rmSync new-db-file))
            storage (new-db/create-storage new-db-file)
            _ (println "Copying db" graph-dir "with" (count datoms) "datoms")
            new-conn (d/conn-from-datoms datoms db-schema/schema-for-db-based-graph {:storage storage})
            new-datoms (d/datoms @new-conn :eavt)]
        (println "Created new db at" new-db-file "with" (count new-datoms) "datoms")))))

(when (= nbb/*file* (:file (meta #'-main)))
  (-main *command-line-args*))