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
  (:import [java.util.regex Pattern]
           [java.net URL]))

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
                        :creds :gpg})
  (resolve-credentials {:url "https://my.datomic.com/repo"
                        :creds :gpg}))

(defn set-repositories! [repositories]
  (let [repositories (into (empty repositories)
                           (for [[id settings] repositories]
                             [id (resolve-credentials settings)]))]
    (core/set-env! :repositories #(into % repositories))))

;;
;; Deploy
;;

;; START lein code

(defn build-url
  "Creates java.net.URL from string"
  [url]
  (try (URL. url)
       (catch java.net.MalformedURLException _
         (URL. (str "http://" url)))))

(defn add-auth-from-url
  [[id settings]]
  (let [url (build-url id)
        user-info (and url (.getUserInfo url))
        [username password] (and user-info (.split user-info ":"))]
    (if username
      [id (assoc settings :username username :password password)]
      [id settings])))

(defn add-auth-interactively [[id settings]]
  (println id settings)
  (if (or (and (:username settings) (some settings [:password :passphrase
                                                    :private-key-file]))
          (re-find #"(file|scp|scpexe)://" (:url settings)))
    [id settings]
    (do
      (util/fail (str "No credentials found for " id " (did you mean `(push :repo \"clojars\")`"
                      "\nPassword prompts are not supported when ran"
                      "after other (potentially)\ninteractive tasks.\nSee `lein"
                      "help deploy` for an explanation of how to specify"
                      "credentials.\n"))
      (print "No credentials found for" id)
      (println "\nSee `boot gpg --help` for how to configure credentials"
               "to avoid prompts.")
      (print "Username: ") (flush)
      (let [username (read-line)
            console (System/console)
            password (if console
                       (.readPassword console "%s"  (into-array ["Password: "]))
                       (do
                         (println "BOOT IS UNABLE TO TURN OFF ECHOING, SO"
                                  "THE PASSWORD IS PRINTED TO THE CONSOLE")
                         (print "Password: ")
                         (flush)
                         (read-line)))]
        [id (assoc settings :username username :password password)]))))

(defn signing-args
  "Produce GPG arguments for signing a file."
  [file opts]
  (let [key-spec (if-let [key (:gpg-key opts)]
                   ["--default-key" key])]
    `["--yes" "-ab" ~@key-spec "--" ~file]))

(defn sign
  "Create a detached signature and return the signature file name."
  [file opts]
  (println file opts)
  (let [{:keys [err exit]} (apply gpg (signing-args file opts))]
    (when-not (zero? exit)
      (util/fail (str "Could not sign " file "\n" err
                      "\n\nSee `boot push --help` for how to set up gpg.\n"
                      "If you don't expect people to need to verify the "
                      "authorship of your jar, you\ncan add `:sign-releases "
                      "false` to the relevant `:deploy-repositories` entry.\n")))
    (str file ".asc")))

;; END lein code

(core/deftask push-gpg
  "Deploy jar file to a Maven repository.
  The repo option is required. If the file option is not specified the task will
  look for jar files created by the build pipeline. The jar file(s) must contain
  pom.xml entries."

  [f file PATH            str      "The jar file to deploy."
   F file-regex MATCH     #{regex} "The set of regexes of paths to deploy."
   g gpg-sign             bool     "Sign jar using GPG private key."
   k gpg-key              str      "Sign jar using given key"
   r repo ALIAS           str      "The alias of the deploy repository."
   t tag                  bool     "Create git tag for this version."
   B ensure-branch BRANCH str      "The required current git branch."
   C ensure-clean         bool     "Ensure that the project git repo is clean."
   R ensure-release       bool     "Ensure that the current version is not a snapshot."
   S ensure-snapshot      bool     "Ensure that the current version is a snapshot."
   T ensure-tag TAG       str      "The SHA1 of the commit the pom's scm tag must contain."
   V ensure-version VER   str      "The version the jar's pom must contain."]

  (let [tgt (core/tmp-dir!)]
    (core/with-pre-wrap fileset
      (util/with-let [_ fileset]
        (core/empty-dir! tgt)
        (let [jarfiles (or (and file [(io/file file)])
                           (->> (core/output-files fileset)
                                (core/by-ext [".jar"])
                                ((if (seq file-regex) #(core/by-re file-regex %) identity))
                                (map core/tmp-file)))
              repo-map (->> (core/get-env :repositories) (into {}))
              r        (get repo-map repo)]
          (when-not (and r (seq jarfiles))
            (throw (Exception. "missing jar file or repo not found")))
          (doseq [f jarfiles]
            (let [{{t :tag} :scm
                   v :version} (pod/with-call-worker (boot.pom/pom-xml-parse ~(.getPath f)))
                  b            (util/guard (git/branch-current))
                  clean?       (util/guard (git/clean?))
                  snapshot?    (.endsWith v "-SNAPSHOT")
                  ; CHANGES
                  artifact-map (when gpg-sign
                                 (util/info "Signing %s...\n" (.getName f))
                                 (shell/with-sh-dir tgt
                                   (sign (.getPath f) {:gpg-key gpg-key})))]
              (assert (or (not ensure-branch) (= b ensure-branch))
                      (format "current git branch is %s but must be %s" b ensure-branch))
              (assert (or (not ensure-clean) clean?)
                      "project repo is not clean")
              (assert (or (not ensure-release) (not snapshot?))
                      (format "not a release version (%s)" v))
              (assert (or (not ensure-snapshot) snapshot?)
                      (format "not a snapshot version (%s)" v))
              (assert (or (not ensure-tag) (not t) (= t ensure-tag))
                      (format "scm tag in pom doesn't match (%s, %s)" t ensure-tag))
              (when (and ensure-tag (not t))
                (util/warn "The --ensure-tag option was specified but scm info is missing from pom.xml\n"))
              (assert (or (not ensure-version) (= v ensure-version))
                      (format "jar version doesn't match project version (%s, %s)" v ensure-version))
              (util/info "Deploying %s...\n" (.getName f))
              ; CHANGES
              (println "repo" (add-auth-interactively [repo (resolve-credentials r)]))
              ; (pod/with-call-worker
              ;   (boot.aether/deploy ~(core/get-env) ~(add-auth-interactively [repo (resolve-credentials r)]) ~(.getPath f) ~artifact-map))
              (when tag
                (util/info "Creating tag %s...\n" v)
                (git/tag v "release")))))))))
