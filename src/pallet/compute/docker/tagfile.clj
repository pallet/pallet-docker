(ns pallet.compute.docker.tagfile
  "Manages a file on a docker host, containing tags for docker containers.

The tag file is a clojure datastructure, that is a map from node id to a map
from tag keywords to sting value.

The tags are kept in the settings for the docker host node.

Locking is taken care of at the node level, so there can be multiple docker
compute services talking to a single docker host."
  (:require
   [pallet.actions :refer [directory exec-script]]
   [pallet.plan :refer [defplan]]
   [pallet.script.lib :refer [cat dirname heredoc]]
   [pallet.settings :refer [assoc-settings get-settings update-settings]]
   [pallet.stevedore :refer [fragment]]
   [taoensso.timbre :refer [debugf]]))

(def facility :docker/tags)

(def settings
  {:tagfile "/var/lib/docker/pallet-tags"
   :lockfile "/var/lock/pallet-tags"})

(defplan read-tags
  "Read tags from the docker host node"
  [session]
  (debugf "read-tags")
  (let [s (exec-script
           session
           (if-not (file-exists? ~(:tagfile settings))
             (pipe (println "'{}'") ("cat" ">" ~(:tagfile settings))))
           (cat ~(:tagfile settings)))
        v (when (zero? (:exit s))
            (debugf "read-tags zero exit")
            (do                         ; binding [*read-eval* false]
              (debugf "read-tags bound *read-eval*")
              (debugf "read-tags %s" s)
              (let [r (read-string (:out s))]
                (debugf "read-tags %s" r)
                r)))]
    (assoc-settings session facility v)
    v))

;;; This API isn't sufficient to support correct locking.
;;; Really need functions to atomically add, remove and modify tags.
(defplan write-tags
  "Write tags to the docker host node"
  [session]
  ;; TODO use some form of CAS to prevent races
  (let [tags (get-settings session facility)]
    (debugf "write-tags %s" tags)
    (directory session (fragment @(dirname ~(:tagfile settings))) {})
    (exec-script
     session
     (heredoc ~(:tagfile settings) ~(pr-str tags) {:literal true}))))

(defn get-tag
  "Set tag on the docker host node"
  [session node-id key]
  (let [m (get-settings session facility)
        v (get-in m [node-id key])]
    (debugf "get-tag %s %s %s %s" node-id key m v)
    {:value v}))

(defplan set-tag
  "Set tag on the docker host node"
  [session node-id key value]
  (debugf "set-tag %s %s %s" node-id key value)
  (debugf "set-tag %s" (get-settings session facility))
  (update-settings session facility nil update-in [node-id] assoc key value)
  (write-tags session))

(defplan remove-tag
  "Remove tag on the docker host node"
  [session node-id key]
  (debugf "remove-tag %s %s" node-id key)
  (update-settings session facility nil update-in [node-id] dissoc key)
  (write-tags session))
