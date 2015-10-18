(set-env!
  :resource-paths #{"src"}
  :dependencies   '[[org.clojure/clojure "1.7.0" :scope "provided"]
                    [boot/core "2.3.0" :scope "provided"]])

(def +version+ "0.1.0-SNAPSHOT")

(task-options!
  pom {:project     'deraen/boot-contrib-gpg
       :version     +version+
       :description ""
       :url         "https://github.com/deraen/boot-contrib-gpg"
       :scm         {:url "https://github.com/deraen/boot-contrib-gpg"}
       :license     {"MIT" "http://opensource.org/licenses/mit-license.php"}})

(deftask dev
  "Dev process"
  []
  (comp
    (watch)
    (repl :server true)
    (pom)
    (jar)
    (install)))
