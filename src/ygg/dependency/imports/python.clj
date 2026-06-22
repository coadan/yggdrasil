(ns ygg.dependency.imports.python
  "Python standard-library import candidate filtering."
  (:require [ygg.dependency.imports.common :as import-common]
            [clojure.string :as str]))

(def stdlib-roots
  #{"__future__" "_thread" "abc" "aifc" "argparse" "array" "ast" "asyncio"
    "atexit" "base64" "bdb" "binascii" "bisect" "builtins" "bz2" "calendar"
    "cmath" "cmd" "code" "codecs" "codeop" "collections" "colorsys"
    "compileall" "concurrent" "configparser" "contextlib" "contextvars"
    "copy" "copyreg" "crypt" "csv" "ctypes" "curses" "dataclasses"
    "datetime" "dbm" "decimal" "difflib" "dis" "distutils" "doctest" "email"
    "encodings" "ensurepip" "enum" "errno" "faulthandler" "fcntl" "filecmp"
    "fileinput" "fnmatch" "fractions" "ftplib" "functools" "gc" "getopt"
    "getpass" "gettext" "glob" "graphlib" "grp" "gzip" "hashlib" "heapq"
    "hmac" "html" "http" "idlelib" "imaplib" "imghdr" "imp" "importlib"
    "inspect" "io" "ipaddress" "itertools" "json" "keyword" "lib2to3"
    "linecache" "locale" "logging" "lzma" "mailbox" "mailcap" "marshal"
    "math" "mimetypes" "mmap" "modulefinder" "multiprocessing" "netrc"
    "nis" "nntplib" "numbers" "operator" "optparse" "os" "pathlib" "pdb"
    "pickle" "pickletools" "pipes" "pkgutil" "platform" "plistlib" "poplib"
    "posix" "pprint" "profile" "pstats" "pty" "pwd" "py_compile" "pyclbr"
    "pydoc" "queue" "quopri" "random" "re" "readline" "reprlib"
    "resource" "rlcompleter" "runpy" "sched" "secrets" "select" "selectors"
    "shelve" "shlex" "shutil" "signal" "site" "smtpd" "smtplib" "sndhdr"
    "socket" "socketserver" "sqlite3" "ssl" "stat" "statistics" "string"
    "stringprep" "struct" "subprocess" "sunau" "symtable" "sys" "sysconfig"
    "tabnanny" "tarfile" "telnetlib" "tempfile" "termios" "textwrap"
    "threading" "time" "timeit" "tkinter" "token" "tokenize" "tomllib"
    "trace" "traceback" "tracemalloc" "tty" "turtle" "types" "typing"
    "unicodedata" "unittest" "urllib" "uuid" "venv" "warnings" "wave"
    "weakref" "webbrowser" "wsgiref" "xdrlib" "xml" "xmlrpc" "zipapp"
    "zipfile" "zipimport" "zlib" "zoneinfo"})

(defn- strip-py-extension
  [path]
  (str/replace (str path) #"\.py$" ""))

(defn- path-parts
  [path]
  (->> (str/split (strip-py-extension path) #"/")
       (remove str/blank?)
       vec))

(defn- python-path?
  [path]
  (str/ends-with? (str path) ".py"))

(defn- module-parts
  [path]
  (let [parts (path-parts path)]
    (if (= "__init__" (last parts))
      (vec (butlast parts))
      parts)))

(defn- package-init-paths
  [files-by-path]
  (->> (keys files-by-path)
       (filter #(str/ends-with? (str %) "/__init__.py"))
       (map #(subs (str %) 0 (- (count (str %)) (count "/__init__.py"))))
       set))

(defn- package-suffixes
  [init-paths path]
  (let [parts (module-parts path)]
    (->> (range (count parts))
         (keep (fn [idx]
                 (let [package-path (str/join "/" (take (inc idx) parts))]
                   (when (contains? init-paths package-path)
                     (str/join "." (drop idx parts))))))
         (remove str/blank?))))

(defn- local-package-targets
  [files-by-path]
  (let [init-paths (package-init-paths files-by-path)]
    (->> (keys files-by-path)
         (filter python-path?)
         (mapcat #(package-suffixes init-paths %))
         set)))

(defn- same-directory-module?
  [files-by-path source-path target]
  (let [dir (import-common/dirname source-path)
        root (import-common/dotted-root target)]
    (and (seq root)
         (or (contains? files-by-path (str dir "/" root ".py"))
             (contains? files-by-path (str dir "/" root "/__init__.py"))))))

(defn- import-prefix-match?
  [target import-name]
  (let [target (str target)
        import-name (str import-name)]
    (and (seq target)
         (seq import-name)
         (or (= target import-name)
             (str/starts-with? target (str import-name "."))))))

(defn external-package-candidate?
  [target]
  (not (contains? stdlib-roots (import-common/dotted-root target))))

(defn local-import?
  [{:keys [files-by-path edge local-namespace-targets target]}]
  (let [targets (into (local-package-targets files-by-path)
                      local-namespace-targets)]
    (or (some #(import-prefix-match? target %) targets)
        (same-directory-module? files-by-path (:path edge) target))))
