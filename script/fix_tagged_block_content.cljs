(ns fix-tagged-block-content
  "This script fixes tagged blocks with :block/content that still has its tags in it.
   We stopped including tags in content for a 2nd time with https://github.com/logseq/logseq/commit/575624c650b2b7e919033a79aa5d14b97507d86f.
   First time we stopped including tags in content the week of Oct 9."
  (:require [datascript.core :as d]
            [logseq.outliner.cli.pipeline :as cli-pipeline]
            [logseq.db.sqlite.db :as sqlite-db]
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
        conn (sqlite-db/open-db! dir db-name)
        query '[:find (pull ?b [:db/id :block/content])
                :where
                [?b :block/tags]
                [?b :block/content ?content]
                [(clojure.string/includes? ?content "#")]
                [(missing? $ ?b :block/link)]]
        blocks-to-update (map first (d/q query @conn))
        update-tx (mapv #(update % :block/content
                                 (fn [content]
                                   (println (pr-str content) "->" (pr-str (second (re-find #"(.*\S)\s+#\S+$" content))))
                                   (or (second (re-find #"(.*\S)\s+#\S+$" content))
                                       (throw (ex-info "Block doesn't have content to replace" {:content content})))))
                        blocks-to-update)]
    (if dry-run?
      (do (println "Would update" (count blocks-to-update) "blocks with the following tx:")
          (prn update-tx))
      (do
        (cli-pipeline/add-listener conn)
        (d/transact! conn update-tx)
        (println "Updated" (count update-tx) "block(s) for graph" (str db-name "!"))))))

(when (= nbb/*file* (:file (meta #'-main)))
  (-main *command-line-args*))