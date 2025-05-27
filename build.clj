(ns build
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'org.clojars.your-username/brainflow-java)
(def version "5.12.2") ; Match BrainFlow version exactly
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
                :resource-dirs ["resources"]
                :scm {:url "https://github.com/your-username/brainflow-java-clojars"
                      :connection "scm:git:git://github.com/your-username/brainflow-java-clojars.git"
                      :developerConnection "scm:git:ssh://git@github.com/your-username/brainflow-java-clojars.git"
                      :tag (str "v" version)}
                :pom-data [[:description "BrainFlow Java library packaged for Clojars - signal acquisition library for EEG, EMG, ECG"]
                           [:url "https://brainflow.org"]
                           [:licenses
                            [:license
                             [:name "MIT License"]
                             [:url "https://github.com/brainflow-dev/brainflow/blob/master/LICENSE"]]]
                           [:developers
                            [:developer
                             [:name "BrainFlow Team"]
                             [:url "https://brainflow.org"]]]]})
  (b/copy-dir {:src-dirs ["resources"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))

(defn deploy [_]
  (jar nil)
  (dd/deploy {:installer :remote
              :artifact jar-file
              :pom-file (b/pom-path {:lib lib :class-dir class-dir})}))