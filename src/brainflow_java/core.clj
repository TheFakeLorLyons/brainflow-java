(ns brainflow-java.core
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.net URL URLClassLoader]           
           [java.io File]
           [java.util.concurrent.locks ReentrantLock]))

(def ^:private brainflow-version "5.16.0")
(def ^:private base-url "https://github.com/brainflow-dev/brainflow/releases/download")

; Thread-safe initialization
(def ^:private initialization-lock (ReentrantLock.))
(def ^:private initialized? (atom false))
(def ^:private initialization-error (atom nil))

(defn- get-os-arch []
  (let [os-name (str/lower-case (System/getProperty "os.name"))
        arch (System/getProperty "os.arch")]
    (cond
      (str/includes? os-name "linux") "linux-x86-64"
      (and (str/includes? os-name "mac") (or (= arch "aarch64") (= arch "arm64"))) "darwin-aarch64"
      (str/includes? os-name "mac") "darwin-x86-64"
      (str/includes? os-name "windows") "win32-x86-64"
      :else (throw (RuntimeException. (str "Unsupported platform: " os-name " " arch))))))

(defn- get-cache-dir []
  (let [home (System/getProperty "user.home")
        cache-dir (io/file home ".brainflow-java" brainflow-version)]
    (.mkdirs cache-dir)
    cache-dir))

(defn- file-exists-and-valid? [file expected-min-size]
  (and (.exists file)
       (.isFile file)
       (> (.length file) expected-min-size)))

(defn- download-with-progress [url dest-file]
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

(defn- extract-tar [tar-file dest-dir]
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

(defn- copy-native-libs
  "Copy all native libraries for the current platform"
  [from-dir to-dir platform-extensions]
  (let [copied-files (atom [])]
    (doseq [file (file-seq from-dir)]
      (when (and (.isFile file)
                 (is-native-lib-file? file platform-extensions))
        (let [dest (io/file to-dir (.getName file))]
          (println "Copying native lib:" (.getName file))
          (io/copy file dest)
          (swap! copied-files conj (.getName file)))))
    @copied-files))

(defn- platform-dir-has-natives?
  "Check if platform directory already has native libraries"
  [platform-dir platform-extensions]
  (let [files (filter #(.isFile %) (file-seq platform-dir))
        native-files (filter #(is-native-lib-file? % platform-extensions) files)]
    (seq native-files)))

(defn- download-and-cache-natives []
  (let [platform (get-os-arch)
        cache-dir (get-cache-dir)
        platform-dir (io/file cache-dir "natives" platform)
        platform-extensions (get-platform-extensions)
        archive-name "compiled_libs.tar"  ; Define the archive name
        archive-file (io/file cache-dir archive-name)]

    (.mkdirs platform-dir)

    ; Check if platform directory already has native libraries
    (when-not (platform-dir-has-natives? platform-dir platform-extensions)
      (println (str "Setting up BrainFlow native libraries for " platform "..."))

      ; Download archive if needed
      (when-not (file-exists-and-valid? archive-file 1000000) ; At least 1MB
        (let [url (str base-url "/" brainflow-version "/" archive-name)]
          (download-with-progress url archive-file)))

      ; Extract and copy native libraries
      (extract-tar archive-file cache-dir)
      (let [copied-files (copy-native-libs cache-dir platform-dir platform-extensions)]
        (println (str "Copied " (count copied-files) " native libraries: "
                      (str/join ", " copied-files)))

        ; Verify we got some files
        (when (empty? copied-files)
          (throw (RuntimeException.
                  (str "No native libraries found for platform " platform
                       " with extensions " platform-extensions)))))

      ; Clean up archive to save space
      (.delete archive-file))

    ; Return the platform directory path
    (.getAbsolutePath platform-dir)))

(defn- add-jar-to-classpath [jar-file]
  (try
    (let [jar-url (.toURI (.toURL jar-file))
          url-class-loader-method (.getDeclaredMethod URLClassLoader "addURL" (into-array Class [URL]))
          _ (.setAccessible url-class-loader-method true)
          class-loader (.getContextClassLoader (Thread/currentThread))]
      (.invoke url-class-loader-method class-loader (into-array Object [(.toURL jar-url)]))
      (println "BrainFlow JAR added to classpath"))
    (catch Exception e
      (throw (RuntimeException. (str "Failed to add JAR to classpath: " (.getMessage e)) e)))))

(defn- download-and-cache-jar []
  (let [cache-dir (get-cache-dir)
        jar-file (io/file cache-dir "brainflow-jar-with-dependencies.jar")]

    (when-not (file-exists-and-valid? jar-file 1000000) ; At least 1MB
      (println "Downloading BrainFlow Java library with dependencies...")
      (let [jar-url (str base-url "/" brainflow-version "/brainflow-jar-with-dependencies.jar")]
        (download-with-progress jar-url jar-file)))

    jar-file))

(defn- setup-native-library-path [native-path]
  (let [current-path (System/getProperty "java.library.path")
        new-path (if (str/blank? current-path)
                   native-path
                   (str current-path File/pathSeparator native-path))]
    (System/setProperty "java.library.path" new-path)

    ; Clear the library path cache so Java will re-read it
    (try
      (let [field (.getDeclaredField ClassLoader "sys_paths")]
        (.setAccessible field true)
        (.set field nil nil))
      (catch Exception e
        (println "Warning: Could not clear library path cache:" (.getMessage e))))))

(defn- ensure-brainflow-loaded!
  "Ensures BrainFlow is loaded. Throws exception if initialization fails."
  []
  (when-not @initialized?
    (.lock initialization-lock)
    (try
      (when-not @initialized?
        (if-let [error @initialization-error]
          (throw (RuntimeException. "BrainFlow initialization previously failed" error))
          (try
            (println "Initializing BrainFlow...")

            ; Download and set up Java library
            (let [jar-file (download-and-cache-jar)]
              (add-jar-to-classpath jar-file))

            ; Download and set up native libraries  
            (let [native-path (download-and-cache-natives)]
              (setup-native-library-path native-path))

            (reset! initialized? true)
            (println "BrainFlow initialization complete!")

            (catch Exception e
              (reset! initialization-error e)
              (println "BrainFlow initialization failed:" (.getMessage e))
              (throw e)))))
      (finally
        (.unlock initialization-lock)))))

; Macro to wrap BrainFlow functions with auto-loading
(defmacro with-brainflow [& body]
  `(do
     (ensure-brainflow-loaded!)
     ~@body))

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
    (let [params-class (Class/forName "brainflow.BrainFlowInputParams")
          constructor (.getDeclaredConstructor params-class (into-array Class []))
          params-instance (.newInstance constructor (into-array Object []))]

      (doseq [[key value] params-map]
        (when value
          (let [field-name (name key)
                field (.getDeclaredField params-class field-name)]
            (.setAccessible field true)
            (.set field params-instance value))))

      params-instance)))

(defn get-board-shim
  "Get a BrainFlow board shim. Downloads BrainFlow if needed. Takes board-id (int) 
   and input-params (map with keys like :serial_port, :ip_address, etc.)"
  [board-id input-params]
  (with-brainflow
    (let [board-shim-class (Class/forName "brainflow.BoardShim")
          params-class (Class/forName "brainflow.BrainFlowInputParams")
          params-instance (.newInstance (.getDeclaredConstructor params-class (into-array Class [])))]

      ; Set parameters on the BrainFlowInputParams instance
      (doseq [[key value] input-params]
        (when value  ; Only set non-nil values
          (let [field-name (name key)
                field (.getDeclaredField params-class field-name)]
            (.setAccessible field true)
            (.set field params-instance value))))

      ; Create BoardShim with proper constructor signature
      (.newInstance (.getDeclaredConstructor board-shim-class
                                             (into-array Class [Integer/TYPE params-class]))
                    (into-array Object [board-id params-instance])))))

(defn get-default-synth-board-shim
  "Simplified version that takes a board-id and optional parameters map"
  ([board-id]
   (get-board-shim board-id {}))
  ([board-id params-map]
   (with-brainflow
     (let [params-instance (create-input-params params-map)
           board-shim-class (Class/forName "brainflow.BoardShim")]
       (.newInstance (.getDeclaredConstructor board-shim-class
                                              (into-array Class [Integer/TYPE (.getClass params-instance)]))
                     (into-array Object [board-id params-instance]))))))

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
      (.start_stream board-shim buffer-size)
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
      (.get_board_data board-shim num-samples)
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

(defn get-brainflow-version
  "Get the BrainFlow version this library uses."
  []
  brainflow-version)

(defn clear-cache!
  "Clear the BrainFlow cache directory."
  []
  (let [cache-dir (get-cache-dir)]
    (when (.exists cache-dir)
      (doseq [file (reverse (file-seq cache-dir))]
        (when (.isFile file) (.delete file)))
      (doseq [dir (reverse (file-seq cache-dir))]
        (when (.isDirectory dir) (.delete dir))))
    (reset! initialized? false)
    (reset! initialization-error nil)
    (println "BrainFlow cache cleared")))

(defn init!
  "Manually initialize BrainFlow. This is optional as initialization happens automatically."
  []
  (ensure-brainflow-loaded!))

(defn test-brainflow
  "Test BrainFlow functionality with a synthetic board.
   This is useful for verifying that BrainFlow is working correctly.
   Returns true if successful, throws exception if failed."
  ([]
   (test-brainflow 0 {})) ; Default to synthetic board with no params
  ([board-id input-params]
   (try
     (println "Testing BrainFlow...")

     ; Step 0: Clean the currently existing cached files
     (clear-cache!)

     ; Step 1: Force initialization
     (init!)
     (assert (brainflow-initialized?) "BrainFlow failed to initialize")

     ; Step 2: Create board shim with proper parameters
     (println "Creating board shim...")
     (let [board (get-default-synth-board-shim board-id input-params)]

       (println "Preparing session...")
       (prepare-session board)

       (println "Starting stream...")
       (start-stream board)

       (Thread/sleep 2000) ; Give it 2 seconds to stream some data

       (println "Stopping stream...")
       (stop-stream board)

       (println "Getting data...")
       (let [data (get-board-data board)]
         (println "Received data shape:" (str (count data) " channels, "
                                              (if (seq data) (count (first data)) 0) " samples")))

       (println "Releasing session...")
       (release-session board))

     (println "BrainFlow test succeeded ✅")
     true

     (catch Exception e
       (println "BrainFlow test failed ❌")
       (println (.getMessage e))
       (throw e)))))