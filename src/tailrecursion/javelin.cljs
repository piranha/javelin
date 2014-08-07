;; Copyright (c) Alan Dipert and Micha Niskin. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns tailrecursion.javelin
  (:require [tailrecursion.priority-map :refer [priority-map]]))

;; helpers ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare Cell cell? cell)

(def ^:dynamic *sync*    nil)
(def ^:private last-rank (atom 0))

(defn- bf-seq
  "Like tree-seq but traversal is breadth-first instead of depth-first."
  [branch? children root]
  (letfn [(walk [queue]
            (when-let [node (peek queue)]
              (->> (when (branch? node) (children node))
                (into (pop queue)) walk (cons node) lazy-seq)))]
    (walk (conj cljs.core.PersistentQueue.EMPTY root))))

(defn- propagate* [pri-map]
  (when-let [next (first (peek pri-map))]
    (let [popq  (pop pri-map)
          old   (.-prev next)
          new   ((.-thunk next))
          diff? (not= new old)]
      (when diff? (set! (.-prev next) new) (-notify-watches next old new))
      (recur (if-not diff? popq (reduce #(assoc %1 %2 (.-rank %2)) popq (.-sinks next)))))))

(defn  deref*     [x] (if (cell? x) @x x))
(defn- next-rank  [ ] (swap! last-rank inc))
(defn- cell->pm   [c] (priority-map c (.-rank c)))
(defn- add-sync!  [c] (swap! *sync* assoc c (.-rank c)))
(defn- propagate! [c] (if *sync* (doto c add-sync!) (doto c (-> cell->pm propagate*))))

;; javelin ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn destroy-cell! [this & [keep-watches?]]
  (let [srcs (.-sources this)]
    (set! (.-sources this) [])
    (when-not keep-watches? (set! (.-watches this) {}))
    (doseq [src srcs]
      (when (cell? src)
        (set! (.-sinks src) (disj (.-sinks src) this))))))

(defn set-formula! [this & [f sources]]
  (destroy-cell! this true)
  (set! (.-sources this) (if f (conj (vec sources) f) (vec sources)))
  (doseq [source (.-sources this)]
    (when (cell? source)
      (set! (.-sinks source) (conj (.-sinks source) this))
      (if (> (.-rank source) (.-rank this))
        (doseq [dep (bf-seq identity #(.-sinks %) source)]
          (set! (.-rank dep) (next-rank))))))
  (let [compute #(apply (deref* (peek %)) (map deref* (pop %)))
        thunk   #(set! (.-state this) (compute (.-sources this)))]
    (set! (.-input? this) (not f))
    (set! (.-thunk this) (if f thunk #(deref this)))
    (propagate! this)))

(deftype Cell [meta state rank prev sources sinks thunk watches input?]
  cljs.core/IPrintWithWriter
  (-pr-writer [this writer opts]
    (write-all writer "#<Cell: " (pr-str state) ">"))

  cljs.core/IMeta
  (-meta [this] meta)

  cljs.core/IDeref
  (-deref [this] (.-state this))

  cljs.core/IReset
  (-reset! [this x]
    (if (.-input? this)
      (do (set! (.-state this) x) (propagate! this) x)
      (throw (js/Error. "can't swap! or reset! formula cell"))))

  cljs.core/ISwap
  (-swap! [this f]        (reset! this (f (.-state this))))
  (-swap! [this f a]      (reset! this (f (.-state this) a)))
  (-swap! [this f a b]    (reset! this (f (.-state this) a b)))
  (-swap! [this f a b xs] (reset! this (apply f (.-state this) a b xs)))

  cljs.core/IWatchable
  (-notify-watches [this o n] (doseq [[key f] watches] (f key this o n)))
  (-add-watch      [this k f] (set! (.-watches this) (assoc watches k f)))
  (-remove-watch   [this k]   (set! (.-watches this) (dissoc watches k))))

(defn cell?     [c]   (when (= (type c) Cell) c))
(defn input?    [c]   (when (and (cell? c) (.-input? c)) c))
(defn set-cell! [c x] (set! (.-state c) x) (set-formula! c))
(defn formula   [f]   (fn [& sources] (set-formula! (cell ::none) f sources)))
(defn cell      [x]   (set-formula! (Cell. {} x (next-rank) x [] #{} nil {} nil)))

(def ^:deprecated lift formula)

;; javelin util ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn dosync* [thunk]
  (let [bind #(binding [*sync* (atom (priority-map))] (%))]
    (if *sync* (thunk) (bind #(do (thunk) (propagate* @*sync*))))))

(defn alts! [& cells]
  (let [olds    (atom (repeat (count cells) ::none)) 
        tag-neq #(vector (not= %1 %2) %2)
        diff    #(->> %2 (map tag-neq %1) (filter first) (map second) distinct)
        proc    #(let [news (diff (deref olds) %&)] (reset! olds %&) news)]
    (apply (formula proc) cells))) 

(defn cell-map [f c]
  (let [cseq ((formula seq) c)]
    (map #((formula (comp f safe-nth)) cseq %) (range 0 (count @cseq)))))

(defn cell-doseq* [items f]
  (let [pool-size (cell 0)
        items-seq ((formula seq) items)
        cur-count ((formula count) items-seq)
        ith-item  #((formula safe-nth) items-seq %)]
    ((formula (fn [pool-size cur-count f ith-item reset-pool-size!]
                (when (< pool-size cur-count)
                  (doseq [i (range pool-size cur-count)] (f (ith-item i)))
                  (reset-pool-size! cur-count))))
     pool-size cur-count f ith-item (partial reset! pool-size))))
