(ns agraph.hash
  "Stable hashing helpers for graph ids and content shas."
  (:import [java.security MessageDigest]))

(defn sha256-hex
  "Return SHA-256 hex for a string value."
  [value]
  (let [bytes (.digest (MessageDigest/getInstance "SHA-256")
                       (.getBytes (str value) "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) bytes))))

(defn short-hash
  "Return a compact stable hash token for ids."
  [value]
  (subs (sha256-hex value) 0 16))
