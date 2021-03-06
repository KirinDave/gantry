(ns gantry.core
  (:use [clojure.contrib.condition :only [raise]]
        clojure.set
        clojure.java.io
        clojure.contrib.str-utils
        gantry.log)
  (:require [clojure.string :as str]
            [clojure.java.shell :as shell]
            clojure.contrib.io
            clojure.contrib.java-utils))


(defn hash-flip [ht]
  (reduce #(assoc %1 (ht %2) %2) {} (keys ht)))

(defn default-ssh-identity []
   (.getPath (clojure.contrib.io/file (. System getProperty "user.home") ".ssh" "id_dsa")))

(defn logged-in-user 
  "Returns the currently logged in username"
  [] 
  (. System getProperty "user.name"))


(defn file-exists 
  [path]
  (. (clojure.contrib.java-utils/file path) exists))


(defn gen-ssh-cmd 
  "Generates an ssh command with the key id optionally supplied in id. Same for the port.

  Example:
    (gen-ssh-cmd) ;=> \"ssh\"
    (gen-ssh-cmd nil 22) ;=> \"ssh -p 22\"
  "
  [& [ id port]] 
    (concat 
      (if id
        ["ssh" "-o" "StrictHostKeyChecking no" "-i" id]
        ["ssh"])
      (if port
        ["-p" (str port)]
        [])))

        
(defn gen-host-addr [user host]
    (if user
      (str user "@" host)
      host))


(defn categorize-future [fut cb]
  (if (future-done? fut)
    :done
    :pending))


(defn gpmap 
  "Like pmap, but maps f over s calling cb when each future is done. This was done so that with
  cb, you can get immediate feedback when a future has completed. Its used for the * functions below.

  The default 'pause' is 100ms. This can be changed with the :timeout key.
  "
  [f cb s & {:keys [pause]}]
  (let [rets (map #(future (f % )) s)]
    (loop [pending rets done []]
      (if (> (count pending) 0)
        (do
          (Thread/sleep (if pause pause 100)) 
          (let [groups (group-by #(if (future-done? %) :done :pending) pending)]
            (when (> (count (get groups :done)))
              (doall (map #(cb (deref %)) (get groups :done))))
            (recur (get groups :pending) (concat done (get groups :done)))))
        (map #(deref %) done)))))


(defn- user [h] (:user h))

(defn- port [h] (:port h))

(defn- ssh-key [h] (:id h))


(defn remote 
  "Run cmd on host. Args include :user, :port, and :id keys for the user to connect with, the 
  port, and the ssh identity respectively. Example:

  (remote \"host.com\" \"yum install -y atop\")
  (remote \"host.com\" \"yum install -y atop\" {:user \"root\"})

  Returns a hashmap with keys :host, :exit, :out, and :err.
  
  "
  [host cmd & [args]]
  (do (debug 
        (format "==> sending '%s' to h=%s:%s user=%s id=%s" cmd host (port args) (user args) (ssh-key args)))
        (assoc 
          (apply shell/sh 
                 (flatten [(gen-ssh-cmd (ssh-key args) (port args)) 
                           (gen-host-addr (user args) host) cmd])) :host host)))



(defn remote* 
  "Invokes remote with cmd for each host in hosts. See remote. Returns a seq of HashMaps, one for
  each host in hosts.
  "
  [hosts cmd & [args]]
  (let [c (fn [h] (remote h cmd args))
        cb (get args :cb)
        vcb (if (fn? cb) cb (fn [r] r))]
    (gpmap c vcb hosts)))



(defn gen-rsync-cmd 
  "Generates the appropriate rsync command given the srcs, destination, and args. args is a HashMap 
  optionally containing :user, :port, and :id.

  Example:
    (gen-rsync-cmd \"localhost\" \"source-dir\" \"dest-dir\" {:port 22}) ; => [\"rsync\" \"-avzL\" \"-e\" \"ssh  -p  22\" \"source-dir\" \"localhost:dest-dir\"]
  "
  [host srcs dest & [args]]
  (if (or (ssh-key args) (port args))
    (let [e-arg (str-join "  " (gen-ssh-cmd (ssh-key args) (port args)))]
      (flatten ["rsync" "-avzL" 
                "-e" e-arg
                srcs (str (gen-host-addr (user args) host) ":" dest)]))
    (flatten ["rsync" "-avzL" 
              srcs (str (gen-host-addr (user args) host) ":" dest)])))
    

(defn upload [host srcs dest & [args]]
  "rsync's srcs to dest on the given host. srcs can be a single file or a seq of files. For example:

    (upload \"host.com\" \"filea\" \"/tmp\")
    (upload \"host.com\" [\"filea\", \"fileb\"] \"/tmp\")

  args supports :port, :user, and :id to specify the ssh port, the user to connect with and the ssh key to
  authenticate with.
  
  "
  (do (debug (format "==> uploading src %s to h=%s:%s => %s user=%s id=%s" 
                     (str srcs) host (port args) dest (user args) (ssh-key args)))
    (assoc 
      (apply shell/sh 
             (flatten [(gen-rsync-cmd host srcs dest args)])) :host host)))


(defn upload* 
  "Invokes upload with srcs and dest for each host in hosts. See upload Returns a seq of HashMaps, one for
  each host in hosts.
  "
  [hosts srcs dest & [args]]
  (let [c (fn [h] (upload h srcs dest args)) 
        cb (get args :cb)
        vcb (if (fn? cb) cb (fn [r] r))]
    (do 
      (debug (format "==> uploading src %s to h=%s:%s => %s user=%s id=%s" 
                     (str srcs) (str hosts) (port args) dest (user args) (ssh-key args)))
      (gpmap c vcb hosts))))


; ht to getwoven/crane
(defn split-cmd 
  [cmd]
  (if (string? cmd)
    (str/split cmd #"\s+")
    cmd))


(defn local 
  "Execute the given cmd locally.  Returns a hashmap with keys :exit, :out, and :err.
  "
  [cmd]
  (apply shell/sh (split-cmd cmd)))


(defn success? 
  "Check the result of a shell command to see if the exit code is 0."
  [result]
  (= 0 (:exit result)))


(defn validate 
  "Validates the command that was run. If it passes success? its valid, if not, an exception is 
  raised of :type :remote-failed. The message contains the STDERR of the result if there is any."
  
  ; FIXME: i'm not sure I want this to throw an exception. should we insert a
  ; HashMap with false?

  [cmd result]
  (if (success? result)
    ; maybe just return result here and let the caller do something with it
    (:out result)
    (raise 
      :type :remote-failed
      :message (if (not (empty? (:err result))) 
                        (format "command '%s' failed: %s" cmd (:err result))
                        (format "command '%s' failed with no output" cmd)))))

