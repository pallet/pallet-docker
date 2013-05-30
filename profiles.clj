{:dev {:dependencies [[ch.qos.logback/logback-classic "1.0.9"]]
       :checkout-deps-shares [:source-paths :test-paths :resource-paths
                              :compile-path]
       :plugins [[codox/codox.leiningen "0.6.4"]
                 [lein-marginalia "0.7.1"]]}
 :doc {:dependencies [[com.palletops/pallet-codox "0.1.0"]]
       :codox {:writer codox-md.writer/write-docs
               :output-dir "doc/0.1/api"
               :src-dir-uri "https://github.com/pallet/pallet-lxc/blob/develop"
               :src-linenum-anchor-prefix "L"}
       :aliases {"marg" ["marg" "-d" "doc/0.1/"]
                 "codox" ["doc"]
                 "doc" ["do" "codox," "marg"]}}
 :release
 {:plugins [[lein-set-version "0.3.0"]]
  :set-version
  {:updates [{:path "README.md" :no-snapshot true}]}}
 :no-checkouts {:checkout-shares ^:replace []} ; disable checkouts
 :clojure-1.5.0 {:dependencies [[org.clojure/clojure "1.5.1"]]}
 :vmfest {:dependencies [[com.palletops/pallet-vmfest "0.3.0-alpha.5"]]}}
