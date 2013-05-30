(ns pallet.compute.docker
  "The docker provider allows Pallet to use and configure Docker containers.

For configuration we assume any image has an SSH server installed.  During
configuration the SSH server is run as PID 1.  By default, pallet starts
containers with the `/usr/sbin/sshd -D` command, which can be overridden in
the `:init` key of the `:image`.

The pallet/ubuntu image has openssh-server installed, and the /var/run/sshd
directory created.  Password is pallet.

Docker doesn't have support for setting any sort of per node metadata, so we
have to maintain this in a file on the host node.

Links
-----

http://docker.io"
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string :refer [blank? split trim]]
   [clojure.tools.logging :as logging]
   [pallet.action-plan :as action-plan]
   [pallet.api :refer [lift-nodes plan-fn]]
   [pallet.core.api-impl :refer [with-script-for-node]]
   [pallet.blobstore :as blobstore]
   [pallet.common.filesystem :as filesystem]
   [pallet.compute :as compute]
   [pallet.compute.docker.tagfile :as tagfile]
   [pallet.compute.implementation :as implementation]
   [pallet.compute.jvm :as jvm]
   [pallet.crate.docker :as docker]
   [pallet.environment :as environment]
   [pallet.execute :as execute]
   [pallet.futures :as futures]
   [pallet.node :as node]
   [pallet.script :as script]
   [pallet.stevedore :as stevedore :refer [script with-script-language]]
   [pallet.utils :as utils])
  (:use
   [pallet.compute.node-list :only [make-node]]
   [pallet.script :only [with-script-context]]
   [pallet.script.lib :only [sed-file]]
   [pallet.core.user :only [*admin-user*]]))




(deftype DockerNode
    [inspect group-name service]
  pallet.node/Node

  (ssh-port [_] 22)

  (primary-ip
    [_]
    (-> inspect :NetworkSettings :IpAddress))

  (private-ip [_] nil)

  (is-64bit?
    [_]
    true)

  (group-name
    [_]
    (or group-name "unknown"))

  (hostname
    [_]
    (-> inspect :Config :Hostname))

  (os-family
    [_])

  (os-version
    [_]
    )

  (running?
    [_]
    (-> inspect :State :Running))

  (terminated? [_]
    (not (-> inspect :State :Running)))

  (id [_] (-> inspect :Id))
  (compute-service [_] service)
  pallet.node.NodePackager
  (packager [node]
    nil)
  pallet.node.NodeHardware
  (hardware [node]
    nil)
  pallet.node.NodeImage
  (image-user [node]
    {:username (let [u (-> inspect :Config :User)]
                 (if (blank? u) "root" u))})
  pallet.node.NodeProxy
  (proxy [node]
      {:host (node/primary-ip (.host_node service))
       :port (if-let [p (-> inspect :NetworkSettings :PortMapping :22)]
               (if-not (blank? p) (Integer/parseInt p)))}))

(defn- nil-if-blank [x]
  (if (string/blank? x) nil x))

(defn- current-time-millis []
  (System/currentTimeMillis))

(defn- adjust-network-config
  "Adjusts the container configuration to use the specified network
  configuration."
  [compute-service node-name network-config]
  (let [config-file (format "/var/lib/docker/%s/config" node-name)]
    ;; (docker-exec
;;      compute-service
;;      (script
;;       ;; remove any existing network configuration
;;       (sed-file
;;        ~config-file
;;        {"docker.network.*" ""})
;;       ~(case (:network-type network-config)
;;          :bridged (script ("sed" "'1i\n
;; docker.network.type=veth
;; docker.network.link=dockerbr0
;; docker.network.flags=up
;; '" ))
;;          :local (script ("sed" "'1i\n
;; docker.network.type=veth
;; docker.network.link=dockerbr0
;; docker.network.flags=up
;; '")))))
    )
  (assert false "not yet implemented"))


;;; # Service
(declare create-nodes)
(declare remove-node)
(declare nodes)
(declare commit)

(defprotocol ImageStore
  (create-image [compute-service node options])
  (delete-image [compute-service image-id]))

(defprotocol ImageRepository
  (publish-image [compute-service image-id options])
  (revoke-image [compute-service image-id options]))

(deftype DockerService
    ;; host-node and host-user determine the node that is hosting the containers
    [host-node host-user environment]
  pallet.compute/ComputeService
  (nodes [compute-service]
    (nodes compute-service host-node host-user))

  (ensure-os-family [compute-service group-spec]
    (->
     group-spec
     (update-in [:image :image-id] #(or % :ubuntu))
     (update-in [:image :os-family] #(or % :ubuntu)))
    group-spec)

  (run-nodes
    [compute-service group-spec node-count user init-script options]
    (let [nodes (compute/nodes compute-service)
          group-name (:group-name group-spec)
          current-nodes (filter #(= group-name (node/group-name %)) nodes)]
      (create-nodes
       compute-service host-node host-user
       group-spec
       (or (-> group-spec :image :init) "/usr/sbin/sshd -D")
       options
       node-count)))

  (reboot
    [compute nodes]
    )

  (boot-if-down
    [compute nodes]
    )

  (shutdown-node
    [compute node _]
    ;; todo: wait for completion
    (logging/infof "Shutting down %s" (pr-str node))
    )

  (shutdown
    [compute nodes user]
    )

  (destroy-nodes-in-group
    [compute group-name]
    (let [nodes (compute/nodes compute)]
      (doseq [n nodes
              :when (= (name group-name) (name (node/group-name n)))]
        (compute/destroy-node compute n))))

  (destroy-node
    [compute node]
    {:pre [node]}
    (remove-node compute host-node host-user node))

  (images [compute]
    )

  (close [compute])
  pallet.environment.Environment
  (environment [_] environment)
  ContainerImage
  (create-image [compute-service node options]
    (commit compute-service host-node host-user node options)))

;;; ## Service Implementation
(defn- host-command
  [compute-service host-node host-user & plan-fns]
  (let [{:keys [results]} (lift-nodes
                           [{:node host-node}] plan-fns :user host-user)]
    (-> results last :result last)))

(defn- script-output
  [result]
  (let [{:keys [error exit out]} result]
    (when-not (zero? exit)
      (throw (ex-info "Command failed" result)))
    out))

(defn- create-node
  "Instantiates a compute node on docker and runs the supplied init script.

  The node will be named 'machine-name', and will be built according
  to the supplied 'model'. This node will boot from the supplied
  'image' and will belong to the supplied 'group'.

  We would like to bind mount the admin user so we have known credentials to
  login, but I think this is only available on the ubuntu template. Passing
  a ssh public key for the default user seems to be more supported."
  [compute-service host-node host-user group-name image-id cmd options]
  (let [options (merge {:detached true :port 22} options)
        {:keys [results]}
        (lift-nodes [{:node host-node}]
                    [(plan-fn (docker/run image-id cmd options))]
                    :user host-user)
        {:keys [error exit out]} (-> results last :result last)]
    (when (zero? exit)
      (DockerNode. out group-name compute-service))))

(defn- set-host-tag
  [compute-service host-node host-user node-id tag value]
  (script-output
   (host-command compute-service host-node host-user
                 (plan-fn (tagfile/set-tag node-id tag value)))))

(defn create-nodes
  [compute-service host-node host-user group-spec cmd options node-count]
  (->> (doseq [i (range node-count)
               :let [node (create-node
                           compute-service host-node host-user
                           (:group-name group-spec)
                           (-> group-spec :image :image-id)
                           cmd options)]]
         (set-host-tag
          compute-service host-node host-user
          (node/id node) :pallet/group-name (:group-name group-spec))
         node)
       (filter identity)))

(defn- remove-node
  "Removes a compute node on docker."
  [compute-service host-node host-user node]
  (let [{:keys [results]}
        (lift-nodes [{:node host-node}]
                    [(plan-fn (docker/kill (node/id node)))]
                    :user host-user)
        {:keys [error exit out]} (-> results last :result last)]
    (when-not (zero? exit)
      (throw (ex-info (str "Removing " (node/id node) " failed"))))))

(defn nodes
  [compute host-node host-user]
  (let [{:keys [results]}
        (lift-nodes [{:node host-node}]
                    [(plan-fn (docker/nodes))]
                    :user host-user)
        {:keys [error exit out]} (-> results last :result last)
        inspect->node (fn [inspect]
                        (clojure.tools.logging/infof
                         "building node for %s"
                         inspect)
                        (let [group-name (-> (host-command
                                              compute host-node host-user
                                              (plan-fn (tagfile/read-tags))
                                              (plan-fn (tagfile/get-tag
                                                        (:Id inspect)
                                                        :pallet/group-name)))
                                             :value)]
                          (clojure.tools.logging/infof
                           "group-name for %s %s"
                           (:Id inspect) group-name)
                          (DockerNode. inspect group-name compute)))]
    (when-let [e (:cause error)]
      (clojure.stacktrace/print-stack-trace e))
    (clojure.tools.logging/infof "results %s" (vec results))
    (when (zero? exit)
      (map inspect->node out))))

(defn commit
  [compute host-node host-user node options]
  (let [{:keys [results]}
        (lift-nodes [{:node host-node}]
                    [(plan-fn (docker/commit (pallet.node/id node) options))]
                    :user host-user)
        {:keys [error exit out]} (-> results last :result last)]
    (when-let [e (:cause error)]
      (clojure.stacktrace/print-stack-trace e))
    (clojure.tools.logging/infof "results %s" (vec results))
    (if (zero? exit)
      (trim out)
      (throw (ex-info (str "Commit image " (pallet.node/id node) " failed")
                      {:node (pallet.node/id node)})))))

;;;; Compute service SPI
(defn supported-providers []
  ["docker"])


(defn docker-service
  [{:keys [node user environment]
    :or {user *admin-user*}
    :as options}]
  (let [] ;; todo. Automatically discover this
    (DockerService. node user environment)))

(defmethod implementation/service :docker
  [_ {:keys [node user environment]
      :as options}]
  (docker-service options))



;;; TODO
;;; check host support for docker with docker-checkconfig

(comment
  (require 'pallet.configure)
  (require 'pallet.compute.vmfest)
  (require 'pallet.compute.docker)
  (def vb (pallet.configure/compute-service :vb4))
  (def host (first (pallet.compute/nodes vb)))
  (def s (pallet.compute/instantiate-provider
          :docker :node host :user pallet.core.user/*admin-user*))
  (pallet.compute/nodes s))
