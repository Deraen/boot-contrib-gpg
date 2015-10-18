# Boot-contrib-gpg

## Use

Create file `~/.boot/credentials.edn`, with something like this:

```edn
{#"clojars"
 {:username "username"
 :password "password"}
 #"my\.datomic\.com"
 {:username "username"
 :password "password"}}
```

Encrypt the file:

```
$ gpg --default-recipient-self --output credentials.edn.gpg --encrypt credentials.edn
```

**Note:** You can also use the same file as for Leiningen:

```
$ ln -s ~/.lein/credentials.clj.gpg ~/boot/credentials.edn.gpg
```

To use dependencies from private repositories in your project:

```
(set-env! :dependencies '[[deraen/boot-contrib-gpg "0.1.0-SNAPSHOT"]])
(require '[deraen.boot-contrib-gpg :refer [set-repositories! push-gpg]])

(set-repositories! [["my.datomic.com" {:url "https://my.datomic.com/repo"
                                       :creds :gpg}]])

(set-env! :dependencies '[rest of dependencies, including stuff from private repos])
```

To push to specific repo:

**TODO**
