(ns fix-renamed-class-urls
  "This script fixes schema.org renamed class urls to drop the incorrect '_Class' suffix"
  (:require [logseq.tasks.db-graph.persist-graph :as persist-graph]
            [logseq.db.sqlite.cli :as sqlite-cli]
            [logseq.db.sqlite.db :as sqlite-db]
            [datascript.core :as d]
            [clojure.string :as string]
            [nbb.core :as nbb]
            ["path" :as node-path]
            ["os" :as os]))

(defn -main [args]
  (when (not= 1 (count args))
    (println "Usage: $0 GRAPH-DIR")
    (js/process.exit 1))
  (let [[graph-dir] args
        [dir db-name] (if (string/includes? graph-dir "/")
                        ((juxt node-path/dirname node-path/basename) graph-dir)
                        [(node-path/join (os/homedir) "logseq" "graphs") graph-dir])
        _ (sqlite-db/open-db! dir db-name)
        conn (sqlite-cli/read-graph db-name)
        ;; find blocks to update
        query '[:find ?b ?prop ?v
                :where
                [?b :block/type "class"]
                [?b :block/original-name ?name]
                [(clojure.string/ends-with? ?name "_Class")]

                ;; can't use (page-property b/c we also need ?prop val)
                [?b :block/properties ?prop]
                [?prop-b :block/name "url"]
                [?prop-b :block/type "property"]
                [?prop-b :block/uuid ?prop-uuid]
                [(get ?prop ?prop-uuid) ?v]]
        blocks-to-update (d/q query @conn)
        ;; update
        url-uuid (or (:block/uuid (d/entity @conn [:block/name "url"]))
                   (throw (ex-info "No :db/id for url" {})))
        update-tx (mapv (fn [[eid props url]]
                          (let [url' (string/replace-first url #"_Class$" "")]
                            ;; (println url "->" url')
                            {:db/id eid
                             :block/properties (assoc props url-uuid url')}))
                        blocks-to-update)]
    (persist-graph/add-listener conn db-name)
    (d/transact! conn update-tx)
    (println "Updated" (count update-tx) "block(s) for graph" (str db-name "!"))))

(when (= nbb/*file* (:file (meta #'-main)))
  (-main *command-line-args*))