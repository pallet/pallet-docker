(ns pallet.compute.docker.tagfile
  "Manages a file on a docker host, containing tags for docker containers.

The tag file is a clojure datastructure, that is a map from node id to a map
from tag keywords to sting value.

The tags are kept in the settings for the docker host node.

Locking is taken care of at the node level, so there can be multiple docker
compute services talking to a single docker host."
  (:require
   [clojure.tools.logging :refer [debugf]]
   [pallet.actions :refer [as-action assoc-settings exec-script
                           with-action-values]]
   [pallet.crate :refer [defplan get-settings update-settings]]
   [pallet.script.lib :refer [cat heredoc with-lock]]))

(def settings
  {:tagfile "/var/lib/docker/pallet-tags"
   :lockfile "/var/lock/pallet-tags"})

(defplan read-tags
  "Read tags from the docker host node"
  []
  (let [s (exec-script
           ;; with-lock (:lockfile settings) {:exclusive true}
           (if-not (file-exists? ~(:tagfile settings))
             (pipe (println "'{}'") ("cat" ">" ~(:tagfile settings))))
           (cat ~(:tagfile settings)))
        v (with-action-values [s]
            (when (zero? (:exit s))
              (binding [*read-eval* false]
                (read-string (:out s)))))]
    (assoc-settings :docker/tags v)))

;;; This API isn't sufficient to support correct locking.
;;; Really need functions to atomically add, remove and modify tags.
(defplan write-tags
  "Write tags to the docker host node"
  []
  ;; TODO use some form of CAS to prevent races
  (let [tags (get-settings :docker/tags)]
    (exec-script
     ;; with-lock ~(:lockfile settings) {:exclusive true}
     (heredoc ~(:tagfile settings) ~(pr-str tags) {:literal true}))))

(defn get-tag
  "Set tag on the docker host node"
  [node-id key]
  (let [m (get-settings :docker/tags)
        v (get-in m [node-id key])]
    (debugf "get-tag %s %s %s %s" node-id key m v)
    (as-action {:value v})))

(defplan set-tag
  "Set tag on the docker host node"
  [node-id key value]
  (update-settings :docker/tags update-in [node-id] assoc key value)
  (write-tags))

(defplan remove-tag
  "Set tag on the docker host node"
  [node-id key]
  (update-settings :docker/tags update-in [node-id] dissoc key)
  (write-tags))
