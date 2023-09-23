(ns sync-class-and-property-class-descriptions
  "This script syncs any changes to schema.org class + property descriptions.
  Changes can happen from schema.org's latest data export or from changes in how
  descriptions are parsed"
  (:require [logseq.tasks.db-graph.persist-graph :as persist-graph]
            [logseq.db.property :as db-property]
            [logseq.db.sqlite.util :as sqlite-util]
            [logseq.db.sqlite.cli :as sqlite-cli]
            [logseq.db.sqlite.db :as sqlite-db]
            [datascript.core :as d]
            [clojure.string :as string]
            [clojure.walk :as w]
            [clojure.set :as set]
            [nbb.core :as nbb]
            ["path" :as node-path]
            ["fs" :as fs]
            ["os" :as os]))

;; Copied from logseq.tasks.db-graph.create-graph-with-schema-org
(def unsupported-data-types
  "Schema datatypes, https://schema.org/DataType, that don't have Logseq equivalents"
  #{"schema:Time" "schema:DateTime"})

(defn- detect-id-conflicts-and-get-renamed-classes
  "Properties and class names conflict in Logseq because schema.org names are
  case sensitive whereas Logseq's :block/name is case insensitive. This is dealt
  with by appending a '_Class' suffix to conflicting classes.  If this strategy
  changes, be sure to update schema->logseq-data-types"
  [property-ids class-ids {:keys [verbose]}]
  (let [conflicts
        (->> (concat property-ids class-ids)
             (group-by (comp sqlite-util/sanitize-page-name first))
             (filter #(> (count (val %)) 1))
             vals)
        ;; If this assertion fails then renamed-classes approach to resolving
        ;; conflicts may need to be revisited
        _ (assert (every? #(= (map second %) [:property :class]) conflicts)
                  "All conflicts are between a property and class")
        renamed-classes (->> conflicts
                             (map #(-> % second first))
                             ;; Renaming classes with '_Class' suffix guarantees uniqueness
                             ;; b/c schema.org doesn't use '_' in their names
                             (map #(vector % (str % "_Class")))
                             (into {}))]
    (if verbose
      (println "Renaming the following classes because they have property names that conflict with Logseq's case insensitive :block/name:"
               (keys renamed-classes) "\n")
      (println "Renaming" (count renamed-classes) "classes due to page name conflicts"))
        ;; Looks for all instances of a renamed class and updates them to the renamed class reference
    renamed-classes))

(defn property-with-unsupported-type? [prop]
  (let [range-includes
        (as-> (prop "schema:rangeIncludes") range-includes*
          (set (map (fn [m] (m "@id")) (if (map? range-includes*) [range-includes*] range-includes*))))
        unsupported-data-types
        (set/intersection range-includes unsupported-data-types)]
    (and (seq range-includes)
         (every? (fn [x] (contains? unsupported-data-types x)) range-includes))))

(defn- get-all-properties [schema-data {:keys [verbose]}]
  (let [all-properties** (filter #(= "rdf:Property" (% "@type")) schema-data)
        [superseded-properties all-properties*] ((juxt filter remove) #(% "schema:supersededBy") all-properties**)
        _ (if verbose
            (println "Skipping the following superseded properties:" (mapv #(% "@id") superseded-properties) "\n")
            (println "Skipping" (count superseded-properties) "superseded properties"))
        [unsupported-properties all-properties] ((juxt filter remove) property-with-unsupported-type? all-properties*)
        _ (if verbose
            (println "Skipping the following unsupported properties:" (mapv #(% "@id") unsupported-properties) "\n")
            (println "Skipping" (count unsupported-properties) "properties with unsupported data types"))]
    all-properties))

(defn- strip-schema-prefix [s]
  (string/replace-first s "schema:" ""))

(defn- get-all-classes-and-properties
  "Get all classes and properties from raw json file"
  [schema-data options]
  (let [;; TODO: See if it's worth pulling in non-types like schema:MusicReleaseFormatType
        all-classes* (filter #(contains? (set (as-> (% "@type") type'
                                                (if (string? type') [type'] type')))
                                         "rdfs:Class")
                             schema-data)
        all-properties* (get-all-properties schema-data options)
        renamed-classes (detect-id-conflicts-and-get-renamed-classes
                         (map #(vector (% "@id") :property) all-properties*)
                         (map #(vector (% "@id") :class) all-classes*)
                         options)
        rename-class-ids (fn [m]
                           (w/postwalk (fn [x]
                                         (if-let [new-class (and (map? x) (renamed-classes (x "@id")))]
                                           (merge x {"@id" new-class})
                                           x)) m))
        ;; Updates keys like @id, @subClassOf
        all-classes (map rename-class-ids all-classes*)
        ;; Updates keys like @id, @rangeIncludes, @domainIncludes
        all-properties (map rename-class-ids all-properties*)]
    {:all-classes all-classes
     :all-properties all-properties
     :renamed-classes (->> renamed-classes
                           (map (fn [[k v]] [(strip-schema-prefix k) (strip-schema-prefix v)]))
                           (into {}))}))

(defn- get-comment-string
  [rdfs-comment renamed-classes]
  (let [desc* (if (map? rdfs-comment)
                (get rdfs-comment "@value")
                rdfs-comment)
        ;; Update refs to renamed classes
        regex (re-pattern (str "\\[\\[(" (string/join "|" (keys renamed-classes)) ")\\]\\]"))
        desc (string/replace desc* regex #(str "[[" (get renamed-classes (second %)) "]]"))]
    ;; Fix markdown and html links to schema website docs
    (string/replace desc #"(\(|\")/docs" "$1https://schema.org/docs")))
;; end of copied

(defn get-descriptions
  []
  (let [schema-data (-> (str (fs/readFileSync "resources/schemaorg-current-https.json"))
                        js/JSON.parse
                        (js->clj)
                        (get "@graph"))
        {:keys [all-classes renamed-classes all-properties]}
        (get-all-classes-and-properties schema-data {:verbose false})]
    {:property-descriptions
     (->> all-properties
          (map #(vector (strip-schema-prefix (% "@id"))
                        (get-comment-string (% "rdfs:comment") renamed-classes)))
          (into {}))
     :class-descriptions
     (->> all-classes
          (map #(vector (strip-schema-prefix (% "@id"))
                        (get-comment-string (% "rdfs:comment") renamed-classes)))
          (into {}))}))

(defn- build-update-tx [conn]
  (let [{:keys [property-descriptions class-descriptions]} (get-descriptions)
        ;; find blocks to update
        classes-to-update (map first
                               (d/q
                                '[:find (pull ?b [*])
                                  :where
                                  [?b :block/type "class"]] @conn))

        properties-to-update (map first
                                  (d/q
                                   '[:find (pull ?b [*])
                                     :where
                                     [?b :block/type "property"]] @conn))
        ;; build update
        description-uuid (or (:block/uuid (d/entity @conn [:block/name "description"]))
                             (throw (ex-info "No :db/id for TODO" {})))
        class-update-tx (vec
                         (keep (fn [{:block/keys [properties original-name] :db/keys [id]}]
                                 (if-let [desc (class-descriptions original-name)]
                                   {:db/id id
                                    :block/properties (assoc properties description-uuid desc)}
                                   (println "Skipped" original-name "because not in schema.org")))
                               classes-to-update))
        property-update-tx (vec
                            (keep (fn [{:block/keys [schema original-name name] :db/keys [id]}]
                                    (if-let [desc (property-descriptions original-name)]
                                      {:db/id id
                                       :block/schema (assoc schema :description desc)}
                                      (when-not (db-property/built-in-properties-keys-str name)
                                        (println "Skipped" original-name "because not in schema.org"))))
                                  properties-to-update))]
    {:class-update-tx class-update-tx
     :property-update-tx property-update-tx}))

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
        {:keys [property-update-tx class-update-tx]} (build-update-tx conn)]
    (persist-graph/add-listener conn db-name)
    (d/transact! conn (concat class-update-tx property-update-tx))
    (println "Updated" (count class-update-tx) "classes and"
             (count property-update-tx) "properties"
             "for graph" (str db-name "!"))))

(when (= nbb/*file* (:file (meta #'-main)))
  (-main *command-line-args*))