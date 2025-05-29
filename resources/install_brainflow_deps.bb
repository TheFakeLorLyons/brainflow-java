#!/usr/bin/env bb
(require '[babashka.http-client :as http]
         '[babashka.fs :as fs]
         '[babashka.process :as process]
         '[clojure.string :as str]
         '[clojure.java.io :as io])

(def brainflow-version "5.16.0")

(def os-type
  (let [os-name (str/lower-case (System/getProperty "os.name"))]
    (cond
      (str/includes? os-name "windows") :windows
      (str/includes? os-name "mac") :macos
      (str/includes? os-name "linux") :linux
      :else :unknown)))

(def arch-type
  (let [arch (str/lower-case (System/getProperty "os.arch"))]
    (cond
      (or (str/includes? arch "amd64") (str/includes? arch "x86_64")) :x64
      (str/includes? arch "x86") :x86
      (or (str/includes? arch "aarch64") (str/includes? arch "arm64")) :arm64
      :else :unknown)))

(defn get-jvm-bitness
  "Get actual JVM bitness - this is crucial for loading correct libraries"
  []
  (let [data-model (System/getProperty "sun.arch.data.model")]
    (println (format "; JVM Data Model: %s-bit" data-model))
    data-model))

(def dependency-packages
  {; Windows dependencies
   :windows
   [{:name "Universal C Runtime Update (KB2999226)"
     :url "https://download.microsoft.com/download/D/1/3/D13E3150-3BB2-4B22-9D8A-47EE2D609FFF/Windows8.1-KB2999226-x64.msu"
     :filename "Windows8.1-KB2999226-x64.msu"
     :args ["/quiet" "/norestart"]
     :priority 0
     :arch [:x64]
     :type :windows-update
     :required true}

    {:name "Visual C++ 2015-2022 Redistributable (x64)"
     :url "https://aka.ms/vs/17/release/vc_redist.x64.exe"
     :filename "vc_redist_2022_x64.exe"
     :args ["/quiet" "/norestart"]
     :priority 1
     :arch [:x64]
     :type :vcredist
     :required true}

    {:name "Visual C++ 2015-2022 Redistributable (x86)"
     :url "https://aka.ms/vs/17/release/vc_redist.x86.exe"
     :filename "vc_redist_2022_x86.exe"
     :args ["/quiet" "/norestart"]
     :priority 2
     :arch [:x64 :x86] ; Install both on x64 systems for compatibility
     :type :vcredist
     :required true}

    {:name "Visual C++ 2013 Redistributable (x64)"
     :url "https://download.microsoft.com/download/2/E/6/2E61CFA4-993B-4DD4-91DA-3737CD5CD6E3/vcredist_x64.exe"
     :filename "vc_redist_2013_x64.exe"
     :args ["/quiet" "/norestart"]
     :priority 3
     :arch [:x64]
     :type :vcredist
     :required false}

    {:name "Visual C++ 2013 Redistributable (x86)"
     :url "https://download.microsoft.com/download/2/E/6/2E61CFA4-993B-4DD4-91DA-3737CD5CD6E3/vcredist_x86.exe"
     :filename "vc_redist_2013_x86.exe"
     :args ["/quiet" "/norestart"]
     :priority 4
     :arch [:x64 :x86]
     :type :vcredist
     :required false}

    {:name "DirectX End-User Runtime"
     :url "https://download.microsoft.com/download/1/7/1/1718CCC4-6315-4D8E-9543-8E28A4E18C4C/dxwebsetup.exe"
     :filename "dxwebsetup.exe"
     :args ["/Q"]
     :priority 5
     :arch [:x64 :x86]
     :type :directx
     :required false}

    {:name "Universal CRT Update (KB2999226)"
     :url "https://support.microsoft.com/en-us/topic/update-for-universal-c-runtime-in-windows-2999226"
     :filename "windows10.0-kb2999226-x64.msu"
     :args ["/quiet" "/norestart"]
     :priority 0
     :arch [:x64]
     :type :windows-update}]

   ; Linux dependencies
   :linux
   [{:name "Build Essential"
     :package "build-essential"
     :priority 1
     :type :apt}
    {:name "CMake"
     :package "cmake"
     :priority 2
     :type :apt}
    {:name "USB Development Libraries"
     :package "libusb-1.0-0-dev"
     :priority 3
     :type :apt}
    {:name "USB Utilities"
     :package "usbutils"
     :priority 4
     :type :apt}
    {:name "Python3 Development"
     :package "python3-dev"
     :priority 5
     :type :apt}]

   ; macOS dependencies
   :macos
   [{:name "Xcode Command Line Tools"
     :command ["xcode-select" "--install"]
     :priority 1
     :type :xcode}
    {:name "Homebrew libusb"
     :package "libusb"
     :priority 2
     :type :brew}
    {:name "Homebrew cmake"
     :package "cmake"
     :priority 3
     :type :brew}]})

(defn log [msg & args]
  (println (str "; " (apply format msg args))))

(defn log-success [msg & args]
  (println (str "; ✓ " (apply format msg args))))

(defn log-error [msg & args]
  (println (str "; ✗ " (apply format msg args))))

(defn log-warning [msg & args]
  (println (str "; ⚠️  " (apply format msg args))))

(defn get-platform-packages
  []
  (get dependency-packages os-type []))

(defn package-applies-to-arch?
  [package]
  (if-let [supported-archs (:arch package)]
    (contains? (set supported-archs) arch-type)
    true)) ; If no arch specified, assume it applies to all

(defn download-file
  [url filename]
  (log "Downloading %s..." filename)
  (try
    (let [response (http/get url {:as :bytes :timeout 300000}) ; 5 min timeout
          status (:status response)]
      (if (= 200 status)
        (let [file-path (fs/path (fs/temp-dir) filename)]
          (spit (str file-path) (:body response))
          (println (str "✓ Downloaded " filename " (" (count (:body response)) " bytes)"))
          file-path)
        (do
          (println (str "✗ Failed to download " filename ": HTTP " status))
          nil)))
    (catch Exception e
      (println (str "✗ Exception during download of " filename ": " (.getMessage e)))
      nil)))

(defn command-exists? [command]
  (try
    (let [result (process/shell {:out :string :err :string :continue true}
                                (if (= os-type :windows)
                                  ["where" command]
                                  ["which" command]))]
      (zero? (:exit result)))
    (catch Exception _
      false)))

(defn check-package-manager
  []
  (cond
    (= os-type :linux)
    (cond
      (command-exists? "apt-get") :apt
      (command-exists? "yum") :yum
      (command-exists? "dnf") :dnf
      (command-exists? "pacman") :pacman
      :else :unknown)

    (= os-type :macos)
    (if (command-exists? "brew") :brew :none)

    :else :none))

(defn check-windows-dlls
  "Check for required Windows DLLs including Universal CRT"
  []
  (when (= os-type :windows)
    (let [system32 (str (System/getenv "WINDIR") "\\System32\\")
          syswow64 (str (System/getenv "WINDIR") "\\SysWOW64\\")
          required-dlls ["VCRUNTIME140.dll" "VCRUNTIME140_1.dll" "MSVCP140.dll"
                         "api-ms-win-crt-runtime-l1-1-0.dll"
                         "api-ms-win-crt-heap-l1-1-0.dll"
                         "api-ms-win-crt-stdio-l1-1-0.dll"
                         "ucrtbase.dll"]
          check-locations [system32 syswow64]
          results (for [dll required-dlls]
                    (let [found-in (some #(when (fs/exists? (fs/path (str % dll))) %)
                                         check-locations)]
                      {:dll dll :found found-in :exists (boolean found-in)}))]
      {:total (count results)
       :found (count (filter :exists results))
       :missing (map :dll (filter #(not (:exists %)) results))
       :details results})))

(defn verify-dependencies []
  (log "Verifying system dependencies...")
  (case os-type
    :windows
    (let [dll-check (check-windows-dlls)]
      (if (= (:found dll-check) (:total dll-check))
        (do
          (log-success "All required Windows DLLs found")
          true)
        (do
          (log-warning "Missing Windows DLLs: %s" (str/join ", " (:missing dll-check)))
          false)))

    :linux
    (let [required-commands ["gcc" "cmake" "pkg-config"]]
      (if (every? command-exists? required-commands)
        (do
          (log-success "All required Linux tools found")
          true)
        (do
          (log-warning "Some required Linux tools are missing")
          false)))

    :macos
    (let [required-commands ["clang" "cmake"]]
      (if (every? command-exists? required-commands)
        (do
          (log-success "All required macOS tools found")
          true)
        (do
          (log-warning "Some required macOS tools are missing")
          false)))

    (do
      (log-warning "Cannot verify dependencies on unsupported OS")
      false)))

(defn run-as-admin
  "Run a command with elevated privileges on Windows"
  [command args]
  (if (= os-type :windows)
    (let [powershell-cmd ["powershell" "-Command"
                          (format "Start-Process -FilePath '%s' -ArgumentList '%s' -Verb RunAs -Wait"
                                  command (str/join "','" args))]
          result (process/shell {:out :string :err :string :timeout 600000} powershell-cmd)]
      result)
    (process/shell {:out :string :err :string :timeout 600000} (concat [command] args))))

(defn install-windows-package [package-info]
  (log "Installing %s..." (:name package-info))
  (let [url (:url package-info)
        filename (:filename package-info)
        file-path (download-file url filename)]
    (if file-path
      (try
        (let [install-command (str file-path)
              args (:args package-info)
              _ (log "Running: %s %s" install-command (str/join " " args))
              ; Try to run with elevated privileges
              result (run-as-admin install-command args)]
          (fs/delete-if-exists file-path)
          (cond
            (zero? (:exit result))
            (do
              (log-success "Successfully installed %s" (:name package-info))
              {:success true :name (:name package-info)})

            (contains? #{1638 1641} (:exit result))
            (do
              (log "Package %s already installed" (:name package-info))
              {:success true :name (:name package-info) :already-installed true})

            (= 3010 (:exit result))
            (do
              (log-warning "Package %s installed, reboot required" (:name package-info))
              {:success true :name (:name package-info) :reboot-required true})

            ; Some installers return 1 but actually succeed
            (and (= 1 (:exit result)) (str/includes? (:out result) "successful"))
            (do
              (log-success "Package %s installed (non-zero exit but success message)" (:name package-info))
              {:success true :name (:name package-info)})

            :else
            (do
              (log-error "Installation failed with exit code %d" (:exit result))
              (log "STDOUT: %s" (:out result))
              (log "STDERR: %s" (:err result))
              {:success false :name (:name package-info) :exit-code (:exit result)})))
        (catch Exception e
          (log-error "Exception during install of %s: %s" (:name package-info) (.getMessage e))
          {:success false :name (:name package-info) :error (.getMessage e)}))
      (do
        (log-error "Download failed for %s" (:name package-info))
        {:success false :name (:name package-info) :error "Download failed"}))))

(defn install-with-apt [package-name]
  (log "Installing %s with apt..." package-name)
  (try
    (process/shell {:inherit true :timeout 300000} ["sudo" "apt-get" "update"])
    (let [result (process/shell {:inherit true :timeout 600000}
                                ["sudo" "apt-get" "install" "-y" package-name])]
      (zero? (:exit result)))
    (catch Exception e
      (log-error "Failed to install %s: %s" package-name (.getMessage e))
      false)))

(defn install-with-brew [package-name]
  (log "Installing %s with Homebrew..." package-name)
  (try
    (process/shell {:inherit true :timeout 300000} ["brew" "update"])
    (let [result (process/shell {:inherit true :timeout 600000}
                                ["brew" "install" package-name])]
      (zero? (:exit result)))
    (catch Exception e
      (log-error "Failed to install %s: %s" package-name (.getMessage e))
      false)))

(defn install-xcode-tools []
  (log "Checking for Xcode Command Line Tools...")
  (try
    (let [check (process/shell {:continue true :out :string} ["xcode-select" "-p"])]
      (if (zero? (:exit check))
        (do
          (log-success "Xcode Command Line Tools already installed")
          true)
        (do
          (log "Installing Xcode Command Line Tools...")
          (let [install-result (process/shell {:inherit true :timeout 1800000}
                                              ["xcode-select" "--install"])]
            (log-success "Xcode Command Line Tools installation initiated")
            true))))
    (catch Exception e
      (log-error "Xcode installation failed: %s" (.getMessage e))
      false)))

(defn jvm-arch
  "Get JVM architecture info for native library selection"
  []
  (System/getProperty "sun.arch.data.model"))

(defn install-package [package package-manager]
  (case (:type package)
    :vcredist (install-windows-package package)
    :windows-update (install-windows-package package)
    :directx (install-windows-package package)
    :apt {:success (install-with-apt (:package package)) :name (:name package)}
    :brew {:success (install-with-brew (:package package)) :name (:name package)}
    :xcode {:success (install-xcode-tools) :name (:name package)}
    {:success false :name (:name package) :error "Unknown package type"}))

(defn install-dependencies
  "Install platform-specific dependencies with better error handling"
  []
  (let [packages (->> (get-platform-packages)
                      (filter package-applies-to-arch?)
                      (sort-by :priority))
        package-manager (check-package-manager)]

    (if (empty? packages)
      (do
        (log "No dependencies defined for %s" (name os-type))
        [])
      (do
        (log "Installing %d dependencies for %s (%s, JVM: %s-bit)"
             (count packages) (name os-type) (name arch-type) jvm-arch)

        (when (and (not= os-type :windows) (= package-manager :unknown))
          (log-warning "No supported package manager found"))

        (let [results (atom [])
              required-packages (filter :required packages)
              optional-packages (filter #(not (:required %)) packages)]

          (log "Installing required dependencies...")
          (doseq [pkg required-packages]
            (let [result (install-package pkg package-manager)]
              (swap! results conj result)
              (when (not (:success result))
                (log-error "Failed to install required dependency: %s" (:name pkg)))
              (Thread/sleep 2000)))
          
          (when (seq optional-packages)
            (log "Installing optional dependencies...")
            (doseq [pkg optional-packages]
              (let [result (install-package pkg package-manager)]
                (swap! results conj result)
                (Thread/sleep 1000))))

          @results)))))

(defn print-summary [results]
  (let [successful (count (filter :success results))
        total (count results)]
    (log "Installation complete: %d of %d succeeded" successful total)
    (doseq [r results]
      (if (:success r)
        (log-success "%s installed" (:name r))
        (log-error "%s failed - %s" (:name r) (or (:error r) "Unknown error"))))))

(defn analyze-system-state
  []
  (println (str "=== System Analysis ==="))
  (println (str "OS: " (System/getProperty "os.name")))
  (println (str "Architecture: " (System/getProperty "os.arch")))
  (println (str "Detected OS Type: " (name os-type)))
  (println (str "Detected Arch: " (name arch-type)))

  (when (= os-type :windows)
    (let [dll-status (check-windows-dlls)
          missing (filter #(not (:exists %)) dll-status)]
      (println (str "Windows DLL Status: " (- (count dll-status) (count missing))
                    "/" (count dll-status) " found"))))

  (when (not= os-type :windows)
    (let [pm (check-package-manager)]
      (println (str "Package Manager: " (name pm)))))

  (println))

(defn install-dependencies
  []
  (let [packages (get-platform-packages)
        applicable-packages (filter package-applies-to-arch? packages)
        sorted-packages (sort-by :priority applicable-packages)
        package-manager (check-package-manager)]

    (if (empty? sorted-packages)
      (do
        (println (str "No dependencies defined for " (name os-type)))
        [])
      (do
        (println (str "Installing " (count sorted-packages) " dependencies for "
                      (name os-type) " (" (name arch-type) ")..."))

        (when (and (not= os-type :windows) (= package-manager :unknown))
          (println "⚠ Warning: No supported package manager found"))

        (let [results (atom [])]
          (doseq [package sorted-packages]
            (let [result (install-package package package-manager)]
              (swap! results conj result)
              (Thread/sleep 2000))) ; Brief pause between installations
          @results)))))

(defn print-summary
  [results]
  (let [successful (count (filter :success results))
        total (count results)
        reboot-needed (some :reboot-required results)]

    (println "\n=== Installation Summary ===")
    (println (str "Successful: " successful "/" total))

    (doseq [result results]
      (if (:success result)
        (println (str "✓ " (:name result)))
        (println (str "✗ " (:name result)
                      (when (:error result) (str " - " (:error result)))))))

    (when reboot-needed
      (println "\n⚠ IMPORTANT: System restart recommended"))

    (when (< successful total)
      (println "\nSome installations failed. This may be due to:")
      (println "- Missing permissions (try running with sudo/administrator)")
      (println "- Network connectivity issues")
      (println "- Unsupported system configuration"))

    (println (str "\n✓ Cross-platform dependency installation complete"))

    (> successful 0))) ; Return true if any succeeded

(defn main []
  (log "=== BrainFlow Setup Script ===")
  (log "Detected OS: %s" (System/getProperty "os.name"))
  (log "Architecture: %s" (System/getProperty "os.arch"))
  (analyze-system-state)

  (if (and (not= os-type :unknown) (not= arch-type :unknown))
    (do
      (verify-dependencies)
      (let [results (install-dependencies)]
        (print-summary results)))
    (log-error "Unsupported OS or architecture")))

(when (= *file* (System/getProperty "babashka.file"))
  (main))