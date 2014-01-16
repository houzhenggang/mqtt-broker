(ns mqtt-broker.subs
   (:refer-clojure :exclude [remove empty]
    :rename {empty? core-empty?})
   (:use [clojure.string :only [join]]))

;; A prefix tree with wildcards, for storing subscriptions.

;;  A message topic is a sequence of strings. Subscription topics are
;; sequences of strings, along with wildcards: '+' to match a single
;; word, and '#' to match all remaining words.

;; A node is either an internal node, which is a map of prefix words
;; to other nodes; or, a leaf node, which has a suffix and a set of
;; result values. An internal node may also have a '+' child, and a
;; '#' child.


;; I'll assume a topic is a sequence of words, e.g., ["foo" "bar"
;; "baz"] or ["+" "bar" "#"].

(defprotocol Node
  ;; Return the set of matches given a suffix
  (matches [this suffix])
  ;; Return a node with the value inserted at suffix
  (insert [this topic value])
  (remove [this topic value]))

(defprotocol Mappity
  (subs-map [this prefix]))

(defn ->map [root]
  (subs-map root ""))

(declare make-internal-node)
(declare make-leaf-node)

(def EMPTY
  (reify
    Node
    (matches [this topic]
      #{})
    (insert [this topic value]
      (make-leaf-node topic #{value}))
    (remove [this topic value]
      this)
    Mappity
    (subs-map [this prefix] {})))

(defn empty? [subs]
  (= subs EMPTY))

(deftype LeafNode [suffix values]
  Node
  (matches [this topic]
    (if (= topic suffix)
      values
      #{}))

  (insert [this topic value]
    (if (= topic suffix)
      (make-leaf-node suffix (conj values value))
      (let [next (first topic)]
        (if (core-empty? suffix)
          (let [child (make-leaf-node (rest topic) #{value})]
            (make-internal-node {next child} values))
          (let [child (make-leaf-node (rest suffix) values)
                parent (make-internal-node {(first suffix) child} #{})]
            (insert parent topic value))))))

  (remove [this topic value]
    (if (= topic suffix)
      (make-leaf-node suffix (disj values value))
      this))

  Mappity
  (subs-map [this prefix]
    {(str prefix "/" (join "/" suffix)) values}))

(defn make-leaf-node [suffix values]
  (if (core-empty? values)
    EMPTY
    (LeafNode. suffix values)))

(deftype InternalNode [prefixes here]
  Node
  (matches [this topic]
    (if (core-empty? topic)
      here
      (let [next (first topic)
            child (get prefixes next)]
        (if (nil? child)
          #{}
          (matches child (rest topic))))))

  (insert [this topic value]
    (if (core-empty? topic)
      (make-internal-node prefixes (conj here value))
      (let [next (first topic)
            child (get prefixes next)]
        (let [newchild (if (nil? child)
                         (make-leaf-node (rest topic) #{value})
                         (insert child (rest topic) value))]
          (make-internal-node (assoc prefixes next newchild) here)))))

  (remove [this topic value]
    (if (core-empty? topic)
      (make-internal-node prefixes (disj here value))
      (let [next (first topic)
            child (get prefixes next)]
        (if (nil? child)
          this
          (let [without (remove child (rest topic) value)]
            (if (empty? without)
              (make-internal-node (dissoc prefixes next) here)
              (make-internal-node (assoc prefixes next without) here)))))))

  Mappity
  (subs-map [this prefix]
    (let [entries (if (core-empty? here) {} {prefix here})]
      (reduce (fn [es k] (merge es (subs-map (get prefixes k)
                                             (str prefix "/" k))))
              entries (keys prefixes)))))

(defn make-internal-node [prefixes here]
  (if (and (core-empty? prefixes) (core-empty? here))
    EMPTY
    (InternalNode. prefixes here)))

(defn empty []
  EMPTY)