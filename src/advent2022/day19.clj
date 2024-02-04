(ns advent2022.day19
  (:require [advent2022.utils :as utils]
            [clojure.data.priority-map :refer [priority-map]]))

(def blueprint-re #"Blueprint \d+:\s+Each ore robot costs (\d+) ore.\s+Each clay robot costs (\d)+ ore.\s+Each obsidian robot costs (\d+) ore and (\d+) clay.\s+Each geode robot costs (\d+) ore and (\d+) obsidian.")

(def test-str "
Blueprint 1:
  Each ore robot costs 4 ore.
  Each clay robot costs 2 ore.
  Each obsidian robot costs 3 ore and 14 clay.
  Each geode robot costs 2 ore and 7 obsidian.
")

(defn parse-blueprint [robot-string]
  (let [[_ & parts] (re-matches blueprint-re (.replaceAll robot-string "\n" ""))
        [ore-cost clay-ore-cost
         obs-ore-cost obs-clay-cost
         geode-ore-cost geode-obs-cost] (map utils/parse-int parts)]
    {:ore {:ore ore-cost}
     :clay {:ore clay-ore-cost}
     :obsidian {:ore obs-ore-cost :clay obs-clay-cost}
     :geode {:ore geode-ore-cost :obsidian geode-obs-cost}}))

(defn can-acquire? [resource robots]
  (contains? robots resource))

(defn quot-round-up [n m]
  (let [x (quot n m)
        r (mod n m)]
    (if (= r 0) x (inc x))))

(defn time-to-acquire [resource resource-amount robots]
  ;; this is blueprint agnostic, e.g. if you have 2 ore robots and want 8 ore
  ;; this is 4 seconds.
  ;; returns time plus any surplus resource
  (let [t (quot resource-amount (robots resource))
        r (mod resource-amount (robots resource))]
    (if (= r 0) [t 0] [(inc t) {resource r}])))

(time-to-acquire :ore 4 {:ore 3})
(def blueprint (parse-blueprint test-str))

(blueprint :clay)

(defn spend-resources [state m]
  (let [{:keys [resources]} state]
    (assoc state :resources
           (into {} (map (fn [[k v]] [k (- v (get m k 0))]) resources)))))

(defn can-build? [blueprint {:keys [resources]} resource]
  (every?
   (fn [[resource num]] (>= (get resources resource 0) num))
   (blueprint resource)))

(can-build? blueprint {:resources {:ore 2}} :clay)

(defn collect-resources [resources robots]
  (into {}
        (map
         (fn [[resource num]]
           [resource (+ num (get robots resource 0))])
         resources)))

;; next states will be build each robot (perhaps waiting several minutes)
;; OR stand pat until the end.

(defn transition [state t]
  (let [{:keys [resources robots]} state]
    ;; for each robot, add * t resource resources.
    (-> state
        (update :time-left #(- % t))
        (assoc :resources
               (merge-with
                +
                resources
                (into {} (map (fn [[resource num]]
                                [resource (* t num)])
                              robots)))))))

(defn stand-pat [state]
  (transition state (state :time-left)))

(defn time-to-build [blueprint state resource]
  (let [{:keys [robots resources]} state]
    ;; for each dependency, max of incoming rates.
    ;; OK, needs to include our current stockpile.
    (reduce
     max
     (map #(quot-round-up
            (- (get-in blueprint [resource %]) (get resources % 0))
            (get robots % 0))
          (keys (blueprint resource))))))

;; (use 'clojure.tools.trace)
(get {:ore 2} :ore)
(get-in blueprint [:clay :ore])

(time-to-build blueprint {:resources {:ore 2} :robots {:ore 1}} :clay)

(defn can-ever-build? [blueprint state resource]
  (let [{:keys [time-left resources robots]} state]
    (and (every? #(> (get robots % 0) 0) (keys (blueprint resource)))
         ;; I think this needs to be <, potentially needs to be even more
         ;; aggressive.  if I can build a geode robot with 1 second left, it
         ;; should be as if I can't build it.
         (< (time-to-build blueprint state resource) time-left))))

(def start-state {:time-left 24
                  :resources {:ore 0 :geode 0 :obsidian 0 :clay 0}
                  :robots {:ore 1}})
(def better-state {:time-left 24
                   :robots {:obsidian 1 :ore 1}})
(def out-of-time-state {:time-left 7
                   :robots {:obsidian 1 :ore 1}})
(can-ever-build? blueprint better-state :geode)
(can-ever-build? blueprint start-state :obsidian)
(can-ever-build? blueprint out-of-time-state :geode)
(stand-pat better-state) ;; should be 24 24
(transition better-state 0) ;; should be 24 24

;; this is off by one, the minute spend collecting the resources is not taken
;; into account.
;; transition zooms forward to the START of the time.
;; we then build the robot.
(defn build-robot [blueprint state resource]
  (let [t (time-to-build blueprint state resource)
        ;; head forward to the start of a time when we have the resources to
        ;; build the robot.
        next-state (transition state t)]
    (-> next-state
        ;; "tick" the state; we need to do this first because we don't want to
        ;; count the being-created robot for collection.
        (transition 1)
        (update-in [:robots resource] (fnil inc 0))
        (spend-resources (blueprint resource)))))

(def brb (partial build-robot blueprint))

(brb (brb start-state :clay) :clay)
(time-to-build blueprint (brb start-state :clay) :clay)

(brb (brb start-state :clay) :clay)

(let [l (list :clay :clay :clay :obsidian :clay :obsidian :geode :geode )]
  (loop [state start-state
         l l]
    (if (empty? l) (stand-pat state)
        (recur (build-robot blueprint state (first l)) (rest l)))))

(defn next-states-better [blueprint state]
  (let [{:keys [time-left resources robots]} state]
    ;; not clear if this will get many hits.
    (if (can-build? blueprint state :geode)
      ;; just build that geode robot.
      (list (build-robot blueprint state :geode))
      ;; otherwise, decide what to do next.
      (conj
       (->> '(:ore :clay :obsidian :geode)
            (filter (partial can-ever-build? blueprint state))
            (map (partial build-robot blueprint state)))
       (stand-pat state)))))

(take 8
      (iterate #(mapcat (fn [s] (next-states-better blueprint s)) %) [start-state]))


(defn heuristic [state]
  ;; what is the format of the state?  obviously, time left, amount of
  ;; materials, what robots you have.
  (let [{:keys [time-left resources robots built-robot]} state
        score 0]
    (+ score
       ;; feels more natural to list the things that we like
       ;; vs the things we don't like for the min heap.
      ;;  (if (= time-left 0) -100 0)
       (* (if (= built-robot :geode) -20 0))
       (* (if (= built-robot :obsidian) -10 0))
       (* (if (= built-robot :clay) -5 0))
       (* (if (= built-robot :ore) -2 0))
       (* (get resources :geode 0) -10)
       (* (get resources :obsidian 0) -5)
       (* (get resources :clay 0) -1)
       (* (get robots :geode 0) -20)
       (* (get robots :obsidian 0) -5)
       (* (get robots :clay 0) -3))))

;; if you have no geode robot and you build a geode robot
;; in this minute, you will collect (dec time-left) geodes.
;; if this can't beat our best score so far, no reason to
;; look at this node.


(defn max-geode-potential
  "max number of geodes we could potentially get from this state"
  [blueprint state]
  ;; we have to be careful because we might be able to build a geode robot
  ;; this turn, a geode robot next turn, etc.
  ;; we'd have to understand resource intake to be able to do the calculation
  ;; intelligently.
  ;; https://old.reddit.com/r/adventofcode/comments/zujwgo/comment/j1jobsq
  (let [{:keys [time-left resources robots]} state
        time-factor (/ (* time-left (dec time-left)) 2)
        max-ore (+ (get resources :ore 0) (* (get robots :ore 0) time-left) time-factor)
        max-obs (+ (get resources :obsidian 0) (* (get robots :obsidian 0) time-left) time-factor)
        max-geobots (max (quot max-ore ((blueprint :geode) :ore)) (quot max-obs ((blueprint :obsidian) :ore)))
        can-gather (+ (get resources :geode 0) (* (get robots :geode 0) time-left))]
    (if (>= max-geobots time-left)
      (+ can-gather time-factor)
      (+ can-gather
         (quot (* max-geobots (dec max-geobots)) 2)
         (* (- time-left max-geobots) max-geobots)))))

(defn should-cutoff [blueprint state best-so-far]
  (let [{:keys [time-left resources robots]} state]
    (or
     (= time-left 0)
     (<= (max-geode-potential blueprint state) best-so-far))))

;; a robot is useless if we already have enough resources coming in a turn to
;; build any other type of robot.
(defn robot-useless? [blueprint {:keys [time-left resources robots built-robot]}]
  (cond
    (= built-robot :geode) false
    (= time-left 1) true
    (and (= time-left 2) (not= built-robot :geode)) true
    :else (->> (keys blueprint)
               (remove #(= % built-robot))
               (map #(get (blueprint %) built-robot 0))
               (every? #(>= (get robots built-robot 0) %)))))

(defn should-explore-neighbor [blueprint neighbor]
  (let [{:keys [built-robot]} neighbor]
  ;; we don't want to build another robot if we have enough resources coming in
  ;; to build the next robot
    (if built-robot
      (not (robot-useless? blueprint neighbor))
      true)))

;; this is BFS with a heuristic
(defn best-score [blueprint]
  (let [start {:time-left 24
               :resources {:ore 0 :geode 0 :obsidian 0 :clay 0}
               :robots {:ore 1}}]
    (loop
     [[pqueue prev] [(priority-map start (heuristic start)) {}]
      best-so-far 0
      nodes 0]
      (cond (empty? pqueue) [best-so-far nodes prev]
       ;; we actually want the highest geode score here, but w/e at this point.
            (> nodes 1000000000) "cutoff" ;; just cutoff
            :else
            (let [[state] (peek pqueue)
                  {:keys [time-left resources robots]} state
                  next-best-so-far (if (= time-left 0)
                                     (max best-so-far (resources :geode))
                                     best-so-far)
                  _ (if (not= next-best-so-far best-so-far) (println "new best score" next-best-so-far state))
                  best-so-far next-best-so-far
                  pqueue (pop pqueue)]
              (cond (should-cutoff blueprint state best-so-far)
                    (recur [pqueue prev] best-so-far (inc nodes))
                    :else
                    (recur
                     (reduce
                      (fn [[pqueue prev] neighbor]
                        (if (should-explore-neighbor blueprint neighbor)
                 ;; use built-robot to cutoff things we don't want to explore
                          [(assoc pqueue neighbor (heuristic neighbor))
                           (assoc prev neighbor state)]
                          [pqueue prev])
               ;; 2) cutoff if there's no way to beat max-score-so-far in the
               ;; remaining time
                        )
                      [pqueue prev]
                      (next-states-better blueprint state))
                     best-so-far
                     (inc nodes))))))))

(def blueprint2 "Blueprint 2:
  Each ore robot costs 2 ore.
  Each clay robot costs 3 ore.
  Each obsidian robot costs 3 ore and 8 clay.
  Each geode robot costs 3 ore and 12 obsidian.")

(defn search [blueprint]
  (let [[score nodes prev] (best-score blueprint)
        best-node (first (filter #(= ((% :resources) :geode) score) (keys prev)))]
    (println "best score for blueprint" score "in" nodes "nodes")
    (loop [node best-node]
      (if (nil? node)
        (println "done")
        (do
          (println node)
          (recur (prev node)))))
    (println best-node)))

(search blueprint)
(search (parse-blueprint blueprint2))
