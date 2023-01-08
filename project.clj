(defproject daylio-csv-to-org "0.0.0"
  :description "A library and CLI to convert Daylio CSV exports to Org Mode files."
  :url "https://github.com/mpereira/daylio-csv-to-org"
  :license {:name "MIT"
            :url  "https://github.com/mpereira/daylio-csv-to-org/README.org#license"}
  :dependencies [[org.clojure/data.csv "1.0.1"]
                 [clojure.java-time "0.3.3"]
                 [commons-io/commons-io "2.11.0"]
                 [camel-snake-kebab "0.4.3"]
                 [metosin/malli "0.9.2"]
                 [com.github.piotr-yuxuan/malli-cli "2.0.0"]]
  :main daylio-csv-to-org.core
  :PLUGINS [[lein-cloverage "1.0.13"]
            [lein-shell "0.5.0"]
            [lein-ancient "0.6.15"]
            [lein-changelog "0.3.2"]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.10.0"]]}}
  :deploy-repositories [["releases" :clojars]]
  :aliases {"update-readme-version" ["shell" "sed" "-i" "s/\\\\[daylio-csv-to-org \"[0-9.]*\"\\\\]/[daylio-csv-to-org \"${:version}\"]/" "README.md"]}
  :release-tasks [["shell" "git" "diff" "--exit-code"]
                  ["change" "version" "leiningen.release/bump-version"]
                  ["change" "version" "leiningen.release/bump-version" "release"]
                  ["changelog" "release"]
                  ["update-readme-version"]
                  ["vcs" "commit"]
                  ["vcs" "tag"]
                  ["deploy"]
                  ["vcs" "push"]])
