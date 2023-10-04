(ns fix-garbage-block-journal-attrs
  "This script fixes blocks that accidentally had :block/journal-day and :block/journal? attributes"
  (:require [logseq.tasks.db-graph.persist-graph :as persist-graph]
            [logseq.db.sqlite.cli :as sqlite-cli]
            [logseq.db.sqlite.db :as sqlite-db]
            [logseq.db.sqlite.util :as sqlite-util]
            [datascript.core :as d]
            [clojure.string :as string]
            [nbb.core :as nbb]
            ["path" :as node-path]
            ["os" :as os]))

(defn -main [args]
  (when (empty? args)
    (println "Usage: $0 GRAPH-DIR")
    (js/process.exit 1))
  (let [[graph-dir] args
        dry-run? (contains? (set args) "-n")
        [dir db-name] (if (string/includes? graph-dir "/")
                        ((juxt node-path/dirname node-path/basename) graph-dir)
                        [(node-path/join (os/homedir) "logseq" "graphs") graph-dir])
        _ (sqlite-db/open-db! dir db-name)
        conn (sqlite-cli/read-graph db-name)
        query '[:find ?b
                :where
                [?b :block/content]
                (or [?b :block/journal-day]
                    [?b :block/journal?])]
        blocks-to-update (map first (d/q query @conn))
        update-tx (mapcat #(vector [:db.fn/retractAttribute % :block/journal?]
                                   [:db.fn/retractAttribute % :block/journal-day])
                          blocks-to-update)]
    (if dry-run?
      (do (println "Would update" (count blocks-to-update) "blocks with the following tx:")
          (prn update-tx))
      (do
        (persist-graph/add-listener conn db-name)
        (d/transact! conn update-tx)
        (println "Updated" (count update-tx) "block(s) for graph" (str db-name "!"))))))

(when (= nbb/*file* (:file (meta #'-main)))
  (-main *command-line-args*))