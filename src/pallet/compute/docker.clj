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
   [clojure.tools.logging :as logging :refer [debugf tracef]]
   [pallet.action-plan :as action-plan]
   [pallet.actions :refer [as-action]]
   [pallet.api :refer [lift-nodes plan-fn]]
   [pallet.core.api-impl :refer [with-script-for-node]]
   [pallet.crate :refer [get-settings]]
   [pallet.blobstore :as blobstore]
   [pallet.common.filesystem :as filesystem]
   [pallet.compute :as compute]
   [pallet.compute.docker.protocols :refer :all]
   [pallet.compute.docker.tagfile :as tagfile]
   [pallet.compute.implementation :as implementation]
   [pallet.compute.jvm :as jvm]
   [pallet.core.api :refer [set-state-for-node state-tag-name]]
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
    [host-node host-user environment tag-provider tag-cache]

  pallet.compute.docker.protocols/ServiceHost
  (host-node [_] host-node)
  (host-user [_] host-user)
  pallet.compute.docker.protocols/TagCache
  (tag-cache [compute-service] tag-cache)
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
    (debugf "Shutting down %s" (pr-str node))
    )

  (shutdown
    [compute nodes user]
    )

  (destroy-nodes-in-group
    [compute group-name]
    (debugf "destroy-nodes-in-group %s" group-name)
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
  ImageStore
  (create-image [compute-service node options]
    (commit compute-service host-node host-user node options))
  pallet.compute.NodeTagReader
  (node-tag [compute node tag-name]
    (compute/node-tag @tag-provider node tag-name))
  (node-tag [compute node tag-name default-value]
    (compute/node-tag @tag-provider node tag-name default-value))
  (node-tags [compute node]
    (compute/node-tags @tag-provider node))
  pallet.compute.NodeTagWriter
  (tag-node! [compute node tag-name value]
    (compute/tag-node! @tag-provider node tag-name value))
  (node-taggable? [compute node]
    (compute/node-taggable? @tag-provider node))
  pallet.compute.ComputeServiceProperties
  (service-properties [_]
    {:provider :docker
     :node host-node
     :user host-user
     :environment environment}))

;;; ## Service Implementation
(defn- host-command
  [compute-service host-node host-user & plan-fns]
  (let [{:keys [results]}
        (lift-nodes
         [{:node host-node}] plan-fns :user host-user
         :plan-state
         {:host
          {(node/id host-node)
           {:docker/tags
            {nil
             @(tag-cache compute-service)}}}})]
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
  [compute-service host-node host-user group-name image-id ports bootstrapped
   cmd options]
  (let [options (merge {:detached true
                        :port (or ports [22])}
                       options)
        {:keys [results]}
        (lift-nodes [{:node host-node}]
                    [(plan-fn (docker/run image-id cmd options))]
                    :user host-user)
        {:keys [error exit out]} (-> results last :result last)]
    (when (zero? exit)
      (let [node (DockerNode. out group-name compute-service)]
        (node/tag! node :pallet/group-name group-name)
        (when bootstrapped
          (set-state-for-node :bootstrapped {:node node}))
        node))))

(defn create-nodes
  [compute-service host-node host-user group-spec cmd options node-count]
  (->> (for [i (range node-count)
               :let [node (create-node
                           compute-service host-node host-user
                           (:group-name group-spec)
                           (-> group-spec :image :image-id)
                           (-> group-spec :network :inbound-ports)
                           (-> group-spec :image :bootstrapped)
                           cmd options)]]
         node)
       (filter identity)))

(defn- remove-node
  "Removes a compute node on docker."
  [compute-service host-node host-user node]
  (debugf "remove-node %s" node)
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
                        (tracef "building node for %s" inspect)
                        (let [node (DockerNode. inspect nil compute)
                              group-name (node/tag node :pallet/group-name)]
                          (tracef
                           "group-name for %s %s" (:Id inspect) group-name)
                          (DockerNode. inspect group-name compute)))]
    (when-let [e (:cause error)]
      (clojure.stacktrace/print-stack-trace e))
    (tracef "results %s" (vec results))
    (when (zero? exit)
      (map inspect->node out))))

(defn commit
  [compute host-node host-user node options]
  {:pre [compute host-node host-user node]}
  (let [{:keys [results]}
        (lift-nodes [{:node host-node}]
                    [(plan-fn (docker/commit (pallet.node/id node) options))]
                    :user host-user)
        {:keys [error exit out]} (-> results last :result last)]
    (when-let [e (:cause error)]
      (clojure.stacktrace/print-stack-trace e))
    (tracef "results %s" (vec results))
    (if (zero? exit)
      (trim out)
      (throw (ex-info (str "Commit image " (pallet.node/id node) " failed")
                      {:node (pallet.node/id node)})))))

(defn ensure-tags
  [^DockerService service]
  (tracef "ensure-tags")
  (when-not @(tag-cache service)
    (let [last-cmd (host-command
                    service (host-node service) (host-user service)
                    (plan-fn
                      (tagfile/read-tags)))]
      (reset!
       (tag-cache service)
       (dissoc last-cmd :action-symbol :context))))
  (tracef "ensure-tags %s" @(tag-cache service)))

(defn- set-host-tag
  [compute-service host-node host-user node-id tag value]
  (swap! (tag-cache compute-service)
         assoc-in [node-id (keyword tag)] value)
  (script-output
   (host-command compute-service host-node host-user
                 (plan-fn
                   (tracef
                    "set-host-tag existing tags %s"
                    (get-settings :docker/tags))
                   (tagfile/set-tag node-id tag value)))))


(deftype TagfileNodeTag [^DockerService service]
  pallet.compute.NodeTagReader
  (node-tag [_ node tag-name]
    (ensure-tags service)
    (get-in @(tag-cache service) [(node/id node) (keyword tag-name)]))
  (node-tag [_ node tag-name default-value]
    (ensure-tags service)
    (get-in @(tag-cache service) [(node/id node) (keyword tag-name)]
            default-value))
  (node-tags [_ node]
    (ensure-tags service)
    (get @(tag-cache service) (node/id node)))
  pallet.compute.NodeTagWriter
  (tag-node! [_ node tag-name value]
    (ensure-tags service)
    (set-host-tag service (host-node service) (host-user service)
                  (node/id node) (keyword tag-name) value))
  (node-taggable? [_ node] true))

;;;; Compute service SPI
(defn supported-providers []
  ["docker"])

(defn docker-service
  [{:keys [node user environment tag-provider]
    :or {user *admin-user*}
    :as options}]
  (let [tag-provider (atom tag-provider)
        service (DockerService. node user environment tag-provider (atom nil))]
    (when-not @tag-provider
      (reset! tag-provider (TagfileNodeTag. service)))
    service))

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
  (def s (pallet.compute/instantiate-provider :docker :node host))
  (pallet.compute/nodes s))
