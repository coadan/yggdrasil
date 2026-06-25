(ns ygg.hash
  "Stable hashing helpers for graph ids and content shas."
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(def ^:private ^"[C" hex-chars
  (char-array "0123456789abcdef"))

(defn- bytes->hex
  [^bytes bytes]
  (let [^"[C" table hex-chars
        ^"[C" out (char-array (* 2 (alength bytes)))]
    (dotimes [idx (alength bytes)]
      (let [value (bit-and (aget bytes idx) 0xff)
            out-idx (* 2 idx)]
        (aset-char out out-idx (aget table (bit-shift-right value 4)))
        (aset-char out (inc out-idx) (aget table (bit-and value 0x0f)))))
    (String. out)))

(defn sha256-hex
  "Return SHA-256 hex for a string value."
  [value]
  (bytes->hex
   (.digest (MessageDigest/getInstance "SHA-256")
            (.getBytes (str value) StandardCharsets/UTF_8))))

(defn sha256-bytes-hex
  "Return SHA-256 hex for a byte array."
  [bytes]
  (bytes->hex (.digest (MessageDigest/getInstance "SHA-256") bytes)))

(defn short-hash
  "Return a compact stable hash token for ids."
  [value]
  (subs (sha256-hex value) 0 16))
