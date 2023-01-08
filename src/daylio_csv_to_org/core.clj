(ns daylio-csv-to-org.core
  (:require
   [camel-snake-kebab.core :as csk]
   [clojure.data.csv :as csv]
   [clojure.java.io :as io]
   [clojure.pprint :refer [pprint]]
   [clojure.string :as string]
   [java-time :as datetime]
   [malli.core :as m]
   [malli.error :as me]
   [malli.transform :as mt]
   [malli.util :as mu]
   [piotr-yuxuan.malli-cli :as malli-cli])
  (:import
   (java.time ZoneOffset)
   (org.apache.commons.io FilenameUtils)))

(def EntryToOrgSubtreeOptions
  (m/schema
   [:map {:closed true}
    [:content-max-line-length [pos-int? {:default 78}]]
    [:heading-max-length [pos-int? {:default 60}]]
    [:heading-timestamp-enabled [boolean? {:default true}]]
    [:heading-timestamp-show-time [boolean? {:default false}]]]))

(def Options
  (mu/merge
   EntryToOrgSubtreeOptions
   (m/schema
    [:map {:closed true
           :decode/args-transformer malli-cli/args-transformer}
     [:help
      {:optional true}
      [boolean? {:description "Display usage summary and exit"
                 :short-option "-h"
                 :arg-number 0}]]
     [:daylio-csv-input [string? {:description "Path to a Daylio CSV export file"
                                  :short-option "-i"}]]
     [:org-output [string? {:description "Path to the resulting Org file"
                            :short-option "-o"}]]])))

(defn truncate
  ([string length]
   (truncate string length "…"))
  ([string length suffix]
   (let [string-len (count string)
         suffix-len (count suffix)]
     (if (<= string-len length)
       string
       (str (subs string 0 (- length suffix-len)) suffix)))))

(defn size-in-bytes [^String s]
  (alength (.getBytes s "UTF-8")))

(defn seconds-since-epoch->offset-date-time [seconds-since-epoch]
  (when (and seconds-since-epoch
             (number? seconds-since-epoch))
    (datetime/offset-date-time
     (.atZone (datetime/instant (* 1000 seconds-since-epoch))
              ZoneOffset/UTC))))

(defn parse-csv-vec [csv-vec]
  (->> csv-vec
       (remove (partial every? string/blank?))
       (into [])))

(defn parse-csv-string
  ([^String csv-string]
   (parse-csv-string csv-string {}))
  ([^String csv-string {:keys [from-file?]}]
   (cond-> (->> csv-string
                (csv/read-csv)
                (parse-csv-vec))
     (not from-file?) (update :metadata
                              assoc
                              :size-in-bytes (size-in-bytes csv-string)))))

(defn parse-csv-reader
  ([csv-file-readable]
   (parse-csv-reader csv-file-readable {}))
  ([csv-file-readable options]
   (-> csv-file-readable
       (slurp)
       (parse-csv-string options))))

(defn parse-csv-file [^String csv-file-path]
  (let [csv-file (io/file csv-file-path)
        parsed-csv (parse-csv-reader csv-file {:from-file? true})]
    {:data parsed-csv
     :metadata {:file-size-in-bytes (.length csv-file)
                :file-name (.getName csv-file)
                :file-extension (FilenameUtils/getExtension csv-file-path)
                :file-last-modified (seconds-since-epoch->offset-date-time
                                     (/ (.lastModified csv-file) 1000))}}))

(defn row->entry [header row]
  (zipmap header row))

(defn fill-paragraph [max-content-line-length s]
  (->> (string/split s #"\s")
       (reduce (fn [{:keys [current-sentence-idx sentences]} word]
                 (if-let [sentence (nth sentences current-sentence-idx nil)]
                   (if (> (+ (.length word) (.length sentence))
                          max-content-line-length)
                     {:current-sentence-idx (inc current-sentence-idx)
                      :sentences (conj sentences word)}
                     {:current-sentence-idx current-sentence-idx
                      :sentences (update sentences
                                         current-sentence-idx
                                         str
                                         " "
                                         word)})
                   {:current-sentence-idx current-sentence-idx
                    :sentences (conj sentences word)}))
               {:current-sentence-idx 0
                :sentences []})
       (:sentences)))

(defn entry->org-subtree
  [{:keys [content-max-line-length
           heading-max-length
           heading-timestamp-enabled
           heading-timestamp-show-time]}
   {:keys [full-date date weekday time mood activities note-title note]
    :as entry}]
  (let [created-property (str "[" full-date " " (subs weekday 0 3) " " time "]")

        heading-timestamp (str "["
                               full-date
                               " "
                               (subs weekday 0 3)
                               (if heading-timestamp-show-time
                                 (str " " time)
                                 "")
                               "]")
        heading-note (string/replace note #"   " " ")
        heading (str "*"
                     " "
                     (if heading-timestamp-enabled
                       (str heading-timestamp " ")
                       "")
                     (or (and (not (string/blank? note-title))
                              note-title)
                         (and (not (string/blank? heading-note))
                              (truncate heading-note heading-max-length))
                         "<empty note>")
                     " "
                     ":" mood ":")
        properties (str "  :PROPERTIES:"
                        "\n"
                        "  :CREATED:  " created-property
                        "\n"
                        "  :END:")
        activity-list (->> (string/split activities #" \| ")
                           (map (fn [activity]
                                  (str "  - " activity)))
                           (string/join "\n"))]
    (str heading
         "\n"
         properties
         "\n"
         "\n"
         activity-list
         (or (and (not (string/blank? note))
                  (str "\n"
                       "\n"
                       (->> #"\s{2,3}"
                            (string/split note)
                            (map (fn [note-line]
                                   (->> note-line
                                        (fill-paragraph content-max-line-length)
                                        (map (fn [line]
                                               (str "  " line)))
                                        (string/join "\n"))))
                            (string/join "\n\n"))))
             ""))))

(defn daylio-csv-to-org
  [{:keys [daylio-csv-input org-output] :as options}]
  (let [{:keys [data metadata]} (parse-csv-file daylio-csv-input)
        [header* & rows] data
        header (map (comp keyword csk/->kebab-case) header*)
        org-subtrees (map (comp (partial entry->org-subtree
                                         (select-keys
                                          options
                                          (mu/keys EntryToOrgSubtreeOptions)))
                                (partial row->entry header))
                          rows)]
    (spit org-output
          (str "#+TITLE: Daylio"
               "\n"
               "\n"
               "#+file_name: " (:file-name metadata)
               "\n"
               "#+last_modified: " (:file-last-modified metadata)
               "\n"
               "\n"
               (string/join "\n" org-subtrees)))))

(defn -main
  [& args]
  (let [config (m/decode Options args (mt/transformer malli-cli/cli-transformer))]
    (cond
      (:help config)
      (do
        (println (malli-cli/summary Options))
        (System/exit 0))

      (not (m/validate Options config))
      (do
        (println "Invalid command line args")
        (->> config
             (m/explain Options)
             (me/humanize)
             (pprint))
        (System/exit 1))

      :else
      (daylio-csv-to-org config))))
