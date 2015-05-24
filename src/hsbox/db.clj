(ns hsbox.db
  (:require [clojure.data.json :as json]
            [clojure.java.jdbc :as jdbc]
            [clojure.java.io :as io :refer [resource]]
            [clojure.string :as str]
            [hsbox.util :refer [current-timestamp file-exists?]]
            [taoensso.timbre :as timbre])
  (:import (java.io File)))

(timbre/refer-timbre)
(def latest-data-version 1)
(def schema-version 3)
;(set! *warn-on-reflection* true)

(def app-config-dir
  (let [config-home (if-let [xdg (System/getenv "XDG_CONFIG_HOME")]
                      (File. xdg)
                      (File. (System/getProperty "user.home") ".config"))
        app-config (File. config-home "headshotbox")]
    (.mkdir config-home)
    (.mkdir app-config)
    app-config))

(def db
  {:classname "org.sqlite.JDBC"
   :subprotocol "sqlite"
   :subname (File. app-config-dir "headshotbox.sqlite")})

(defn exec-sql-file [file & {:keys [transaction?] :or {transaction? true}}]
  (let [queries (str/split (slurp (resource file)) #";\r?\n")]
    (apply jdbc/db-do-commands db transaction? queries)))

(defn init-db []
  (exec-sql-file "sql/create.sql"))

(defn wipe-demos []
  (jdbc/with-db-transaction
    [trans db]
    (jdbc/execute! db ["DELETE FROM demos"])))

(defn init-db-if-absent []
  (if-not (file-exists? (str app-config-dir "/headshotbox.sqlite"))
    (init-db)))

(defn get-meta-value [key]
  (json/read-str (:value (first (jdbc/query db ["SELECT value FROM meta WHERE key=?" key]))) :key-fn keyword))

(defn get-current-schema-version [] (get-meta-value "schema_version"))

(defn get-config []
  (get-meta-value "config"))

(defn half-parsed-demo? [{:keys [score rounds players]}]
  (let [score1 (first (:score score))
        score2 (second (:score score))]
    (or (= 0 (count players))
        (not= 2 (count (:score score)))
        (and (not (:surrendered score)) (not= score1 score2 15) (< score1 16) (< score2 16))
        (not (empty? (filter #(not (:tick_end %)) rounds))))))

(defn kw-steamids-to-long [path dict]
  (assoc-in dict path (into {} (for [[k v] (get-in dict path)] [(Long/parseLong (name k)) v]))))

(defn db-json-to-dict [rows]
  (->> rows
       (map #(assoc % :data (json/read-str (:data %) :key-fn keyword)))
       (map (partial kw-steamids-to-long [:data :players]))))

(defn get-all-demos []
  (->>
    (jdbc/query db [(str "SELECT demos.demoid, data FROM demos")])
    (db-json-to-dict)
    (map #(assoc (:data %) :demoid (:demoid %)))))

(defn sql-demoids [demoids]
  (str " (" (str/join ", " (map #(str "\"" % "\"") demoids)) ")"))

(defn migrate-2 []
  (exec-sql-file "sql/migrate_1_to_2.sql" :transaction? false)
  (let [half-parsed-demos (filter half-parsed-demo? (get-all-demos))
        demoids (map #(:demoid %) half-parsed-demos)]
    (if (not (empty? demoids))
      (jdbc/execute! db [(str "UPDATE demos SET mtime = 0 WHERE demoid IN " (sql-demoids demoids))]))))

(defn migrate-3 []
  (let [demos (jdbc/query db [(str "SELECT demos.demoid, mtime FROM demos")])]
    (doseq [demo demos]
        (let [mtime (int (read-string (str (:mtime demo))))]
          (jdbc/execute! db ["UPDATE demos SET mtime = ? WHERE demoid = ?" mtime (:demoid demo)])))))

(def migrations {1 [2 migrate-2]
                 2 [3 migrate-3]})

(defn get-migration-plan []
  (loop [plan []
         version (get-current-schema-version)]
    (cond
      (= version schema-version) plan
      (not (contains? migrations version)) (throw (Exception. "Cannot find a migration plan"))
      :else (let [[next-version f] (get migrations version)]
              (recur (conj plan [next-version f]) next-version)))))

(defn upgrade-db []
  (let [migration-plan (get-migration-plan)]
    (doall (map #(let [version (first %) procedure (second %)]
                  (warn "Migrating from schema version" (get-current-schema-version) "to" version)
                  (jdbc/with-db-transaction
                    [trans db]
                    (procedure)
                    (jdbc/execute! db ["UPDATE meta SET value = ? WHERE key = ?" version "schema_version"])))
                migration-plan))))

(defn set-config [dict]
  (jdbc/with-db-transaction
    [trans db]
    (jdbc/execute! db ["UPDATE meta SET value=? WHERE key=?" (json/write-str dict) "config"])))

(defn update-config [dict]
  (set-config (merge (get-config) dict)))

(defn get-steam-api-key [] (:steam_api_key (get-config)))

(defn get-demo-directory [] (:demo_directory (get-config)))

(defn demo-path [demoid]
  (.getPath (io/file (get-demo-directory) demoid)))

(defn get-data-version [demoid]
  (:data_version (first (jdbc/query db ["SELECT data_version FROM demos WHERE demoid=?" demoid]))))

(defn get-demo-mtime [demoid]
  (:mtime (first (jdbc/query db ["SELECT mtime FROM demos WHERE demoid=?" demoid]))))

(defn demoid-in-db? [demoid mtime]
  "Returns true if the demo is present, was parsed by the latest version at/after mtime"
  (and (= (get-data-version demoid) latest-data-version) (<= mtime (get-demo-mtime demoid))))

(defn del-demo [demoid]
  (jdbc/with-db-transaction
    [trans db]
    (jdbc/execute! db ["DELETE FROM demos WHERE demoid=?" demoid])))

(defn add-demo [demoid timestamp mtime map data]
  (jdbc/with-db-transaction
    [trans db]
    (let [data-version (get-data-version demoid)]
      (cond
        (nil? data-version)
        (do
          (debug "Adding demo data for" demoid)
          (jdbc/execute! db ["INSERT INTO demos (demoid, timestamp, mtime, map, data_version, data) VALUES (?, ?, ?, ?, ?, ?)"
                             demoid timestamp mtime map latest-data-version (json/write-str data)]))
        (not (demoid-in-db? demoid mtime))
        (do
          (debug "Updating data for demo" demoid)
          (jdbc/execute! db ["UPDATE demos SET data=?, data_version=?, timestamp=?, mtime=?, map=? WHERE demoid=?"
                             (json/write-str data) latest-data-version timestamp mtime map demoid]))))))

(defn keep-only [demoids]
  (if (count demoids)
    (jdbc/with-db-transaction
      [trans db]
      (jdbc/execute! db [(str "DELETE FROM demos WHERE demoid NOT IN " (sql-demoids demoids))]))))

(defn get-steamid-info [steamids]
  (->>
    (jdbc/query db [(str "SELECT steamid, timestamp, data FROM steamids WHERE steamid IN (" (str/join ", " steamids) ")")])
    (map #(assoc % :data (json/read-str (:data %) :key-fn keyword)))
    (map #(assoc (:data %) :steamid (:steamid %) :timestamp (:timestamp %)))))

(defn update-steamids [steamids-info]
  (jdbc/with-db-transaction
    [trans db]
    (do
      (jdbc/execute! db [(str "DELETE FROM steamids WHERE steamid IN (" (str/join ", " (keys steamids-info)) ")")])
      (doseq [steamid-info steamids-info]
        (jdbc/execute! db ["INSERT INTO steamids (steamid, timestamp, data) VALUES (?, ?, ?)"
                           (first steamid-info) (current-timestamp) (json/write-str (second steamid-info))]))
      ))
  steamids-info)

(defn get-demo-notes [demoid]
  (jdbc/with-db-transaction
    [trans db]
    (:notes (first (jdbc/query db ["SELECT notes FROM demos WHERE demoid=?" demoid])))))

(defn set-demo-notes [demoid notes]
  (jdbc/with-db-transaction
    [trans db]
    (jdbc/execute! db ["UPDATE demos SET notes=? WHERE demoid=?" notes demoid])))