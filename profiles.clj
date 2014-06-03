{:dev {:dependencies [[ch.qos.logback/logback-classic "1.0.9"]
                      [com.palletops/crates "0.1.1"]]
       :checkout-deps-shares [:source-paths :test-paths :resource-paths
                              :compile-path]
       :plugins [[lein-pallet-release "RELEASE"]]}
 :provided {:dependencies [[org.clojure/clojure "1.6.0"]
                           [com.palletops/pallet "0.9.0-SNAPSHOT"]]} }
