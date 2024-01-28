(ns advent2022.day16
  (:require [advent2022.utils :as utils]
            [clojure.set :as set]
            [clojure.data.priority-map :refer [priority-map]]))

;; I guess we start with floyd warshall, eliminate the 00 flow nodes, and
;; perform some kind of search.

(def lines (utils/read-resource-lines "input/day16.txt"))

(def valve-re #"Valve (\w+) has flow rate=(\d+); (tunnels lead to valves|tunnel leads to valve) (\w+(, \w+)*)")

(re-matches valve-re "Valve HH has flow rate=22; tunnel leads to valve GG")

(defn parse-line [[adjacency flow] line]
  (let [[_ valve flow-str _ tunnels-str] (re-matches valve-re line)
        valve-flow (utils/parse-int flow-str)
        tunnels (.split tunnels-str ", ")]
    [(assoc adjacency valve (set tunnels))
     (assoc flow valve valve-flow)]))


;; so now we floyd-warshall the graph, just so we don't waste any time faffing
;; about on intermediate nodes.

(def adjacency-matrix (first (reduce parse-line [{} {}] lines)))
(def flows (second (reduce parse-line [{} {}] lines)))

(defn adjacency-edges [adjancency]
  (reduce
   (fn [acc k]
     (reduce
      (fn [acc v]
        (conj acc [k v]))
      acc
      (adjancency k)))
   []
   (keys adjancency)))
(adjacency-edges adjacency-matrix)

(defn all-pairs-shortest-paths [adjacency]
  (let [initial-distances (->> (keys adjacency)
                               (into {} (map #(vector [% %] 0)))
                               (merge (into {} (map #(vector % 1) (adjacency-edges adjacency)))))]
    (reduce
     (fn [distances [k i j]]
       (let [dist-i-j (get distances [i j] Integer/MAX_VALUE)
             dist-k-j (get distances [k j] Integer/MAX_VALUE)
             dist-i-k (get distances [i k] Integer/MAX_VALUE)]
         (if (> dist-i-j (+ dist-i-k dist-k-j))
           (assoc distances [i j] (+ dist-i-k dist-k-j))
           distances)))
     initial-distances
     (for [k (keys adjacency)
           i (keys adjacency)
           j (keys adjacency)]
       [k i j]))))


;; following a reddit comment we'll use A* search and use the cost function
;; as any valve NOT open causes the cost to increase.
;; we don't have a goal.  our final nodes will all have timeLeft < 0 and we'll
;; take the min cost from that.
;; we similarly don't really have a heuristic function.
;; I guess A* is just fancy Dijkstra's in this case.

(map-indexed vector [1 2])

(def valves
  (->> flows
      ;;  (filter #(> (second %) 0))
       (map first)
       (apply hash-set)))

(def bit-idx (into {} (map-indexed vector valves)))
(def reverse-bit-idx (set/map-invert bit-idx))

(defn calculate-cost [open-valves elapsed-time]
  (->> valves
       (remove #(bit-test open-valves (reverse-bit-idx %)))
       (map flows)
       (reduce + 0)
       (* elapsed-time)))

(defn maybe-add-score [node current-node score goal-score came-from]
  (let [should-add (< score (get goal-score node Integer/MAX_VALUE))]
    [(if should-add (assoc goal-score node score) goal-score)
     (if should-add (assoc came-from node current-node) came-from)
     should-add]))

(defn my-test [[a b c :as all] d]
  {:a a :b b :c c :d d :all all})

(my-test [1 2 3 4 5 6 7] 8)

(defn is-valve-open? [open-valves valve]
  (bit-test open-valves (reverse-bit-idx valve)))

(defn open-valve [open-valves valve]
  (bit-set open-valves (reverse-bit-idx valve)))

(defn reconstruct-path [node came-from]
  (loop [curr node result []]
    (if (contains? came-from curr)
      (recur (came-from curr) (conj result curr))
      (reverse (map first (conj result curr))))))

;; so this works for part 1
;; for part 2, we have an elephant, and less time.
;; kind of feels like floyd-warshall isn't helpful, though it does increase
;; the search state.  the elephant and you moving at once seems important,
;; and if it takes you + elephant different time to get there, things are
;; going to get rough in the logic.
;; so OK, we'll rewrite the algorithm to have the elephant, won't try to share
;; code, at least at first.  but scale isn't much worse and our approach
;; was shown to work for part 1.

;; actually we can make this usable for both me and elephant (bffs).
;; the real elephant combo logic will be in list merging.
(defn get-neighbor-node-list [[location open-valves time-left]]
  (let [is-valve-closed (not (is-valve-open? open-valves location))
        should-only-open-valve (>= (flows location) 4)]
    (if (and is-valve-closed should-only-open-valve)
      [[location (open-valve open-valves location) (dec time-left)]]
    ;; otherwise process each neighbor of the adjacency matrix
      (reduce
       (fn [acc neighbor-location]
         (conj acc [neighbor-location open-valves (dec time-left)]))
       (if (and (> (get flows location 0) 0) is-valve-closed)
         [[location (open-valve open-valves location) (dec time-left)]]
         [])
       (adjacency-matrix location)))))

(defn combo-lists [list1 list2]
  (for [[location open-valves] list1
        [elephant-location ele-open-valves time-left] list2]
    [location elephant-location (bit-or open-valves ele-open-valves) time-left]))

(defn process-elephant-neighbor [current-node]
  (fn [[open-set goal-score came-from] next-node]
    (let [[loc ele-loc next-open-valves time-left] next-node]
      (if
       ;; reduce search space by not returning to nodes that we
       ;; already have a stand-pat score for w/same num open-valves.
       (contains? goal-score [loc ele-loc next-open-valves 0])
        [open-set goal-score came-from]
        (let [tentative-gscore (if (< time-left 0) Integer/MAX_VALUE
                                   ;; must use our current open valves for cost.
                                   (+ (goal-score current-node) (calculate-cost (nth current-node 2) 1)))
              [goal-score came-from added] (maybe-add-score next-node current-node tentative-gscore goal-score came-from)
              ;; heuristic rewards changing the valve state.
              ;; this is less efficient because we don't have
              heuristic (if (not= next-open-valves (nth current-node 2)) 0 15)] ;(- 30 (flows (first next-node)) (flows (second next-node)))]
          [(if added
             (do
              ;;  (println "adding" next-node "to open set with score" (+ tentative-gscore heuristic))
               (assoc open-set next-node (+ tentative-gscore heuristic)))
             (do
              ;;  (println "not adding" next-node "score not good enough" tentative-gscore "vs" (get goal-score next-node Integer/MAX_VALUE))
               open-set))
           goal-score
           came-from])))))

(defn search-with-elephant [elephant-move? start-time]
  (let [start ["AA" "AA" 0 start-time]
        worst-score (calculate-cost 0 start-time)]
    (loop [[open-set goal-score came-from] [(priority-map start 0) (assoc {} start 0) {}]
           nodes 0
           min-so-far Integer/MAX_VALUE]
      (if
       (empty? open-set) [goal-score came-from nodes (- worst-score min-so-far)]
       (let [[current-node] (peek open-set)
             [location ele-location open-valves time-left] current-node
             open-set (pop open-set)
            ;; we need to process both my action and the elephant's action
            ;; IF I open a valve, we move on to the elephant.
            ;; if there's no valve worth opening we generate a list of next
            ;; positions and need to glom the elephants on.
            ;; so it's like, my-next and elephant-next, these are lists, then
            ;; there's some method of merging the lists.
            ;; oh yes, standing pat should also be added.
             stand-pat-node [location ele-location open-valves 0]
             stand-pat-score (+ (goal-score current-node) (calculate-cost open-valves time-left))
            ;; fun, the logic is basically the same.
             [goal-score came-from] (maybe-add-score stand-pat-node current-node stand-pat-score goal-score came-from)
             min-so-far (if (< (get goal-score stand-pat-node) min-so-far)
                          (do (printf "new min %d (scanned %d nodes)\n" (- worst-score stand-pat-score) nodes)
                              (flush)
                              (get goal-score stand-pat-node))
                          min-so-far)]

        ;;  (println "my neighbors" (get-neighbor-node-list [location open-valves time-left]))
        ;;  (println "elephant neighbors" (get-neighbor-node-list [ele-location open-valves time-left]))
         (recur
          (reduce
           (process-elephant-neighbor current-node)
           [open-set goal-score came-from]
           (combo-lists
            (get-neighbor-node-list [location open-valves time-left])
            (if elephant-move?
              (get-neighbor-node-list [ele-location open-valves time-left])
              [["AA" open-valves (dec time-left)]])))
            ;;  ))
          (inc nodes)
          min-so-far))))))

;; so this is implemented but it does not work.
;; I suppose an easy way to check this is if the elephant doesn't move and we
;; start at 30 minutes left.

(println
 "part 1 answer (should be 1651)"
 (let [[goal-score came-from nodes result] (search-with-elephant false 30)
       _ (println "A* search scanned" nodes "nodes")]
   result))

(println
 "part 2 answer (should be 1707)"
 (let [[goal-score came-from nodes result] (search-with-elephant true 26)
       _ (println "A* search scanned" nodes "nodes")]
   result))
