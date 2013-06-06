;;; Pallet project configuration file

(require
 '[pallet.compute.docker-test
   :refer [docker-test]]
 '[pallet.crates.test-nodes :refer [node-specs]])

(defproject riemann-crate
  :provider node-specs                  ; supported pallet nodes
  :groups [docker-test])
