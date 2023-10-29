(ns exclude-from-graph
  "This script sets the exclude-from-graph-view property for all non-journal pages
  before the given timestamp. This is useful for creating graph views that don't
  include initial seed data e.g. graphs that are seeded with schema.org ontology"
  (:require [logseq.outliner.cli.persist-graph :as persist-graph]
            [logseq.db.sqlite.cli :as sqlite-cli]
            [logseq.db.sqlite.db :as sqlite-db]
            [datascript.core :as d]
            [clojure.string :as string]
            [nbb.core :as nbb]
            ["path" :as node-path]
            ["os" :as os]))

(defn -main [args]
  (when (empty? args)
    (println "Usage: $0 GRAPH-DIR TIMESTAMP")
    (js/process.exit 1))
  (let [[graph-dir timestamp] args
        dry-run? (contains? (set args) "-n")
        [dir db-name] (if (string/includes? graph-dir "/")
                        ((juxt node-path/dirname node-path/basename) graph-dir)
                        [(node-path/join (os/homedir) "logseq" "graphs") graph-dir])
        _ (sqlite-db/open-db! dir db-name)
        conn (sqlite-cli/read-graph db-name)
        query '[:find ?b ?prop
                :in $ ?timestamp
                :where
                [?b :block/name]
                [?b :block/journal? false]
                [(get-else $ ?b :block/properties {}) ?prop]
                [?b :block/created-at ?created-at]
                [(< ?created-at ?timestamp)]]
        blocks-to-update (d/q query @conn (js/parseInt timestamp))
        exclude-from-id (or (:block/uuid (d/entity @conn [:block/name "exclude-from-graph-view"]))
                            (throw (ex-info "No :db/id for exclude-from-graph-view" {})))
        update-tx (mapv (fn [[eid props]]
                          {:db/id eid
                           :block/properties (assoc props exclude-from-id true)})
                        blocks-to-update)]
    (if dry-run?
      (do (println "Would update" (count blocks-to-update) "blocks with the following tx:")
          ;; (prn (sort-by :block/created-at (map first blocks-to-update)))
          (prn update-tx))
      (do
        (persist-graph/add-listener conn db-name)
        (d/transact! conn update-tx)
        (println "Updated" (count update-tx) "block(s) for graph" (str db-name "!"))))))

(when (= nbb/*file* (:file (meta #'-main)))
  (-main *command-line-args*))