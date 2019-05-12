(defproject com.taoensso/tufte "2.1.0-RC4"
  :author "Peter Taoussanis <https://www.taoensso.com>"
  :description "Simple profiling and performance monitoring for Clojure/Script"
  :url "https://github.com/ptaoussanis/tufte"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo
            :comments "Same as Clojure"}
  :min-lein-version "2.3.3"
  :global-vars {*warn-on-reflection* true
                *assert*             true}

  :dependencies
  [[org.clojure/clojure "1.7.0"]
   [com.taoensso/encore "2.106.0"]
   [amalloy/ring-buffer "1.3.0"]]

  :plugins
  [[lein-pprint    "1.2.0"]
   [lein-ancient   "0.6.15"]
   [lein-codox     "0.10.5"]
   [lein-cljsbuild "1.1.7"]]

  :profiles
  {;; :default [:base :system :user :provided :dev]
   :server-jvm {:jvm-opts ^:replace ["-server"]}
   :1.8      {:dependencies [[org.clojure/clojure "1.8.0"]]}
   :1.9      {:dependencies [[org.clojure/clojure "1.9.0"]]}
   :1.10     {:dependencies [[org.clojure/clojure "1.10.0"]]}
   :test     {:dependencies [[org.clojure/test.check "0.9.0"]]}
   :provided {:dependencies [[org.clojure/clojurescript "1.10.516"]]}
   :dev
   [:1.10 :test :server-jvm
    {:dependencies [[com.taoensso/timbre "4.10.0"]]}]}

  :cljsbuild
  {:test-commands
   {"node"    ["node" :node-runner "target/main.js"]
    "phantom" ["phantomjs" :runner "target/main.js"]}

   :builds
   [{:id :main
     :source-paths ["src" "test"]
     :compiler
     {:output-to "target/main.js"
      :optimizations :advanced
      :pretty-print false}}]}

  :aliases
  {"build-once" ["cljsbuild" "once"]
   "deploy-lib" ["do" "build-once," "deploy" "clojars," "install"]
   "start-dev"  ["with-profile" "+dev" "repl" ":headless"]}

  :repositories
  {"sonatype-oss-public" "https://oss.sonatype.org/content/groups/public/"})
