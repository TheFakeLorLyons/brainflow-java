# BrainFlow Java for Clojure

This library allows users to elegantly download the necessary dependencies for BrainFlow automatically, making it easy to work with EEG, EMG, ECG and other biosignal acquisition devices from Clojure.

This program will scan the computer to see if there are any missing required dependencies or subdependencies that are necessary to run BrainFlow, install them, and install BrainFlow. It is designed to work on Windows, Mac, and Linux but as of 5/28 has only been tested on Windows.

If the user does not have the required underlying C++ runtimes, it will prompt the user to respond 'y' or 'no' to downloading the dependencies.

The program can be run either at the repl using test-brainflow at the bottom of the namespace (or technically by running ensure-brainflow-loaded! on its own), or by running the main function at the command line via:
- `clj -M:dev core.clj` 

## What is BrainFlow?

[BrainFlow](https://brainflow.org/) is a library intended to obtain, parse and analyze EEG, EMG, ECG and other kinds of data from biosignal acquisition devices. It supports many popular devices including:

- OpenBCI boards (Cyton, Ganglion, etc.)
- Muse headbands
- Emotiv devices
- NeuroSky devices
- And many more

## ⚠️ Important Considerations

# ¡ Necessary Flag in deps :aliases !

- In order to properly utilize these dependencies, it is necessary to add the following line into your deps file:
   - `:dev {:jvm-opts ["--add-opens=java.base/java.net=ALL-UNNAMED"]}`

#  ¡ This downloader -requires- that you have babashka installed !
- Information on babashka here: [Babashka site](https://babashka.org/)

### System Requirements

- **Windows**: Visual C++ Redistributables (automatically installed)
- **macOS/Linux**: Standard development tools
- **Java 8+** and **Clojure**

### Data Usage Warning

This library will download approximately **100MB** of data including:

- BrainFlow Java library (~30MB)
- Native libraries for your platform (~70MB)
- Visual C++ Redistributables (Windows only, ~50MB)

Files are cached locally in `~/.brainflow-java/` to avoid re-downloading.

### Security Considerations:
- Downloads binaries from GitHub releases ([github.com/brainflow-dev/brainflow](https://github.com/brainflow-dev/brainflow))
- On Windows, provides the option to automatically install Visual C++ Redistributables
- Loads native libraries into JVM process
- **Review the source code before using in production environments**

## Get Started:

Add the following to your `deps.edn`:
`brainflow-java/brainflow-java {:mvn/version "1.0.006"}`

__Lorelai Lyons 2025__