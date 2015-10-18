(ns deraen.boot-contrib-gpg
  {:boot/export-tasks true}
  (:require [boot.core :as core]
            [boot.util :as util]
            [boot.pod :as pod]
            [boot.git :as git]
            [boot.task-helpers :as helpers]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as string])
  (:import [java.util.regex Pattern]))

;; START lein code

(defn getprop
  "Wrap System/getProperty for testing purposes."
  [prop-name]
  (System/getProperty prop-name))

(defn getenv
  "Wrap System/getenv for testing purposes."
  [name]
  (System/getenv name))

(defn boot-home
  "Return full path to the user's Boot home directory."
  []
  (let [boot-home (getenv "BOOT_HOME")
        boot-home (or (and boot-home (io/file boot-home))
                      (io/file (System/getProperty "user.home") ".boot"))]
    (.getAbsolutePath (doto boot-home .mkdirs))))


(defn gpg-program
  "Lookup the gpg program to use, defaulting to 'gpg'"
  []
  (or (getenv "BOOT_GPG") "gpg"))

(defn- get-english-env []
  "Returns environment variables as a map with clojure keywords and LANGUAGE set to 'en'"
  (let [env (System/getenv)
        keywords (map #(keyword %) (keys env))]
    (merge (zipmap keywords (vals env))
           {:LANGUAGE "en"})))

(defn gpg
  "Shells out to (gpg-program) with the given arguments"
  [& args]
  (let [env (get-english-env)]
    (try
      (shell/with-sh-env env
        (apply shell/sh (gpg-program) args))
      (catch java.io.IOException e
        {:exit 1 :err (.getMessage e)}))))

(defn gpg-available?
  "Verifies (gpg-program) exists"
  []
  (zero? (:exit (gpg "--version"))))

(defn credentials-fn
  "Decrypt map from credentials.edn.gpg in Boot home if present."
  ([] (let [cred-file (io/file (boot-home) "credentials.edn.gpg")]
        (if (.exists cred-file)
          (credentials-fn cred-file))))
  ([file]
     (let [{:keys [out err exit]} (gpg "--quiet" "--batch"
                                       "--decrypt" "--" (str file))]
       (if (pos? exit)
         (binding [*out* *err*]
           (println "Could not decrypt credentials from" (str file))
           (println err)
           (println "See `boot gpg --help` for how to install gpg."))
         (read-string out)))))

(def credentials (memoize credentials-fn))

(defn- match-credentials [settings auth-map]
  (get auth-map (:url settings)
       (first (for [[re? cred] auth-map
                    :when (and (instance? Pattern re?)
                               (re-find re? (:url settings)))]
                cred))))

(defn- resolve-credential
  "Resolve key-value pair from result into a credential, updating result."
  [source-settings result [k v]]
  (letfn [(resolve [v]
            (cond (= :env v)
                  (getenv (str "BOOT_" (string/upper-case (name k))))

                  (and (keyword? v) (= "env" (namespace v)))
                  (getenv (string/upper-case (name v)))

                  (= :gpg v)
                  (get (match-credentials source-settings (credentials)) k)

                  (coll? v) ;; collection of places to look
                  (->> (map resolve v)
                       (remove nil?)
                       first)

                  :else v))]
    (if (#{:username :password :passphrase :private-key-file} k)
      (assoc result k (resolve v))
      (assoc result k v))))

(defn resolve-credentials
  "Applies credentials from the environment or ~/.boot/credentials.edn.gpg
  as they are specified and available."
  [settings]
  (let [gpg-creds (if (= :gpg (:creds settings))
                    (match-credentials settings (credentials)))
        resolved (reduce (partial resolve-credential settings)
                         (empty settings)
                         settings)]
    (if gpg-creds
      (dissoc (merge gpg-creds resolved) :creds)
      resolved)))

;; END lein code

(comment
  (resolve-credentials {:url "http://clojars.org/repo"
                        :creds :gpg}))

(defn set-repositories [repositories]
  (core/set-env! :repositories (into (empty repositories)
                                     (for [[k v] repositories]
                                       [k (resolve-credentials v)]))))
