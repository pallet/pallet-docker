(ns pallet.compute.docker.protocols
  "Internal protocols")

(defprotocol ServiceHost
  (host-node [_] "Return the node for the service host")
  (host-user [_] "Return the user for the service host"))

(defprotocol TagCache
  (tag-cache [compute-service]
    "Provides a service wide atom for caching tags"))

(defprotocol ImageStore
  (create-image [compute-service node options])
  (delete-image [compute-service image-id]))

(defprotocol ImageRepository
  (publish-image [compute-service image-id options])
  (revoke-image [compute-service image-id options]))
