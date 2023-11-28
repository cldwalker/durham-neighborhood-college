(ns fix-tagged-block-content-again
  "This script fixes tagged blocks with :block/content that don't have tags in it.
   We brought back including tags in content on Nov 16th with https://github.com/logseq/logseq/commit/09ad27b0ffcd8dc8bb6424b9dca183552bf28aa4"
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
    (println "Usage: $0 GRAPH-DIR")
    (js/process.exit 1))
  (let [[graph-dir] args
        dry-run? (contains? (set args) "-n")
        [dir db-name] (if (string/includes? graph-dir "/")
                        ((juxt node-path/dirname node-path/basename) graph-dir)
                        [(node-path/join (os/homedir) "logseq" "graphs") graph-dir])
        _ (sqlite-db/open-db! dir db-name)
        conn (sqlite-cli/read-graph db-name)
        query '[:find (pull ?b [:db/id :block/content {:block/tags [:block/uuid :block/original-name]}])
                :where
                [?b :block/tags]
                [?b :block/content ?content]
                (not [(clojure.string/includes? ?content "#")])
                [(missing? $ ?b :block/link)]]
        blocks-to-update (map first (d/q query @conn))
        update-tx (mapv #(hash-map
                          :db/id
                          (:db/id %)
                          :block/content
                          (do
                            (println (pr-str (:block/content %)) "->" (pr-str (str (:block/content %) " #~^" (-> % :block/tags first :block/original-name))))
                            (when (> (count (:block/tags %)) 1) (throw (ex-info "More than 1 tag" {})))
                            ;; This tag form only works for tags with no spaces
                            (when (some (fn [t] (string/includes? (:block/original-name t) " ")) (:block/tags %))
                              (throw (ex-info "Tags can't have whitespace" {})))
                            (str (:block/content %) " #~^" (-> % :block/tags first :block/uuid))))
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