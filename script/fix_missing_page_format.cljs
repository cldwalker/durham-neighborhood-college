(ns fix-missing-page-format
  "This script fixes pages with missing :block/format"
  (:require [logseq.tasks.db-graph.persist-graph :as persist-graph]
            [logseq.db.sqlite.cli :as sqlite-cli]
            [logseq.db.sqlite.db :as sqlite-db]
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
        query '[:find (pull ?b [:db/id :block/name])
                :where
                [?b :block/name]
                [(missing? $ ?b :block/format)]]
        blocks-to-update (map first (d/q query @conn))
        update-tx (mapv #(hash-map :db/id (:db/id %) :block/format :markdown)
                        blocks-to-update)]
    (if dry-run?
      (do (println "Would update" (count blocks-to-update) "blocks with the following tx:")
          (prn (map :block/name blocks-to-update))
          (prn update-tx))
      (do
        (persist-graph/add-listener conn db-name)
        (d/transact! conn update-tx)
        (println "Updated" (count update-tx) "block(s) for graph" (str db-name "!"))))))

(when (= nbb/*file* (:file (meta #'-main)))
  (-main *command-line-args*))