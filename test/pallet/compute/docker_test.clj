(ns pallet.compute.docker-test
  (:require
   [clojure.pprint :refer [pprint]]
   [clojure.string :refer [blank?]]
   [clojure.tools.logging :refer [debugf]]
   [clojure.test :refer :all]
   [pallet.action-options :refer [with-action-options]]
   [pallet.actions :refer [exec-checked-script exec-script package
                           package-manager remote-file remote-file-content]]
   [pallet.compute :refer [instantiate-provider node-spec nodes]]
   [pallet.compute.docker :refer [create-image]]
   ;; [pallet.core.api :refer [phase-errors]]
   [pallet.core.plan-state :as plan-state]
   ;; [pallet.crate :refer [target-node]]
   [pallet.crate.automated-admin-user :as automated-admin-user]
   [pallet.crate.docker :as docker]
   [pallet.group :refer [converge group-spec phase-errors]]
   [pallet.node :as node]
   [pallet.plan :refer [plan-fn]]
   [pallet.script.lib :refer [download-file]]))

;;; # Docker Host Node

;;; #  Container
(def image-group
  (group-spec :image
    {:phases {:configure (plan-fn [session]
                           (package-manager session :universe)
                           (package-manager session :update)
                           (package session "nginx-light")
                           (remote-file session
                                        "/usr/share/nginx/www/abc"
                                        {:content "abc"})
                           (exec-checked-script
                            session
                            "Update nginx config"
                            ("cat" ">>" "/etc/nginx/nginx.conf"
                             "<< EOF\nmaster_process off;\ndaemon off;\nEOF")))}
     :node-spec (node-spec {:image {:image-id "pallet/ubuntu2"
                                    :os-family :ubuntu
                                    :os-version "13.10"
                                    :packager :apt
                                    :bootstrapped true}})}))

(defn image-container
  "Function to return the image-id of a docker image that has
  installed."
  [docker-service]
  (let [res (converge {image-group 1}
                      :compute docker-service
                      :phase [:install :configure]
                      :user {:username "root" :password "pallet"
                             :sudo-password "pallet"})]
    (when-let [e (phase-errors res)]
      (pprint e)
      (pprint res)
      (throw (ex-info "Problem starting container"
                      {:phase-errors e})))
    (let [node (:node (first (:targets res)))
          _ (assert node "No node reported from container")
          id (create-image docker-service node {:tag "pallet/docker-test"})]
      (converge {image-group 0} :compute docker-service)
      [id 80])))

(defn httpd-group
  "Return a group-spec to run  in a container based on the specified
  image-id."
  [image-id port]
  (group-spec :httpd
    :node-spec {:image {:image-id image-id
                        :init "/usr/sbin/nginx"}
                :network {:inbound-ports [80]}}))

(defn run-httpd
  "Run httpd in a docker container."
  [docker-service image-id port]
  (let [res (converge {(httpd-group image-id port) 1}
                      :compute docker-service
                      :phase [:install :configure]
                      :os-detect false)]
    (when-let [e (phase-errors res)]
      (pprint e)
      (throw (ex-info "Problem starting "
                      {:phase-errors e})))
    (:node (first (:targets res)))))

(defn remove-httpd
  "Remove httpd docker container."
  [docker-service]
  (let [res (converge {(httpd-group "pallet/docker-test" 80) 0}
                      :compute docker-service
                      :phase [:install :configure]
                      :os-detect false)]
    (when-let [e (phase-errors res)]
      (pprint e)
      (throw (ex-info "Problem starting "
                      {:phase-errors e})))))

;; (deftest docker-test
;;   (let [host (start-docker-host)
;;         docker (instantiate-provider :docker :node host)]
;;     (is (zero? (count (nodes docker))))
;;     (let [[image-id port] (image-container docker)
;;           node (run-httpd docker image-id port)]
;;       (destroy-node docker node))))

(def docker-test
  (group-spec :docker
    :extends [automated-admin-user/with-automated-admin-user
              (docker/server-spec {})]
    :phases
    {:test (plan-fn [session]
             (let [docker (instantiate-provider :docker :node (target-node))]
               (is (zero? (count (nodes docker))))
               (let [[image-id port] (image-container docker)
                     node (run-httpd docker image-id port)
                     local-port (if-let [p (-> (.inspect node) :NetworkSettings
                                               :PortMapping :80)]
                                  (if-not (blank? p) (Integer/parseInt p)))
                     _ (debugf "node is %s" (.inspect node))
                     url (str "http://localhost:" local-port "/abc")
                     _ (debugf "url is %s" url)]
                 (with-action-options {:script-trace true}
                   (exec-checked-script
                    "Download file"
                    ("sleep" 10)
                    ("wget" -v -O "/tmp/abc-from-container" ~url )
                    (download-file ~url "/tmp/abc-from-container"))
                   (let [v (remote-file-content "/tmp/abc-from-container")]
                     (with-action-values [v]
                       (is (= "abc" v))))))))
     :shutdown (plan-fn
                 (let [docker (instantiate-provider
                               :docker :node (target-node))]
                   (remove-httpd docker)))}
    :default-phases [:install :configure :test :shutdown]
    :roles #{:live-test :default}))
