(ns brainflow-java.core
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.java.shell :as cshell]
            [clojure.pprint :as pprint]
            [zprint.core :as zprint])
  (:import [java.io File]
           [java.util.concurrent.locks ReentrantLock]))

(def ^:private brainflow-version "5.16.0")
(def ^:private base-url "https://github.com/brainflow-dev/brainflow/releases/download")
(def ^:private brainflow-classloader (atom nil))

; Thread-safe initialization
(def ^:private initialization-lock (ReentrantLock.))
(def ^:private initialized? (atom false))
(def ^:private initialization-error (atom nil))

(defn get-os-type
  []
  (let [os-name (str/lower-case (System/getProperty "os.name"))]
    (cond
      (str/includes? os-name "windows") :windows
      (str/includes? os-name "mac") :macos
      (str/includes? os-name "linux") :linux
      :else :unknown)))

(defn- babashka-available?
  "Check if Babashka is available on the system"
  []
  (try
    (let [result (clojure.java.shell/sh "bb" "--version")]
      (zero? (:exit result)))
    (catch Exception _
      false)))

(defn create-dependency-installer-script
  "Create the cross-platform dependency installer script file"
  []
  (let [script-content (slurp (io/resource "install_brainflow_deps.bb"))
        temp-file (File/createTempFile "install_brainflow_deps" ".bb")]
    (spit temp-file script-content)
    (.setExecutable temp-file true)
    temp-file))

(defn install-dependencies-with-babashka
  "Install all BrainFlow dependencies using cross-platform Babashka script"
  []
  (if-not (babashka-available?)
    (do
      (println "Babashka not found. Please install Babashka for automatic dependency installation.")
      (println "Babashka installation: https://github.com/babashka/babashka#installation")
      (println "")
      false)
    (do
      (println "Installing BrainFlow dependencies automatically...")
      (try
        (let [script-file (create-dependency-installer-script)
              result (cshell/sh "bb" (.getAbsolutePath script-file))]
          (.delete script-file) ; Clean up
          (if (zero? (:exit result))
            (do
              (println "✓ BrainFlow dependencies installation completed")
              true)
            (do
              (println "⚠ Dependency installation completed with warnings")
              (println "Output:" (:out result))
              (when (not (str/blank? (:err result)))
                (println "Errors:" (:err result)))
              ; Return true even with warnings, as some dependencies might have installed
              true)))
        (catch Exception e
          (println (str "✗ Failed to run dependency installer: " (.getMessage e)))
          (println "")
          false)))))

(defn- print-manual-vcredist-instructions
  "Print manual installation instructions"
  []
  (println)
  (println "Manual Installation:")
  (println "1. Download: https://aka.ms/vs/17/release/vc_redist.x64.exe")
  (println "2. Run the installer")
  (println "3. Restart your REPL")
  (println))

; Enhanced dependency checking with auto-install
(defn- check-and-install-dependencies
  "Check for dependencies and offer to install them automatically"
  []
  (when (str/includes? (str/lower-case (System/getProperty "os.name")) "windows")
    (let [system32 (str (System/getenv "WINDIR") "\\System32\\")
          test-dlls ["VCRUNTIME140.dll" "MSVCP140.dll" "api-ms-win-crt-runtime-l1-1-0.dll"]
          missing-dlls (filter #(not (.exists (io/file system32 %))) test-dlls)]

      (when (seq missing-dlls)
        (println "\n⚠️  Missing Visual C++ Runtime Dependencies:")
        (doseq [dll missing-dlls]
          (println (str "   - " dll)))
        (println)

        (if (babashka-available?)
          (do
            (print "Would you like to automatically install Visual C++ Redistributables? [Y/n]: ")
            (flush)
            (let [response (str/trim (read-line))]
              (if (or (empty? response) (= "y" (str/lower-case response)))
                (install-dependencies-with-babashka)
                (do
                  (println "Skipping automatic installation.")
                  (print-manual-vcredist-instructions)
                  false))))
          (do
            (println "Automatic installation requires Babashka.")
            (println "Install Babashka: https://github.com/babashka/babashka#installation")
            (println "Or install Visual C++ Redistributables manually:")
            (print-manual-vcredist-instructions)
            false))))))

(defn get-actual-jvm-bitness
  "More robust JVM bitness detection"
  []
  (let [data-model (System/getProperty "sun.arch.data.model")
        pointer-size (try
                       ; Try to detect pointer size through unsafe operations
                       (let [unsafe-class (Class/forName "sun.misc.Unsafe")
                             field (.getDeclaredField unsafe-class "theUnsafe")]
                         (.setAccessible field true)
                         (let [unsafe (.get field nil)
                               address-size (.addressSize unsafe)]
                           (* address-size 8))) ; Convert bytes to bits
                       (catch Exception _ nil))
        os-arch (System/getProperty "os.arch")]

    (println (format "; Bitness Detection:"))
    (println (format ";   sun.arch.data.model: %s" data-model))
    (when pointer-size
      (println (format ";   Pointer size method: %d-bit" pointer-size)))
    (println (format ";   os.arch: %s" os-arch))

    ; Use multiple methods to determine bitness
    (cond
      ; Primary method: sun.arch.data.model
      (= data-model "64") "64"
      (= data-model "32") "32"
      ; Secondary method: pointer size
      (= pointer-size 64) "64"
      (= pointer-size 32) "32"
      ; Fallback: parse from os.arch
      (or (str/includes? (str/lower-case os-arch) "64")
          (str/includes? (str/lower-case os-arch) "amd64")
          (str/includes? (str/lower-case os-arch) "x86_64")) "64"
      ; Default to 32-bit if uncertain
      :else "32")))


(defn- get-os-arch
  "Get OS architecture string that matches actual JVM bitness"
  []
  (let [os-name (str/lower-case (System/getProperty "os.name"))
        jvm-bits (get-actual-jvm-bitness)
        os-arch (System/getProperty "os.arch")]
    (println (format "; Detected JVM: %s-bit, OS: %s, Arch: %s" jvm-bits os-name os-arch))
    (cond
      (str/includes? os-name "linux")
      (if (= jvm-bits "64")
        (if (or (str/includes? os-arch "aarch64") (str/includes? os-arch "arm64"))
          "linux-aarch64"
          "linux-x86-64")
        "linux-x86")

      (str/includes? os-name "mac")
      (if (= jvm-bits "64")
        (if (or (str/includes? os-arch "aarch64") (str/includes? os-arch "arm64"))
          "darwin-aarch64"
          "darwin-x86-64")
        "darwin-x86")

      (str/includes? os-name "windows")
      (if (= jvm-bits "64")
        "win32-x86-64"
        "win32-x86") ; Return 32-bit path for 32-bit JVM

      :else (throw (RuntimeException. (str "Unsupported platform: " os-name " " os-arch " " jvm-bits "-bit"))))))

(defn- get-cache-dir
  []
  (let [home (System/getProperty "user.home")
        cache-dir (io/file home ".brainflow-java" brainflow-version)]
    (.mkdirs cache-dir)
    cache-dir))

(defn- file-exists-and-valid? [file expected-min-size]
  (and (.exists file)
       (.isFile file)
       (> (.length file) expected-min-size)))

(defn- download-with-progress
  [url dest-file]
  (println (str "Downloading " (.getName dest-file) "..."))
  (try
    (with-open [in (io/input-stream url)
                out (io/output-stream dest-file)]
      (let [buffer (byte-array 8192)
            total-size (atom 0)]
        (loop []
          (let [bytes-read (.read in buffer)]
            (when (pos? bytes-read)
              (.write out buffer 0 bytes-read)
              (swap! total-size + bytes-read)
              (when (zero? (mod @total-size (* 1024 1024))) ; Progress every MB
                (println (str "  Downloaded " (/ @total-size 1024 1024) " MB...")))
              (recur))))))
    (println (str "Download complete: " (.getName dest-file)))
    (catch Exception e
      (when (.exists dest-file) (.delete dest-file))
      (throw (RuntimeException. (str "Failed to download " url ": " (.getMessage e)) e)))))

(defn- extract-tar
  [tar-file dest-dir]
  (println "Extracting native libraries...")
  (try
    (let [pb (ProcessBuilder. ["tar" "-xf" (.getAbsolutePath tar-file) "-C" (.getAbsolutePath dest-dir)])
          process (.start pb)
          exit-code (.waitFor process)]
      (when-not (zero? exit-code)
        (throw (RuntimeException. (str "tar extraction failed with exit code: " exit-code)))))
    (catch java.io.IOException e
      ; Fallback: try to use Java's built-in capabilities or fail gracefully
      (throw (RuntimeException.
              (str "Failed to extract tar file. Please ensure 'tar' command is available: " (.getMessage e)) e)))))

(defn- get-platform-extensions
  "Get file extensions for the current platform"
  []
  (let [platform (get-os-arch)]
    (cond
      (str/starts-with? platform "linux") [".so"]
      (str/starts-with? platform "darwin") [".dylib"]
      (str/starts-with? platform "win32") [".dll"]
      :else [])))

(defn- is-native-lib-file?
  "Check if file is a native library for the current platform"
  [file platform-extensions]
  (let [name (.getName file)]
    (some #(.endsWith name %) platform-extensions)))

(defn- is-correct-architecture-library?
  "Check if a library file matches the target JVM architecture"
  [file target-bits]
  (let [filename (str/lower-case (.getName file))]
    (cond
      ; If target is 32-bit, accept libraries that explicitly mention 32, x86 (without 64), or have no architecture indicators
      (= target-bits "32")
      (or (str/includes? filename "32")
          (str/includes? filename "x86")
          (and (not (str/includes? filename "64"))
               (not (str/includes? filename "x64"))
               (not (str/includes? filename "amd64"))))

      ; If target is 64-bit, accept libraries that mention 64, x64, amd64, or have no architecture indicators
      (= target-bits "64")
      (or (str/includes? filename "64")
          (str/includes? filename "x64")
          (str/includes? filename "amd64")
          (and (not (str/includes? filename "32"))
               (not (str/includes? filename "x86")))))))

(defn- find-native-libraries-in-archive
  "Find and extract the correct native libraries from the extracted archive"
  [cache-dir target-platform target-bits platform-extensions]
  (let [found-libs (atom [])
        search-dirs [(io/file cache-dir "lib")
                     (io/file cache-dir "libs")
                     (io/file cache-dir target-platform)
                     (io/file cache-dir (str "native-" target-platform))
                     cache-dir]] ; Also search root of cache

    (println (format "; Searching for %s-bit libraries in archive..." target-bits))

    ; Search through all possible directories
    (doseq [search-dir search-dirs]
      (when (.exists search-dir)
        (println (format "; Searching in: %s" (.getAbsolutePath search-dir)))
        (doseq [file (file-seq search-dir)]
          (when (and (.isFile file)
                     (is-native-lib-file? file platform-extensions)
                     (is-correct-architecture-library? file target-bits))
            (println (format "; Found matching library: %s" (.getName file)))
            (swap! found-libs conj file)))))

    @found-libs))

(defn- copy-native-libs
  "Copy all native libraries for the current platform, with correct architecture filtering"
  [cache-dir to-dir platform-extensions target-platform target-bits]
  (let [copied-files (atom [])
        found-libs (find-native-libraries-in-archive cache-dir target-platform target-bits platform-extensions)]

    (if (empty? found-libs)
      (do
        (println (format "; ⚠️  No %s-bit native libraries found for platform %s" target-bits target-platform))
        (println "; Available files in archive:")
        (doseq [file (file-seq cache-dir)]
          (when (and (.isFile file) (is-native-lib-file? file platform-extensions))
            (println (format ";   - %s" (.getName file)))))
        [])

      (do
        (doseq [file found-libs]
          (let [filename (.getName file)
                dest (io/file to-dir filename)]
            (println (format "; Copying %s-bit library: %s" target-bits filename))
            (io/copy file dest)
            (swap! copied-files conj filename)))

        (println (format "; Successfully copied %d libraries" (count @copied-files)))
        @copied-files))))

(defn- platform-dir-has-natives?
  "Check if platform directory already has native libraries"
  [platform-dir platform-extensions]
  (let [files (filter #(.isFile %) (file-seq platform-dir))
        native-files (filter #(is-native-lib-file? % platform-extensions) files)]
    (seq native-files)))

(defn- download-and-cache-natives
  []
  (let [jvm-bits (get-actual-jvm-bitness)
        platform (get-os-arch)
        cache-dir (get-cache-dir)
        platform-dir (io/file cache-dir "natives" platform)
        platform-extensions (get-platform-extensions)
        archive-name "compiled_libs.tar"
        archive-file (io/file cache-dir archive-name)]

    (.mkdirs platform-dir)

    ; Check if platform directory already has native libraries
    (when-not (platform-dir-has-natives? platform-dir platform-extensions)
      (println (format "; Setting up BrainFlow native libraries for %s (%s-bit JVM)..." platform jvm-bits))

      ; Download archive if needed
      (when-not (file-exists-and-valid? archive-file 1000000) ; At least 1MB
        (let [url (str base-url "/" brainflow-version "/" archive-name)]
          (download-with-progress url archive-file)))

      ; Extract archive
      (extract-tar archive-file cache-dir)

      ; Copy libraries with correct architecture filtering
      (let [copied-files (copy-native-libs cache-dir platform-dir platform-extensions platform jvm-bits)]
        (if (empty? copied-files)
          (throw (RuntimeException.
                  (format "No suitable %s-bit native libraries found for platform %s" jvm-bits platform)))
          (println (format "; Copied %d native libraries: %s"
                           (count copied-files)
                           (str/join ", " copied-files)))))

      ; Clean up archive to save space
      (.delete archive-file)

      ; Clean up extracted files to save space
      (doseq [file (file-seq cache-dir)]
        (when (and (.isFile file)
                   (not (.equals file archive-file))
                   (not (str/starts-with? (.getAbsolutePath file) (.getAbsolutePath platform-dir))))
          (.delete file))))

    ; Return the platform directory path
    (.getAbsolutePath platform-dir)))

(defn- detect-execution-context
  "Detect if we're running in REPL or CLI mode"
  []
  (let [current-loader (.getContextClassLoader (Thread/currentThread))]
    (cond
      (instance? clojure.lang.DynamicClassLoader current-loader) :repl
      (re-find #"nrepl" (str current-loader)) :repl
      :else :cli)))

(defn- add-jar-to-classpath
  "Enhanced JAR loading with better CLI support"
  [jar-file]
  (let [context (detect-execution-context)]
    (println (format "; Execution context: %s" context))

    (try
      (case context
        :repl
        (do
          (println "; Using REPL-optimized loading...")
          (let [current-loader (.getContextClassLoader (Thread/currentThread))]
            (if (instance? clojure.lang.DynamicClassLoader current-loader)
              (let [add-url-method (.getMethod clojure.lang.DynamicClassLoader "addURL"
                                               (into-array Class [java.net.URL]))]
                (.invoke add-url-method current-loader
                         (into-array Object [(.toURL (.toURI jar-file))]))
                (println "; ✓ JAR added via DynamicClassLoader")
                true)
              false)))

        :cli
        (do
          (println "; Using CLI-optimized loading...")
          ; Method 1: Try to add to system classloader if possible
          (try
            (let [system-loader (ClassLoader/getSystemClassLoader)]
              (if (instance? java.net.URLClassLoader system-loader)
                (let [add-url-method (.getDeclaredMethod java.net.URLClassLoader "addURL"
                                                         (into-array Class [java.net.URL]))]
                  (.setAccessible add-url-method true)
                  (.invoke add-url-method system-loader
                           (into-array Object [(.toURL (.toURI jar-file))]))
                  (println "; ✓ JAR added to system classloader")
                  (Thread/sleep 500)
                  true)
                (throw (Exception. "System classloader is not URLClassLoader"))))
            (catch Exception e
              (println (format "; System classloader approach failed: %s" (.getMessage e)))
              ; Method 2: Create compound classloader
              (try
                (let [current-thread (Thread/currentThread)
                      current-loader (.getContextClassLoader current-thread)
                      jar-urls (into-array java.net.URL [(.toURL (.toURI jar-file))])
                      compound-loader (java.net.URLClassLoader. jar-urls current-loader)]
                  (.setContextClassLoader current-thread compound-loader)

                  ; Also try to set for namespace classloader
                  (when-let [ns-loader (.getClassLoader (class clojure.lang.Namespace))]
                    (try
                      (.setContextClassLoader (Thread/currentThread)
                                              (java.net.URLClassLoader. jar-urls ns-loader))
                      (catch Exception _)))

                  (println "; ✓ JAR added via compound URLClassLoader")
                  (Thread/sleep 500)
                  true)
                (catch Exception e2
                  (println (format "; Compound classloader failed: %s" (.getMessage e2)))
                  false))))))
      (catch Exception e
        (println (format "; JAR loading failed: %s" (.getMessage e)))
        false))))

(defn- verify-brainflow-classes
  "Enhanced class verification that caches the successful classloader"
  []
  (let [test-classes ["brainflow.BoardShim"
                      "brainflow.BrainFlowInputParams"
                      "brainflow.BoardIds"]
        context-loader (.getContextClassLoader (Thread/currentThread))]

    (println "; Verifying BrainFlow classes are available...")
    (println (format "; Using classloader: %s" (.getClass context-loader)))

    (doseq [class-name test-classes]
      (try
        ; Try multiple class loading approaches
        (let [loaded-class (or
                            ; Method 1: Standard Class/forName
                            (try (Class/forName class-name) (catch Exception _ nil))
                            ; Method 2: Using context classloader explicitly  
                            (try (.loadClass context-loader class-name) (catch Exception _ nil))
                            ; Method 3: Using system classloader
                            (try (.loadClass (ClassLoader/getSystemClassLoader) class-name) (catch Exception _ nil)))]

          (if loaded-class
            (do
              (println (format "; ✓ Found class: %s" class-name))
              ; Cache the classloader that successfully loaded the class
              (when-not @brainflow-classloader
                (reset! brainflow-classloader
                        (or (try (.getClassLoader loaded-class) (catch Exception _ nil))
                            context-loader))))
            (do
              (println (format "; ✗ Missing class: %s" class-name))
              (throw (RuntimeException. (format "BrainFlow class not found: %s" class-name))))))

        (catch RuntimeException e (throw e))
        (catch Exception e
          (println (format "; ✗ Error loading class %s: %s" class-name (.getMessage e)))
          (throw (RuntimeException. (format "BrainFlow class not found: %s" class-name) e)))))))


(defn- download-and-cache-jar
  "Enhanced JAR download and setup with verification"
  []
  (let [cache-dir (get-cache-dir)
        jar-file (io/file cache-dir "brainflow-jar-with-dependencies.jar")]

    (when-not (file-exists-and-valid? jar-file 1000000) ; At least 1MB
      (println "Downloading BrainFlow Java library with dependencies...")
      (let [jar-url (str base-url "/" brainflow-version "/brainflow-jar-with-dependencies.jar")]
        (download-with-progress jar-url jar-file)))

    ; Try to add to classpath
    (let [success (add-jar-to-classpath jar-file)]
      (when-not success
        (throw (RuntimeException. "Failed to add BrainFlow JAR to classpath"))))

    ; Verify classes are available
    (try
      (verify-brainflow-classes)
      (catch Exception e
        ; If verification fails, provide helpful error message
        (println "; BrainFlow JAR added to classpath but classes not found.")
        (println "; This may indicate:")
        (println ";   1. The JAR file is corrupted")
        (println ";   2. REPL needs to be restarted")
        (println ";   3. Classpath modification didn't take effect")
        (println (format "; JAR file size: %d bytes" (.length jar-file)))
        (throw e)))

    jar-file))

(defn check-dll-architecture
  "Check if a DLL matches the current JVM architecture on Windows"
  [dll-path]
  (when (and (= (get-os-type) :windows) (.exists (io/file dll-path)))
    (try
      ; Use PowerShell to check DLL architecture
      (let [ps-command (format
                        "[System.Reflection.AssemblyName]::GetAssemblyName('%s').ProcessorArchitecture"
                        dll-path)
            result (cshell/sh "powershell" "-Command" ps-command)]
        (when (zero? (:exit result))
          (let [arch (str/trim (:out result))]
            (println (format ";   DLL %s architecture: %s"
                             (.getName (io/file dll-path)) arch))
            arch)))
      (catch Exception e
        (println (format ";   Could not check architecture for %s: %s"
                         (.getName (io/file dll-path)) (.getMessage e)))
        nil))))

(defn load-native-library
  "Enhanced native library loading with architecture verification"
  [lib-path lib-name]
  (let [full-path (.getAbsolutePath (io/file lib-path lib-name))
        jvm-bits (get-actual-jvm-bitness)]
    (println (format "; Loading: %s" lib-name))
    (try
      ; First check if file exists
      (if-not (.exists (io/file full-path))
        (do
          (println (format "; ✗ File not found: %s" full-path))
          {:success false :error "File not found"})

        ; Check architecture compatibility on Windows
        (let [arch-check (when (= (get-os-type) :windows)
                           (check-dll-architecture full-path))]

          ; Warn about potential architecture mismatches
          (when (and arch-check (= (get-os-type) :windows))
            (let [expected-arch (if (= jvm-bits "64") "Amd64" "X86")]
              (when (not= arch-check expected-arch)
                (println (format "; ⚠️  Architecture mismatch: DLL is %s, JVM is %s-bit"
                                 arch-check jvm-bits)))))

          ; Attempt to load the library
          (try
            (System/load full-path)
            (println (format "; ✓ Successfully loaded: %s" lib-name))
            {:success true}

            (catch UnsatisfiedLinkError e
              (let [msg (.getMessage e)]
                (println (format "; ✗ Failed to load %s: %s" lib-name
                                 (cond
                                   (str/includes? msg "Can't load IA 32-bit")
                                   "32-bit/64-bit architecture mismatch"
                                   (str/includes? msg "%1 is not a valid Win32 application")
                                   "Architecture mismatch or missing dependencies"
                                   (str/includes? msg "dependent libraries")
                                   "Missing dependencies"
                                   :else "Native library error")))
                (println (format ";   → Error details: %s" msg))
                {:success false :error msg}))

            (catch Exception e
              (println (format "; ✗ Unexpected error loading %s: %s" lib-name (.getMessage e)))
              {:success false :error (.getMessage e)}))))

      (catch Exception e
        (println (format "; ✗ Error accessing %s: %s" lib-name (.getMessage e)))
        {:success false :error (.getMessage e)}))))

(defn- debug-library-architecture
  "Debug helper to show library architecture information"
  [native-path]
  (let [native-dir (io/file native-path)
        jvm-bits (get-actual-jvm-bitness)
        platform-extensions (get-platform-extensions)]

    (println (format "; Debug: Library architecture analysis"))
    (println (format "; JVM Bitness: %s" jvm-bits))
    (println (format "; Platform extensions: %s" platform-extensions))
    (println (format "; Native path: %s" native-path))

    (when (.exists native-dir)
      (let [all-files (->> (file-seq native-dir)
                           (filter #(.isFile %))
                           (filter #(is-native-lib-file? % platform-extensions)))]

        (println (format "; Found %d total native library files:" (count all-files)))
        (doseq [file all-files]
          (let [filename (.getName file)
                matches-arch (is-correct-architecture-library? file jvm-bits)]
            (println (format ";   %s %s - %s"
                             (if matches-arch "✓" "✗")
                             filename
                             (if matches-arch "matches" "wrong architecture")))))))))

(defn load-all-native-libraries
  "Enhanced native library loading with better error reporting and architecture matching"
  [native-path]
  (println "; Loading native libraries...")
  (let [jvm-bits (get-actual-jvm-bitness)
        native-dir (io/file native-path)
        platform-extensions (get-platform-extensions)]

    (println (format "; Target JVM: %s-bit" jvm-bits))
    (println (format "; Native library path: %s" (.getAbsolutePath native-dir)))

    ; Debug library architecture
    (debug-library-architecture native-path)

    (if-not (.exists native-dir)
      (do
        (println (format "; ✗ Native library directory does not exist: %s" native-path))
        {:loaded 0 :failed 1 :results []})

      (let [all-lib-files (->> (file-seq native-dir)
                               (filter #(.isFile %))
                               (filter #(is-native-lib-file? % platform-extensions)))

            ; Filter to only libraries that match our architecture
            matching-lib-files (filter #(is-correct-architecture-library? % jvm-bits) all-lib-files)
            lib-names (map #(.getName %) matching-lib-files)]

        (println (format "; Found %d total libraries, %d match %s-bit architecture"
                         (count all-lib-files) (count matching-lib-files) jvm-bits))

        (if (empty? matching-lib-files)
          (do
            (println "; ✗ No libraries match the target architecture")
            {:loaded 0 :failed 1 :results []})

          (let [results (doall (map #(load-native-library native-path %) lib-names))
                successful (count (filter :success results))
                failed (count (filter #(not (:success %)) results))]

            (println)
            (println (format "; Library loading summary:"))
            (println (format ";   ✓ Successfully loaded: %d" successful))
            (println (format ";   ✗ Failed to load: %d" failed))

            (when (> failed 0)
              (println)
              (println "; ⚠️  Some libraries failed due to missing dependencies.")
              (println "; This may cause BrainFlow functionality to be limited.")
              (println "; If you experience issues, try restarting after dependency installation."))

            {:loaded successful :failed failed :results results}))))))

; Update the setup function to use enhanced loading
(defn- setup-native-libraries [native-path]
  ; Still set the system property as a fallback
  (let [current-path (System/getProperty "java.library.path")
        new-path (if (str/blank? current-path)
                   native-path
                   (str current-path File/pathSeparator native-path))]
    (System/setProperty "java.library.path" new-path))

  ; Use enhanced loading with dependency checking
  (load-all-native-libraries native-path))

(defn verify-native-libraries
  "Verify that native libraries exist and are accessible"
  [native-path]
  (if (.exists (io/file native-path))
    (let [dll-files (->> (file-seq (io/file native-path))
                         (filter #(.isFile %))
                         (filter #(or (str/ends-with? (.getName %) ".dll")
                                      (str/ends-with? (.getName %) ".so")
                                      (str/ends-with? (.getName %) ".dylib")))
                         (map #(.getName %)))]
      (println (format "; Found %d native libraries in %s" (count dll-files) native-path))
      (doseq [lib (take 5 dll-files)] ; Show first 5
        (println (format ";   - %s" lib)))
      (when (> (count dll-files) 5)
        (println (format ";   ... and %d more" (- (count dll-files) 5))))
      (> (count dll-files) 0))
    (do
      (println (format "; ✗ Native library path does not exist: %s" native-path))
      false)))

(defn ensure-brainflow-loaded!
  "Ensures BrainFlow is loaded with improved error handling and architecture detection"
  []
  (when-not @initialized?
    (.lock initialization-lock)
    (try
      (if-let [error @initialization-error]
        (throw (RuntimeException. "BrainFlow initialization previously failed" error))
        (try
          (println "; Initializing BrainFlow...")

          ; Step 1: Check and install system dependencies
          (println "; Checking system dependencies...")
          (when-not (check-and-install-dependencies)
            (throw (RuntimeException. "Missing system dependencies; cannot proceed.")))

          ; Step 2: Check if BrainFlow classes are already available (via deps.edn)
          (println "; Checking if BrainFlow classes are available...")
          (if (try
                (Class/forName "brainflow.BoardShim")
                true
                (catch ClassNotFoundException _ false))
            (println "; ✓ BrainFlow classes already available")

            ; Step 2b: Not available, try to load dynamically
            (do
              (println "; Setting up Java library...")
              (let [jar-file (download-and-cache-jar)
                    success (add-jar-to-classpath jar-file)]
                (when-not success
                    ; Provide helpful error message for CLI users
                  (let [abs-path (.getAbsolutePath jar-file)]
                    (println (format "; ✗ Dynamic JAR loading failed in CLI mode"))
                    (println (format "; JAR downloaded to: %s" abs-path))
                    (println "; SOLUTIONS:")
                    (println ";   1. Add to deps.edn:")
                    (println (format ";      {:deps {brainflow/brainflow {:local/root \"%s\"}}}" abs-path))
                    (println ";   2. Use classpath flag:")
                    (println (format ";      clj -cp \"%s\" -M:dev -m brainflow-java.core" abs-path))
                    (println ";   3. Run in REPL mode instead of CLI")
                    (throw (RuntimeException.
                            (str "BrainFlow JAR loading failed. "
                                 "For CLI execution, add JAR to deps.edn or use -cp flag. "
                                 "See suggestions above."))))))))

          ; Step 3: Download and set up native libraries with correct architecture
          (println "; Setting up native libraries...")
          (let [native-path-str (download-and-cache-natives)
                native-path (io/file native-path-str)]
            (println (format "; Native library path: %s" (.getAbsolutePath native-path)))

            ; Verify libraries exist before trying to load them
            (if (verify-native-libraries native-path)
              (do
                (println "; Native libraries verified, proceeding with setup...")
                (setup-native-libraries native-path))
              (throw (RuntimeException.
                      (format "Native libraries not found for architecture: %s" native-path)))))

          ; Step 4: Final verification - try to use BrainFlow classes
          (try
            (verify-brainflow-classes)
            (reset! initialized? true)
            (println "; BrainFlow initialization complete!")
            (catch Exception e
              (println "; ✗ BrainFlow class verification failed")
              (throw e)))

          (catch Exception e
            (reset! initialization-error e)
            (println (format "; BrainFlow initialization failed: %s" (.getMessage e)))
            (println "; Stack trace:")
            (.printStackTrace e)
            (throw e))))
      (finally
        (.unlock initialization-lock)))))

; Macro to wrap BrainFlow functions with auto-loading
(defmacro with-brainflow [& body]
  `(do
     (when-not @initialized?
       (ensure-brainflow-loaded!))
     ~@body))

(defn- load-brainflow-class
  "Load a BrainFlow class using the cached classloader"
  [class-name]
  (if-let [loader @brainflow-classloader]
    (try
      ; First try with the cached classloader
      (.loadClass loader class-name)
      (catch Exception _
        ; Fallback to standard Class/forName
        (Class/forName class-name)))
    ; No cached loader, use standard approach
    (Class/forName class-name)))

; Examples, these functions would ensure that the BrainFlow functions auto-load
; (The files should cache and with-brainflow should only need to happen once)
(defn create-input-params
  "Create a BrainFlowInputParams object with the given parameters.
   Common parameters include:
   :serial_port - Serial port name (e.g. 'COM3', '/dev/ttyUSB0')
   :ip_address - IP address for network boards
   :ip_port - IP port for network boards  
   :mac_address - MAC address for Bluetooth boards
   :other_info - Additional info as needed
   :serial_number - Serial number
   :file - File path for file-based boards
   :timeout - Timeout value
   :master_board - Master board ID for some boards"
  [params-map]
  (with-brainflow
    (let [params-class (load-brainflow-class "brainflow.BrainFlowInputParams")  ;; Changed this line
          constructor (.getDeclaredConstructor params-class (into-array Class []))
          params-instance (.newInstance constructor (into-array Object []))]

      ; Use setter methods
      (when-let [serial-port (:serial_port params-map)]
        (.set_serial_port params-instance serial-port))
      (when-let [ip-address (:ip_address params-map)]
        (.set_ip_address params-instance ip-address))
      (when-let [ip-port (:ip_port params-map)]
        (.set_ip_port params-instance (int ip-port)))
      (when-let [mac-address (:mac_address params-map)]
        (.set_mac_address params-instance mac-address))
      (when-let [other-info (:other_info params-map)]
        (.set_other_info params-instance other-info))
      (when-let [serial-number (:serial_number params-map)]
        (.set_serial_number params-instance serial-number))
      (when-let [file (:file params-map)]
        (.set_file params-instance file))
      (when-let [timeout (:timeout params-map)]
        (.set_timeout params-instance (int timeout)))
      (when-let [master-board (:master_board params-map)]
        (.set_master_board params-instance (int master-board)))

      params-instance)))

(defn with-suppressed-jna-logging
  "Temporarily suppress JNA logging during BrainFlow operations"
  [f]
  (let [original-level (System/getProperty "jna.debug_load")]
    (try
      (System/setProperty "jna.debug_load" "false")
      (f)
      (finally
        (if original-level
          (System/setProperty "jna.debug_load" original-level)
          (System/clearProperty "jna.debug_load"))))))

(defn get-board-shim
  "Get a BrainFlow board shim. Downloads BrainFlow if needed. Takes board-id (int)
   and input-params (map with keys like :serial_port, :ip_address, etc.)"
  [board-id input-params]
  (with-brainflow
    (let [board-shim-class (load-brainflow-class "brainflow.BoardShim")
          params-instance (create-input-params input-params)]
      (try
        ; Suppress the JNA extraction messages that can be confusing
        (with-suppressed-jna-logging
          #(.newInstance (.getDeclaredConstructor board-shim-class
                                                  (into-array Class [Integer/TYPE (.getClass params-instance)]))
                         (into-array Object [(int board-id) params-instance])))
        (catch Exception e
          ; Check if this is the expected JNA FileAlreadyExistsException
          (if (and (instance? java.lang.reflect.InvocationTargetException e)
                   (let [cause (.getCause e)]
                     (and cause
                          (or (instance? java.nio.file.FileAlreadyExistsException cause)
                              (and (instance? RuntimeException cause)
                                   (str/includes? (.getMessage cause) "FileAlreadyExistsException"))))))
            ; This is the expected JNA caching behavior - continue normally
            (do
              (println "; Note: BrainFlow libraries already cached (this is normal)")
              ; Try again without suppression
              (.newInstance (.getDeclaredConstructor board-shim-class
                                                     (into-array Class [Integer/TYPE (.getClass params-instance)]))
                            (into-array Object [(int board-id) params-instance])))
            ; Otherwise, re-throw the original exception
            (throw e)))))))

(defn get-default-synth-board-shim
  "Simplified version that takes a board-id and optional parameters map"
  ([board-id]
   (get-board-shim board-id {:other_info "synthetic"}))
  ([board-id params-map]
   (get-board-shim board-id params-map)))

(defn prepare-session
  "Prepare a BrainFlow session. Automatically downloads BrainFlow if needed."
  [board-shim]
  (with-brainflow
    (.prepare_session board-shim)))

(defn start-stream
  "Start BrainFlow data stream. Automatically downloads BrainFlow if needed."
  [board-shim & [buffer-size]]
  (with-brainflow
    (if buffer-size
      (.start_stream board-shim (int buffer-size))
      (.start_stream board-shim))))

(defn stop-stream
  "Stop BrainFlow data stream. Automatically downloads BrainFlow if needed."
  [board-shim]
  (with-brainflow
    (.stop_stream board-shim)))

(defn get-board-data
  "Get data from BrainFlow board. Automatically downloads BrainFlow if needed."
  [board-shim & [num-samples]]
  (with-brainflow
    (if num-samples
      (.get_board_data board-shim (int num-samples))
      (.get_board_data board-shim))))

(defn release-session
  "Release BrainFlow session. Automatically downloads BrainFlow if needed."
  [board-shim]
  (with-brainflow
    (.release_session board-shim)))

(defn brainflow-initialized?
  "Check if BrainFlow has been initialized."
  []
  @initialized?)

(defn is-expected-jna-error?
  "Check if an exception is the expected JNA file extraction behavior"
  [exception]
  (let [msg (.getMessage exception)]
    (or (str/includes? msg "FileAlreadyExistsException")
        (str/includes? msg "BoardController.dll")
        (and (str/includes? msg "is not found in jar file")
             (str/includes? msg ".dll")))))

; Add this informational function
(defn explain-jna-messages
  "Explain the JNA extraction messages users might see"
  []
  (println)
  (println "=== About BrainFlow Library Extraction ===")
  (println "If you see messages like:")
  (println "  'Unpacking to: ...BoardController.dll'")
  (println "  'FileAlreadyExistsException: ...BoardController.dll'")
  (println)
  (println "This is NORMAL behavior. BrainFlow uses JNA (Java Native Access)")
  (println "to automatically extract and cache native libraries. These messages")
  (println "indicate the caching system is working correctly.")
  (println)
  (println "The 'FileAlreadyExistsException' just means the library was already")
  (println "extracted in a previous run - this is expected and harmless.")
  (println "==================================================="))

(defn get-cause-chain
  "Unwraps an exception into a sequence of causes, starting with the root."
  [^Throwable e]
  (loop [acc [] curr e]
    (if (and curr (not (some #(= curr %) acc)))
      (recur (conj acc curr) (.getCause curr))
      acc)))

(defn update-gitignore
  [filename]
  (let [gitignore-file (io/file ".gitignore")
        existing-content (if (.exists gitignore-file)
                           (slurp gitignore-file)
                           "")
        lines (if (empty? existing-content)
                []
                (clojure.string/split-lines existing-content))]

    (when-not (some #(= (clojure.string/trim %) filename) lines)
      (with-open [w (io/writer gitignore-file :append true)]
        (when-not (empty? existing-content)
          (.write w "\n"))
        (.write w "# BrainFlow local dependencies\n")
        (.write w (str filename "\n"))))))

(defn build-native-path
  "Builds the full native path including platform-specific directory"
  [base-native-path]
  (let [platform (get-os-arch)
        full-path (str base-native-path platform "/")]
    (println (format "Detected platform: %s" platform))
    (println (format "Native library path: %s" full-path))
    full-path))

(defn ensure-trailing-separator [^String path]
  (let [sep (System/getProperty "file.separator")]
    (if (.endsWith path sep)
      path
      (str path sep))))

(defn create-brainflow-local-edn
  "Creates the brainflow-local.edn file with the actual JAR path and jvm-opts"
  [jar-path native-lib-path]
  (let [local-file (io/file "brainflow-local.edn")
        jar (io/file jar-path)
        native-path (-> (io/file native-lib-path)
                        .getAbsolutePath
                        ensure-trailing-separator)

        brainflow-dep {:deps {'brainflow/brainflow {:local/root (.getAbsolutePath jar)}}
                       :jvm-opts [(str "-Djava.library.path=" native-path)]}]

    ; Write the brainflow-local.edn file (gitignored)
    (with-open [w (io/writer local-file)]
      (binding [*print-namespace-maps* false]
        (pprint/pprint brainflow-dep w)))

    (println (format "✓ Created brainflow-local.edn with path: %s" (.getAbsolutePath jar)))
    local-file))

(defn sort-aliases [aliases]
  (into (sorted-map)                        ; natural ascending order
        (map (fn [[k v]]
               [k (if (map? v) (sort-aliases v) v)]))
        aliases))

(defn smart-sort [m]
  (into (sorted-map-by #(compare %2 %1))   ; reverse top-level keys
        (map (fn [[k v]]
               (cond
                 (= k :aliases) [k (sort-aliases v)]  ; sort aliases ascending
                 (map? v)          [k (smart-sort v)] ; recurse other maps
                 :else             [k v])))
        m))

(defn update-project-deps
  "Updates deps.edn to reference brainflow-local.edn under :flow alias"
  [jar-path native-lib-base-path]
  (let [deps-file (io/file "deps.edn")
        native-lib-path (build-native-path native-lib-base-path)

        ; Create brainflow-local.edn with jvm-opts
        _ (create-brainflow-local-edn jar-path native-lib-path)

        edn-map (if (.exists deps-file)
                  (edn/read-string (slurp deps-file))
                  {})

        aliases (or (:aliases edn-map) {})
        existing-flow (get aliases :flow {})

        final-flow (-> existing-flow
                       (assoc :deps-file "brainflow-local.edn")
                       (dissoc :extra-deps))

        updated-aliases (assoc aliases :flow final-flow)
        final-edn (assoc edn-map :aliases updated-aliases)]

    (spit deps-file
          (zprint/zprint-str (smart-sort final-edn)
                             {:map {:sort? false}}))

    (try
      (edn/read-string (slurp deps-file))
      (println "✓ deps.edn updated and verified as valid EDN")
      (catch Exception e
        (println "ERROR: Generated invalid EDN file")
        (throw e)))

    (update-gitignore "brainflow-local.edn")

    (println "✓ Updated deps.edn to reference brainflow-local.edn file in :flow alias")
    (println "✓ Users can run: clojure -M:flow -m floj.cli")))

(defn test-brainflow
  "Test BrainFlow functionality with a synthetic board.
   Returns true if successful, throws exception if failed."
  ([] (test-brainflow -1 {})) ; Default to synthetic board
  ([board-id input-params]
   (try
     (println "Testing BrainFlow...")

     ; Step 1: Force initialization
     (ensure-brainflow-loaded!)
     (assert (brainflow-initialized?) "BrainFlow failed to initialize")

     ; Step 2: Try to create the board shim
     (println "Creating board shim...")
     (let [board (get-board-shim board-id input-params)]

       (println "Preparing session...")
       (prepare-session board)

       (println "Starting stream...")
       (start-stream board)

       (Thread/sleep 2000)

       (println "Stopping stream...")
       (stop-stream board)

       (println "Getting data...")
       (let [data (get-board-data board)]
         (println (format "Received data shape: %d channels, %d samples"
                          (count data)
                          (if (seq data) (count (first data)) 0))))

       (println "Releasing session...")
       (release-session board))

     (println "\n=== BrainFlow installation test PASSED successfully! ===")

     (let [base-path (str (System/getProperty "user.home") "/.brainflow-java/")
           jar-path (str base-path "5.16.0/brainflow-jar-with-dependencies.jar")
           native-path (str base-path "natives/")]
       (update-project-deps jar-path native-path))
     (println "Your BrainFlow installation is working correctly.")
     true

     (catch Exception e
       (println "\n=== BrainFlow test FAILED ===")
       (println "Error:" (.getMessage e))

       (if (some is-expected-jna-error? (get-cause-chain e))
         (do
           (println "; Detected expected JNA library extraction behavior")
           (explain-jna-messages)

           (throw e))
         (do
           (.printStackTrace e)
           (throw e)))))))

(defn -main []
  (test-brainflow))

(comment
  (test-brainflow))