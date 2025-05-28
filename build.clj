(ns build
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'com.github.thefakelorlyons/brainflow-java)
(def version "1.0.1")
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis basis
                :src-dirs ["src"]
                :scm {:url "https://github.com/thefakelorlyons/brainflow-java"
                      :connection "scm:git:git://github.com/thefakelorlyons/brainflow-java.git"
                      :developerConnection "scm:git:ssh://git@github.com/thefakelorlyons/brainflow-java.git"
                      :tag (str "v" version)}
                :pom-data [[:description "Lightweight BrainFlow Clojure wrapper with automatic native library downloading"]
                           [:url "https://brainflow.org"]
                           [:licenses
                            [:license
                             [:name "MIT License"]
                             [:url "https://github.com/brainflow-dev/brainflow/blob/master/LICENSE"]]]
                           [:developers
                            [:developer
                             [:name "Lorelai Lyons"]
                             [:email "thefakelorlyons@gmail.com"]]]]})

  (b/copy-dir {:src-dirs ["src"]
               :target-dir class-dir})

  (b/jar {:class-dir class-dir
          :jar-file jar-file}))

(defn deploy [_]
  (jar nil)
  (dd/deploy {:installer :remote
              :artifact jar-file
              :pom-file (b/pom-path {:lib lib :class-dir class-dir})}))