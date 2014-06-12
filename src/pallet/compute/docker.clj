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
  (:refer-clojure :exclude [proxy])
  (:require
   [clojure.core.async :refer [<! chan]]
   [clojure.java.io :as io]
   [clojure.string :as string :refer [blank? split trim]]
   [clojure.tools.logging :as logging :refer [debugf tracef warnf]]
   [com.palletops.docker :as docker-api]
   [pallet.blobstore :as blobstore]
   [pallet.common.filesystem :as filesystem]
   [pallet.compute :as compute]
   [pallet.compute.docker.protocols :as impl
    :refer [ServiceHost TagCache ImageStore ImageRepository]]
   [pallet.compute.docker.tagfile :as tagfile]
   [pallet.compute.implementation :as implementation]
   [pallet.compute.protocols :as protocols]
   [pallet.compute.jvm :as jvm]
   [pallet.core.context :refer [with-domain]]
   [pallet.core.plan-state.in-memory :refer [in-memory-plan-state]]
   [pallet.crate.docker :as docker]
   [pallet.docker.execute :refer [docker-exec]]
   [pallet.docker.executor :refer [docker-executor]]
   [pallet.docker.file-upload :refer [docker-upload]]
   [pallet.environment :as environment]
   [pallet.node :as node]
   [pallet.plan :refer [execute-plan plan-fn]]
   [pallet.script :as script]
   [pallet.session :as session]
   [pallet.settings :refer [get-settings]]
   [pallet.stevedore :as stevedore :refer [script with-script-language]]
   [pallet.tag :refer [set-state-flag]]
   [pallet.utils :as utils :refer [maybe-assoc]]
   [pallet.utils.async :refer [from-chan go-try]])
  (:use
   [pallet.script :only [with-script-context]]
   [pallet.script.lib :only [sed-file]]
   [pallet.user :only [*admin-user*]]))


;;; ### Tags

;;; Tagging is used to keep track of various things pallet needs to
;;; know about a server, like its os-fmaily.

(def pallet-name-tag :pallet-name)
(def pallet-image-tag :pallet-image)
(def pallet-state-tag :pallet-state)

;;; # Protocol API wrappers
(defn create-image
  [compute-service node options]
  (impl/create-image compute-service node options))

(defn tag-cache
  [compute-service]
  (impl/tag-cache compute-service))

;;; # Service Implementation
(defn ssh-port [_] 22)

(defn primary-ip
 [inspect]
 (-> inspect :NetworkSettings :IPAddress))

(defn private-ip
 [_]
 nil)

(defn is-64bit?
 [_]
 true)

(defn os-family
  [inspect node]
  (or (if-let [image (protocols/node-tag
                      (:compute-service node) node pallet-image-tag)]
        (:os-family image))
      (if-let [m (.image_meta (:compute-service node))]
        (:os-family (get m (inspect :Image))))))

(defn os-version
  [inspect node]
  (or (if-let [image (protocols/node-tag
                      (:compute-service node) node pallet-image-tag)]
        (:os-version image))
      (if-let [m (.image_meta (:compute-service node))]
        (:os-version (get m (inspect :Image))))))

(defn run-state
 [inspect]
 (let [state (-> inspect :State)]
   (if (:Running state)
     :running
     :terminated)))

(defn id
  "Return the id for a node from a docker response."
  [inspect]
  (or (-> inspect :Id)
      (-> inspect :ID)))

(defn hostname
 [inspect]
 (or (first (:Names inspect))
     (-> inspect :Config :Hostname)
     (id inspect)))

(defn packager [inspect node]
  (or (if-let [image (protocols/node-tag
                      (:compute-service node) node pallet-image-tag)]
        (:packager image))
      (if-let [m (.image_meta (:compute-service node))]
        (:packager (get m (inspect :Image))))))

(defn hardware [node]
  {})

(defn image-user [inspect node]
  (debugf "image-user for %s" (inspect :Image))
  (or
   (if-let [image (protocols/node-tag
                   (:compute-service node) node pallet-image-tag)]
     (if (:login-user image)
       (-> {:username (:login-user image)}
           (maybe-assoc :private-key (:login-private-key image)))))
   ;; (select-keys
   ;;  ((.image_meta (:compute-service node))
   ;;   (:Image inspect))
   ;;  [:username :password :private-key :public-key
   ;;   :private-key-path :public-key-path])
   {:username (let [u (-> inspect :Config :User)]
                (if (blank? u)
                  "root"
                  u))}))

(defn proxy [node] nil)

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
(declare create-nodes*)
(declare remove-node)
(declare nodes)
(declare commit)


(deftype DockerService
    ;; host-node and host-user determine the node that is hosting the containers
    [host-node host-user port executor file-uploader
     environment tag-provider tag-cache image-meta]

  pallet.core.protocols/Closeable
  (close [_])

  pallet.compute.docker.protocols/ServiceHost
  (host-node [_] host-node)
  (host-user [_] host-user)

  pallet.compute.docker.protocols/TagCache
  (tag-cache [compute-service] tag-cache)

  protocols/ComputeService
  (nodes [compute-service ch]
    (with-domain :docker
      (go-try ch
        (>! ch {:targets
                (nodes compute-service executor host-node host-user port)}))))

  protocols/ComputeServiceNodeCreateDestroy

  (images [_ ch]
    (with-domain :docker
      (go-try ch
        (>! ch {:images []}))))

  (create-nodes
    [service node-spec user node-count {:keys [node-name] :as options} ch]
    (when-not (every? (:image node-spec) [:os-family :os-version :login-user])
      (throw
       (ex-info
        "node-spec :image must contain :os-family :os-version :login-user keys"
        {:supplied (select-keys (:image node-spec)
                                [:os-family :os-version :login-user])})))
    (with-domain :docker
      (go-try ch
        (let [c (chan)
              nodes (do
                      (compute/nodes service c)
                      (:targets (<! c)))
              current-nodes nodes
              ;; (filter #(= node-name (node/-name %)) nodes)
              ]
          (>! ch {:new-targets
                  (create-nodes*
                   service executor host-node host-user
                   node-spec
                   (or (-> node-spec :image :init)
                       "/bin/bash"
                       ;; "/usr/sbin/sshd -D"
                       )
                   options
                   node-count)})))))

  (destroy-nodes [service nodes ch]
    (with-domain :docker
      (go-try ch
        (doseq [n nodes]
          (remove-node service host-node host-user n))
        (>! ch {:old-targets nodes}))))

  ;; protocols/ComputeServiceNodeStop
  ;; (stop-nodes
  ;;   [compute nodes ch]
  ;;   (with-domain :docker
  ;;     (>! ch {})))

  ;; (restart-nodes
  ;;   [compute nodes ch]
  ;;   (with-domain :ec2
  ;;     (>! ch {})))


  pallet.environment.protocols.Environment
  (environment [_] environment)

  ImageStore
  (create-image [compute-service node options]
    (commit compute-service executor host-node host-user node options))

  protocols/NodeTagReader
  (node-tag [compute node tag-name]
    (pallet.compute.protocols/node-tag @tag-provider node tag-name))
  (node-tag [compute node tag-name default-value]
    (pallet.compute.protocols/node-tag @tag-provider node tag-name default-value))
  (node-tags [compute node]
    (pallet.compute.protocols/node-tags @tag-provider node))

  protocols/NodeTagWriter
  (tag-node! [compute node tag-name value]
    (pallet.compute.protocols/tag-node! @tag-provider node tag-name value))
  (node-taggable? [compute node]
    (pallet.compute.protocols/node-taggable? @tag-provider node))

  protocols/ComputeServiceProperties
  (service-properties [_]
    {:provider :docker
     :node host-node
     :user host-user
     :environment environment})

  protocols/JumpHosts
  (jump-hosts [_]
    [{:endpoint
      {:server (node/node-address host-node)
       :port (node/ssh-port host-node)}}]))

;;; ## Service Implementation
(defn- host-command
  [compute-service host-node host-user plan-fn]
  {:pre [host-node host-user]}
  (let [session (session/create
                 {:user host-user
                  :plan-state (in-memory-plan-state
                               {:host
                                {(:id host-node)
                                 {:docker/tags
                                  {nil
                                   @(tag-cache compute-service)}}}})
                  :executor (docker-executor)
                  :action-options {:file-uploader (docker-upload {})}})
        {:keys [results]} (execute-plan
                           session
                           host-node
                           plan-fn)]
    (-> results last :result last)))

(defn- container-command
  [compute-service node user plan-fn]
  {:pre [node user]}
  (let [node (merge {:os-family :ubuntu :os-version "13.10" :packager :apt}
                    node)
        session (session/create
                 {:user user
                  :plan-state (in-memory-plan-state
                               {:host
                                {(:id node)
                                 {:docker/tags
                                  {nil
                                   @(tag-cache compute-service)}}}})
                  :executor (docker-executor)
                  :action-options {:file-uploader (docker-upload {})}})
        {:keys [action-results] :as result} (execute-plan
                                             session
                                             node
                                             plan-fn)]
    (debugf "result %s" (pr-str result))
    (last action-results)))

(defn- script-output
  [result]
  (debugf "script-output %s" (pr-str result))
  (let [{:keys [error exit out]} result]
    (when-not (zero? exit)
      (throw (ex-info "Command failed" result)))
    out))

(defn- node-map [inspect node-name compute]
  {:pre [compute inspect]}
  (debugf "node-map %s" (pr-str inspect))
  (as-> {:id (id inspect)
         :primary-ip (primary-ip inspect)
         :hostname (hostname inspect)
         :run-state (run-state inspect)
         :ssh-port 22
         :hardware (hardware inspect)
         :compute-service compute
         :action-options {:executor (.executor compute)}
         :provider-data {:host-node (.host_node compute)
                         :host-user (.host_user compute)}}
        n
        (merge
         {:os-family (os-family inspect n)
          :os-version (os-version inspect n)
          :packager (packager inspect n)
          :image-user (image-user inspect n)}
         n)
        (maybe-assoc n :proxy (proxy inspect))))

(defn- create-node
  "Instantiates a compute node on docker and runs the supplied init script.

  The node will be named 'machine-name', and will be built according
  to the supplied 'model'. This node will boot from the supplied
  'image' and will belong to the supplied 'group'.

  We would like to bind mount the admin user so we have known credentials to
  login, but I think this is only available on the ubuntu template. Passing
  a ssh public key for the default user seems to be more supported."
  [compute-service executor host-node host-user group-name image-id ports
   bootstrapped image cmd options]

  (let [{:keys [status body] :as r} (docker-exec
                                     (.transport executor)
                                     host-node host-user
                                     {:command :image-create
                                      :fromImage image-id
                                      :registry "https://index.docker.io/"})]
    (debugf "Image pull %s" (pr-str r)))

  (let [options (merge {:detached true
                        :port (or ports [22])}
                       options)
        {:keys [status body] :as r} (docker-exec
                                     (.transport executor)
                                     host-node host-user
                                     {:command :container-create
                                      :Image image-id
                                      :Cmd [cmd]
                                      :AttachStdout false
                                      :AttachStderr false
                                      :AttachStdin false
                                      :OpenStdin true})]
    (debugf "create-node create %s" (pr-str r))
    (if (= 201 status)
      (let [id (:Id body)
            {:keys [status body] :as r} (docker-exec
                                         (.transport executor)
                                         host-node host-user
                                         {:command :container-start
                                          :id id})]
        (debugf "create-node start %s" (pr-str r))
        (if (= 204 status)
          (let [{:keys [status body] :as r}
                (docker-exec
                 (.transport executor)
                 host-node host-user
                 {:command :container
                  :id id})
                node (assoc (node-map body group-name compute-service)
                       :os-family (:os-family image)
                       :os-version (:os-version image)
                       :packager (:packager image)
                       :image-user {:username (:login-user image)})]
            (node/tag! node :pallet/group-name group-name)
            (node/tag! node pallet-image-tag image)
            (when bootstrapped
              (set-state-flag node :bootstrapped))
            node)
          (warnf "create-node start failed %s" (pr-str r))))
      (warnf "create-node create failed %s" (pr-str r)))))

(defn create-nodes*
  [compute-service executor host-node host-user group-spec cmd options
   node-count]
  (debugf "create-nodes %s nodes %s" node-count group-spec)
  (->> (for [i (range node-count)
               :let [node (create-node
                           compute-service executor host-node host-user
                           (:group-name group-spec)
                           (-> group-spec :image :image-id)
                           (-> group-spec :network :inbound-ports)
                           (-> group-spec :image :bootstrapped)
                           (-> group-spec :image)
                           cmd options)]]
         node)
       (filterv identity)))

(defn- remove-node
  "Removes a compute node on docker."
  [compute-service host-node host-user node]
  (debugf "remove-node %s" node)
  (let [{:keys [results]}
        (execute-plan
         (session/create {:user host-user})
         {:node host-node}
         (plan-fn [session] (docker/kill session (node/id node))))
        {:keys [error exit out]} (-> results last :result last)]
    (when-not (zero? exit)
      (throw (ex-info (str "Removing " (node/id node) " failed"))))))


(defn image-tag
  "Return the image tag for a group"
  [group-spec]
  (pr-str (-> group-spec
              :image
              (select-keys
               [:image-id :os-family :os-version :os-64-bit
                :login-user :packager]))))

(defn set-node-tags
  "Update the tags on a node to match the given group-spec image and
  node-state."
  [compute node group-spec node-state]
  (protocols/tag-node! compute node
                       pallet-image-tag (image-tag group-spec))
  ;; (protocols/tag-node! compute node
  ;;                      pallet-state-tag (pr-str node-state))
  )

(defn nodes
  "Return a sequence of node maps."
  [compute executor host-node host-user port]
  (let [{:keys [body]} (docker-exec
                        (.transport executor)
                        host-node host-user {:command :containers})
        inspect->node (fn [inspect]
                        (tracef "building node for %s" inspect)
                        (let [node (node-map inspect nil compute)
                              _ (debugf "node %s" (pr-str node))
                              group-name (if (node/node? node)
                                           (protocols/node-tag
                                            compute node :pallet/group-name))]
                          (tracef
                           "group-name for %s %s" (:Id inspect) group-name)
                          (node-map inspect group-name compute)))]
    ;; (when-let [e (:cause error)]
    ;;   (clojure.stacktrace/print-stack-trace e))
    (tracef "body %s" body)
    (debugf "body %s" body)
    (map inspect->node body)))

(defn commit
  [compute executor host-node host-user node options]
  {:pre [compute host-node host-user node]}
  (let [{:keys [body] :as r} (docker-exec
                              (.transport executor)
                              host-node host-user
                              (docker-api/commit-map
                               (merge
                                {:container (node/id node)}
                                options)))]
    ;; (when-let [e (:cause error)]
    ;;   (clojure.stacktrace/print-stack-trace e))
    (debugf "commit results %s" (pr-str r))
    ;; (if (zero? exit)
    ;;   (trim out)
    ;;   (throw (ex-info (str "Commit image " (pallet.node/id node) " failed")
    ;;                   {:node (pallet.node/id node)})))
    body))

(defn ensure-tags
  [^DockerService service node]
  (tracef "ensure-tags")
  (when-not @(tag-cache service)
    (let [;; host-node (.host_node service)
          ;; host-user (.host_user service)
          last-cmd (container-command
                    service node pallet.user/*admin-user* ; host-node host-user
                    (plan-fn [session] (tagfile/read-tags session)))]
      (debugf "ensure-tags %s" last-cmd)
      (reset!
       (tag-cache service)
       (dissoc last-cmd :action-symbol :context))))
  (tracef "ensure-tags %s" @(tag-cache service)))

(defn- set-host-tag
  [compute-service node user node-id tag value]
  (swap! (tag-cache compute-service)
         assoc-in [node-id (keyword tag)] value)
  (script-output
   (container-command compute-service node user
                 (plan-fn [session]
                   (tracef
                    "set-host-tag existing tags %s"
                    (get-settings session :docker/tags))
                   (tagfile/set-tag session node-id tag value)))))


(deftype TagfileNodeTag [^DockerService service]
  protocols/NodeTagReader
  (node-tag [_ node tag-name]
    (ensure-tags service node)
    (get-in @(tag-cache service) [(:id node) (keyword tag-name)]))
  (node-tag [_ node tag-name default-value]
    (ensure-tags service node)
    (get-in @(tag-cache service) [(:id node) (keyword tag-name)]
            default-value))
  (node-tags [_ node]
    (ensure-tags service node)
    (get @(tag-cache service) (:id node)))
  protocols/NodeTagWriter
  (tag-node! [_ node tag-name value]
    (ensure-tags service node)
    (set-host-tag service node pallet.user/*admin-user*
                  (:id node) (keyword tag-name) value))
  (node-taggable? [_ node] true))


;;;; Compute service SPI
(defn supported-providers []
  ["docker"])

(defn docker-service
  [{:keys [node user port environment tag-provider image-meta]
    :or {user *admin-user*}
    :as options}]
  (let [tag-provider (atom tag-provider)
        executor (docker-executor)
        file-uploader (docker-upload {})
        service (DockerService.
                 node user port
                 executor file-uploader
                 environment tag-provider (atom nil) image-meta)]
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
