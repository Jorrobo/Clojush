;; clojush.clj
;;
;; This file implements a version of the Push programming language and the PushGP genetic
;; programming system in the Clojure programming language. See the accompanying README
;; file for usage instructions and other notes.
;;
;; Copyright (c) 2010 Lee Spector (lspector@hampshire.edu)
;;
;; This program is free software: you can redistribute it and/or modify it under
;; the terms of version 3 of the GNU General Public License as published by the
;; Free Software Foundation, available from http://www.gnu.org/licenses/gpl.txt.
;;
;; This program is distributed in the hope that it will be useful, but WITHOUT ANY
;; WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
;; PARTICULAR PURPOSE. See the GNU General Public License (http://www.gnu.org/licenses/)
;; for more details.

;;;;;
;; namespace declaration and access to needed libraries
(ns clojush.clojush
  (:gen-class)
  (:require
    [clojure.zip :as zip]
    [clojure.math.numeric-tower :as math]
    [clojure.string :as string]
    [local-file])
  (:use
    [clojush.globals]
    [clojush.random]
    [clojush.util]))

(import java.lang.Math)

;; backtrace abbreviation, to ease debugging
(defn bt []
  (.printStackTrace *e))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; states, stacks, and instructions

;; 20101017 
;; record-based states, should be faster but aren't measurably so (see measurements)
;; reverting to structs for greater flexibility in use of state as map

;; useful for define-push-state-record-type
;(defn keyword->symbol [kwd]
;  "Returns the symbol obtained by removing the : from a keyword."
;  (read-string (name kwd)))
;
;(defmacro define-push-state-record-type []
;  "Defines the pushstate record type. The odd trick with read-string was a hack to 
;avoid namespace qualification on the pushstate symbol."
;  `(defrecord ~(read-string "pushstate") [~@(map keyword->symbol push-types)]))
;
;(define-push-state-record-type)
;
;(defmacro make-push-state
;  "Returns an empty push state."
;  []
;  `(pushstate. ~@(map (fn [_] nil) push-types)))

;1:3 clojush=> (time (stress-test 100000))
;:no-errors-found-in-stress-test
;"Elapsed time: 57673.781 msecs"
; more runs:
;"Elapsed time: 57682.971 msecs"
;"Elapsed time: 55129.614 msecs"

;; struct-based states follow

(defmacro define-push-state-structure []
  `(defstruct push-state ~@push-types))

(define-push-state-structure)

(defn make-push-state
  "Returns an empty push state."
  []
  (struct-map push-state))

;; corresponding results for struct-based states
;1:3 clojush=> (time (stress-test 100000))
;:no-errors-found-in-stress-test
;"Elapsed time: 59194.123 msecs"
; more runs:
;"Elapsed time: 52389.016 msecs"
;"Elapsed time: 54706.127 msecs"

(def registered-instructions (atom #{}))

(defn register-instruction 
  "Add the provided name to the global list of registered instructions."
  [name]
  (swap! registered-instructions conj name))

(def instruction-table (atom (hash-map)))

(defmacro define-registered
  [instruction definition]
  `(do (register-instruction '~instruction)
       (swap! instruction-table assoc '~instruction ~definition)))

(defn state-pretty-print
  [state]
  (doseq [t push-types]
    (printf "%s = " t)
    (println (t state))
    (flush)))

(defn push-item
  "Returns a copy of the state with the value pushed on the named stack. This is a utility,
   not for use in Push programs."
  [value type state]
  (assoc state type (cons value (type state))))

(defn top-item
  "Returns the top item of the type stack in state. Returns :no-stack-item if called on 
   an empty stack. This is a utility, not for use as an instruction in Push programs."
  [type state]
  (let [stack (type state)]
    (if (empty? stack)
      :no-stack-item
      (first stack))))

(defn stack-ref
  "Returns the indicated item of the type stack in state. Returns :no-stack-item if called 
   on an empty stack. This is a utility, not for use as an instruction in Push programs.
   NOT SAFE for invalid positions."
  [type position state]
  (let [stack (type state)]
    (if (empty? stack)
      :no-stack-item
      (nth stack position))))

(defn pop-item
  "Returns a copy of the state with the specified stack popped. This is a utility,
   not for use as an instruction in Push programs."
  [type state]
  (assoc state type (rest (type state))))

(defn registered-for-type
  "Returns a list of all registered instructions with the given type name as a prefix."
  [type & {:keys [include-randoms] :or {include-randoms true}}]
  (let [for-type (filter #(.startsWith (name %) (name type)) @registered-instructions)]
    (if include-randoms
      for-type
      (filter #(not (.endsWith (name %) "_rand")) for-type))))

(defn registered-nonrandom
  "Returns a list of all registered instructions aside from random instructions."
  []
  (filter #(not (.endsWith (name %) "_rand")) @registered-instructions))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ACTUAL INSTRUCTIONS

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; instructions for all types (except auxiliary and tag)

(defn popper 
  "Returns a function that takes a state and pops the appropriate stack of the state."
  [type]
  (fn [state] (pop-item type state)))

(define-registered exec_pop (popper :exec))
(define-registered integer_pop (popper :integer))
(define-registered float_pop (popper :float))
(define-registered code_pop (popper :code))
(define-registered boolean_pop (popper :boolean))
(define-registered zip_pop (popper :zip))
(define-registered string_pop (popper :string))

(defn duper 
  "Returns a function that takes a state and duplicates the top item of the appropriate 
   stack of the state."
  [type]
  (fn [state]
    (if (empty? (type state))
      state
      (push-item (top-item type state) type state))))

(define-registered exec_dup (duper :exec))
(define-registered integer_dup (duper :integer))
(define-registered float_dup (duper :float))
(define-registered code_dup (duper :code))
(define-registered boolean_dup (duper :boolean))
(define-registered zip_dup (duper :zip))
(define-registered string_dup (duper :string))

(defn swapper 
  "Returns a function that takes a state and swaps the top 2 items of the appropriate 
   stack of the state."
  [type]
  (fn [state]
    (if (not (empty? (rest (type state))))
      (let [first-item (stack-ref type 0 state)
            second-item (stack-ref type 1 state)]
        (->> (pop-item type state) 
             (pop-item type)
             (push-item first-item type)
             (push-item second-item type)))
      state)))

(define-registered exec_swap (swapper :exec))
(define-registered integer_swap (swapper :integer))
(define-registered float_swap (swapper :float))
(define-registered code_swap (swapper :code))
(define-registered boolean_swap (swapper :boolean))
(define-registered zip_swap (swapper :zip))
(define-registered string_swap (swapper :string))

(defn rotter 
  "Returns a function that takes a state and rotates the top 3 items of the appropriate 
   stack of the state."
  [type]
  (fn [state]
    (if (not (empty? (rest (rest (type state)))))
      (let [first (stack-ref type 0 state)
            second (stack-ref type 1 state)
            third (stack-ref type 2 state)]
        (->> (pop-item type state)
             (pop-item type)
             (pop-item type)
             (push-item second type)
             (push-item first type)
             (push-item third type)))
      state)))

(define-registered exec_rot (rotter :exec))
(define-registered integer_rot (rotter :integer))
(define-registered float_rot (rotter :float))
(define-registered code_rot (rotter :code))
(define-registered boolean_rot (rotter :boolean))
(define-registered zip_rot (rotter :zip))
(define-registered string_rot (rotter :string))

(defn flusher
  "Returns a function that empties the stack of the given state."
  [type]
  (fn [state]
    (assoc state type '())))

(define-registered exec_flush (flusher :exec))
(define-registered integer_flush (flusher :integer))
(define-registered float_flush (flusher :float))
(define-registered code_flush (flusher :code))
(define-registered boolean_flush (flusher :boolean))
(define-registered zip_flush (flusher :zip))
(define-registered string_flush (flusher :string))


(defn eqer 
  "Returns a function that compares the top two items of the appropriate stack of 
   the given state."
  [type]
  (fn [state]
    (if (not (empty? (rest (type state))))
      (let [first (stack-ref type 0 state)
            second (stack-ref type 1 state)]
        (->> (pop-item type state)
             (pop-item type)
             (push-item (= first second) :boolean)))
      state)))

(define-registered exec_eq (eqer :exec))
(define-registered integer_eq (eqer :integer))
(define-registered float_eq (eqer :float))
(define-registered code_eq (eqer :code))
(define-registered boolean_eq (eqer :boolean))
(define-registered zip_eq (eqer :zip))
(define-registered string_eq (eqer :string))

(defn stackdepther
  "Returns a function that pushes the depth of the appropriate stack of the 
   given state."
  [type]
  (fn [state]
    (push-item (count (type state)) :integer state)))

(define-registered exec_stackdepth (stackdepther :exec))
(define-registered integer_stackdepth (stackdepther :integer))
(define-registered float_stackdepth (stackdepther :float))
(define-registered code_stackdepth (stackdepther :code))
(define-registered boolean_stackdepth (stackdepther :boolean))
(define-registered zip_stackdepth (stackdepther :zip))
(define-registered string_stackdepth (stackdepther :string))

(defn yanker
  "Returns a function that yanks an item from deep in the specified stack,
   using the top integer to indicate how deep."
  [type]
  (fn [state]
    (if (or (and (= type :integer)
                 (not (empty? (rest (type state)))))
            (and (not (= type :integer))
                 (not (empty? (type state)))
                 (not (empty? (:integer state)))))
      (let [raw-index (stack-ref :integer 0 state)
            with-index-popped (pop-item :integer state)
            actual-index (max 0 (min raw-index (- (count (type with-index-popped)) 1)))
            item (stack-ref type actual-index with-index-popped)
            with-item-pulled (assoc with-index-popped 
                                    type 
                                    (let [stk (type with-index-popped)]
                                      (concat (take actual-index stk)
                                              (rest (drop actual-index stk)))))]
        (push-item item type with-item-pulled))
      state)))

(define-registered exec_yank (yanker :exec))
(define-registered integer_yank (yanker :integer))
(define-registered float_yank (yanker :float))
(define-registered code_yank (yanker :code))
(define-registered boolean_yank (yanker :boolean))
(define-registered zip_yank (yanker :zip))
(define-registered string_yank (yanker :string))

(defn yankduper
  "Returns a function that yanks a copy of an item from deep in the specified stack,
   using the top integer to indicate how deep."
  [type]
  (fn [state]
    (if (or (and (= type :integer)
                 (not (empty? (rest (type state)))))
            (and (not (= type :integer))
                 (not (empty? (type state)))
                 (not (empty? (:integer state)))))
      (let [raw-index (stack-ref :integer 0 state)
            with-index-popped (pop-item :integer state)
            actual-index (max 0 (min raw-index (- (count (type with-index-popped)) 1)))
            item (stack-ref type actual-index with-index-popped)]
        (push-item item type with-index-popped))
      state)))

(define-registered exec_yankdup (yankduper :exec))
(define-registered integer_yankdup (yankduper :integer))
(define-registered float_yankdup (yankduper :float))
(define-registered code_yankdup (yankduper :code))
(define-registered boolean_yankdup (yankduper :boolean))
(define-registered zip_yankdup (yankduper :zip))
(define-registered string_yankdup (yankduper :string))

(defn shover
  "Returns a function that shoves an item deep in the specified stack, using the top
   integer to indicate how deep."
  [type]
  (fn [state]
    (if (or (and (= type :integer)
                 (not (empty? (rest (type state)))))
            (and (not (= type :integer))
                 (not (empty? (type state)))
                 (not (empty? (:integer state)))))
      (let [raw-index (stack-ref :integer 0 state)
            with-index-popped (pop-item :integer state)
            item (top-item type with-index-popped)
            with-args-popped (pop-item type with-index-popped)
            actual-index (max 0 (min raw-index (count (type with-args-popped))))]
        (assoc with-args-popped type (let [stk (type with-args-popped)]
                                       (concat (take actual-index stk)
                                               (list item)
                                               (drop actual-index stk)))))
      state)))

(define-registered exec_shove (shover :exec))
(define-registered integer_shove (shover :integer))
(define-registered float_shove (shover :float))
(define-registered code_shove (shover :code))
(define-registered boolean_shove (shover :boolean))
(define-registered zip_shove (shover :zip))
(define-registered string_shove (shover :string))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; rand instructions

(define-registered 
  boolean_rand
  (fn [state]
    (push-item (lrand-nth [true false]) :boolean state)))

(define-registered 
  integer_rand
  (fn [state]
    (push-item (+' (lrand-int (+ 1 (- max-random-integer min-random-integer)))
                   min-random-integer)
               :integer
               state)))

(define-registered 
  float_rand
  (fn [state]
    (push-item (+' (lrand (- max-random-float min-random-float))
                   min-random-float)
               :float
               state)))

(define-registered 
  code_rand
  (fn [state]
    (if (not (empty? (:integer state)))
      (push-item (random-code (math/abs (mod (stack-ref :integer 0 state) 
                                             max-points-in-random-expressions)) 
                              @global-atom-generators)
                 :code
                 (pop-item :integer state))
      state)))

(define-registered 
  string_rand
  (fn [state]
    (push-item 
      (apply str (repeatedly 
                   (+' min-random-string-length
                       (lrand-int (- max-random-string-length 
                                     min-random-string-length)))
                   #(rand-nth 
                      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz1234567890")))
      :string
      state)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; instructions for numbers

(defn adder
  "Returns a function that pushes the sum of the top two items."
  [type]
  (fn [state]
    (if (not (empty? (rest (type state))))
      (let [first (stack-ref type 0 state)
            second (stack-ref type 1 state)]
        (->> (pop-item type state)
             (pop-item type)
             (push-item (keep-number-reasonable (+' first second)) type)))
      state)))

(define-registered integer_add (adder :integer))
(define-registered float_add (adder :float))

(defn subtracter
  "Returns a function that pushes the difference of the top two items."
  [type]
  (fn [state]
    (if (not (empty? (rest (type state))))
      (let [first (stack-ref type 0 state)
            second (stack-ref type 1 state)]
        (->> (pop-item type state)
             (pop-item type)
             (push-item (keep-number-reasonable (- second first)) type)))
      state)))

(define-registered integer_sub (subtracter :integer))
(define-registered float_sub (subtracter :float))

(defn multiplier
  "Returns a function that pushes the product of the top two items."
  [type]
  (fn [state]
    (if (not (empty? (rest (type state))))
      (let [first (stack-ref type 0 state)
            second (stack-ref type 1 state)]
        (->> (pop-item type state)
             (pop-item type)
             (push-item (keep-number-reasonable (*' second first)) type)))
      state)))

(define-registered integer_mult (multiplier :integer))
(define-registered float_mult (multiplier :float))

(defn divider
  "Returns a function that pushes the quotient of the top two items. Does
   nothing if the denominator would be zero."
  [type]
  (fn [state]
    (if (and (not (empty? (rest (type state))))
             (not (zero? (stack-ref type 0 state))))
      (let [first (stack-ref type 0 state)
            second (stack-ref type 1 state)]
        (->> (pop-item type state)
             (pop-item type)
             (push-item (if (= type :integer)
                          (truncate (keep-number-reasonable (/ second first)))
                          (keep-number-reasonable (/ second first)))
                        type)))
      state)))

(define-registered integer_div (divider :integer))
(define-registered float_div (divider :float))

(defn modder
  "Returns a function that pushes the modulus of the top two items. Does
   nothing if the denominator would be zero."
  [type]
  (fn [state]
    (if (and (not (empty? (rest (type state))))
             (not (zero? (stack-ref type 0 state))))
      (let [frst (stack-ref type 0 state)
            scnd (stack-ref type 1 state)]
        (->> (pop-item type state)
             (pop-item type)
             (push-item (if (= type :integer)
                          (truncate (keep-number-reasonable (mod scnd frst)))
                          (keep-number-reasonable (mod scnd frst)))
                        type)))
      state)))

(define-registered integer_mod (modder :integer))
(define-registered float_mod (modder :float))

(defn lessthaner
  "Returns a function that pushes the result of < of the top two items onto the 
   boolean stack."
  [type]
  (fn [state]
    (if (not (empty? (rest (type state))))
      (let [first (stack-ref type 0 state)
            second (stack-ref type 1 state)]
        (->> (pop-item type state)
             (pop-item type)
             (push-item (< second first) :boolean)))
      state)))

(define-registered integer_lt (lessthaner :integer))
(define-registered float_lt (lessthaner :float))

(defn greaterthaner
  "Returns a function that pushes the result of > of the top two items onto the 
   boolean stack."
  [type]
  (fn [state]
    (if (not (empty? (rest (type state))))
      (let [first (stack-ref type 0 state)
            second (stack-ref type 1 state)]
        (->> (pop-item type state)
             (pop-item type)
             (push-item (> second first) :boolean)))
      state)))

(define-registered integer_gt (greaterthaner :integer))
(define-registered float_gt (greaterthaner :float))

(define-registered 
  integer_fromboolean
  (fn [state]
    (if (not (empty? (:boolean state)))
      (let [item (stack-ref :boolean 0 state)]
        (->> (pop-item :boolean state)
             (push-item (if item 1 0) :integer)))
      state)))

(define-registered 
  float_fromboolean
  (fn [state]
    (if (not (empty? (:boolean state)))
      (let [item (stack-ref :boolean 0 state)]
        (->> (pop-item :boolean state)
             (push-item (if item 1.0 0.0) :float)))
      state)))

(define-registered 
  integer_fromfloat
  (fn [state]
    (if (not (empty? (:float state)))
      (let [item (stack-ref :float 0 state)]
        (->> (pop-item :float state)
             (push-item (truncate item) :integer)))
      state)))

(define-registered 
  float_frominteger
  (fn [state]
    (if (not (empty? (:integer state)))
      (let [item (stack-ref :integer 0 state)]
        (->> (pop-item :integer state)
             (push-item (*' 1.0 item) :float)))
      state)))

(defn minner
  "Returns a function that pushes the minimum of the top two items."
  [type]
  (fn [state]
    (if (not (empty? (rest (type state))))
      (let [first (stack-ref type 0 state)
            second (stack-ref type 1 state)]
        (->> (pop-item type state)
             (pop-item type)
             (push-item (min second first) type)))
      state)))

(define-registered integer_min (minner :integer))
(define-registered float_min (minner :float))

(defn maxer
  "Returns a function that pushes the maximum of the top two items."
  [type]
  (fn [state]
    (if (not (empty? (rest (type state))))
      (let [first (stack-ref type 0 state)
            second (stack-ref type 1 state)]
        (->> (pop-item type state)
             (pop-item type)
             (push-item (max second first) type)))
      state)))

(define-registered integer_max (maxer :integer))
(define-registered float_max (maxer :float))

(define-registered 
  float_sin
  (fn [state]
    (if (not (empty? (:float state)))
      (push-item (keep-number-reasonable (Math/sin (stack-ref :float 0 state)))
                 :float
                 (pop-item :float state))
      state)))

(define-registered 
  float_cos
  (fn [state]
    (if (not (empty? (:float state)))
      (push-item (keep-number-reasonable (Math/cos (stack-ref :float 0 state)))
                 :float
                 (pop-item :float state))
      state)))

(define-registered 
  float_tan
  (fn [state]
    (if (not (empty? (:float state)))
      (push-item (keep-number-reasonable (Math/tan (stack-ref :float 0 state)))
                 :float
                 (pop-item :float state))
      state)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; instructions for Booleans

(define-registered 
  boolean_and
  (fn [state]
    (if (not (empty? (rest (:boolean state))))
      (push-item (and (stack-ref :boolean 0 state)
                      (stack-ref :boolean 1 state))
                 :boolean
                 (pop-item :boolean (pop-item :boolean state)))
      state)))

(define-registered 
  boolean_or
  (fn [state]
    (if (not (empty? (rest (:boolean state))))
      (push-item (or (stack-ref :boolean 0 state)
                     (stack-ref :boolean 1 state))
                 :boolean
                 (pop-item :boolean (pop-item :boolean state)))
      state)))

(define-registered boolean_not
                   (fn 
                     [state]
                     (if (not (empty? (:boolean state)))
                       (push-item (not (stack-ref :boolean 0 state))
                                  :boolean
                                  (pop-item :boolean state))
                       state)))

(define-registered 
  boolean_frominteger
  (fn [state]
    (if (not (empty? (:integer state)))
      (push-item (not (zero? (stack-ref :integer 0 state)))
                 :boolean
                 (pop-item :integer state))
      state)))

(define-registered 
  boolean_fromfloat
  (fn [state]
    (if (not (empty? (:float state)))
      (push-item (not (zero? (stack-ref :float 0 state)))
                 :boolean
                 (pop-item :float state))
      state)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; instructions for strings

(define-registered 
  string_concat
  (fn [state]
    (if (not (empty? (rest (:string state))))
      (if (>= max-string-length (+ (count (stack-ref :string 1 state))
                                   (count (stack-ref :string 0 state))))
        (push-item (str (stack-ref :string 1 state)
                        (stack-ref :string 0 state))
                   :string
                   (pop-item :string (pop-item :string state)))
        state)
      state)))

(define-registered 
  string_take
  (fn [state]
    (if (and (not (empty? (:string state)))
             (not (empty? (:integer state))))
      (push-item (apply str (take (stack-ref :integer 0 state)
                                  (stack-ref :string 0 state)))
                 :string
                 (pop-item :string (pop-item :integer state)))
      state)))

(define-registered 
  string_length
  (fn [state]
    (if (not (empty? (:string state)))
      (push-item (count (stack-ref :string 0 state))
                 :integer
                 (pop-item :string state))
      state)))

(define-registered
  string_atoi
  (fn [state]
    (if (not (empty? (:string state)))
      (try (pop-item :string
                     (push-item (Integer/parseInt (top-item :string state))
                                :integer state))
           (catch Exception e state))
      state)))

(define-registered
  string_reverse
  (fn [state]
    (if (empty? (:string state))
      state
      (let [top-string (top-item :string state)]
        (push-item (apply str (reverse top-string))
                   :string
                   (pop-item :string state))))))

(define-registered
  string_parse_to_chars
  (fn [state]
    (if (empty? (:string state))
      state
      (loop [char-list (reverse (top-item :string state))
             loop-state (pop-item :string state)]
        (if (empty? char-list)
          loop-state
          (recur (rest char-list)
                 (push-item (str (first char-list)) :string loop-state)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; code and exec instructions

(define-registered 
  code_append
  (fn [state]
    (if (not (empty? (rest (:code state))))
      (let [new-item (concat (ensure-list (stack-ref :code 0 state))
                             (ensure-list (stack-ref :code 1 state)))]
        (if (<= (count-points new-item) @global-max-points-in-program)
          (push-item new-item
                     :code
                     (pop-item :code (pop-item :code state)))
          state))
      state)))

(define-registered 
  code_atom
  (fn [state]
    (if (not (empty? (:code state)))
      (push-item (not (seq? (stack-ref :code 0 state)))
                 :boolean
                 (pop-item :code state))
      state)))

(define-registered 
  code_car
  (fn [state]
    (if (and (not (empty? (:code state)))
             (> (count (ensure-list (stack-ref :code 0 state))) 0))
      (push-item (first (ensure-list (stack-ref :code 0 state)))
                 :code
                 (pop-item :code state))
      state)))

(define-registered 
  code_cdr
  (fn [state]
    (if (not (empty? (:code state)))
      (push-item (rest (ensure-list (stack-ref :code 0 state)))
                 :code
                 (pop-item :code state))
      state)))

(define-registered 
  code_cons
  (fn [state]
    (if (not (empty? (rest (:code state))))
      (let [new-item (cons (stack-ref :code 1 state)
                           (ensure-list (stack-ref :code 0 state)))]
        (if (<= (count-points new-item) @global-max-points-in-program)
          (push-item new-item
                     :code
                     (pop-item :code (pop-item :code state)))
          state))
      state)))

(define-registered 
  code_do
  (fn [state]
    (if (not (empty? (:code state)))
      (push-item (stack-ref :code 0 state) 
                 :exec
                 (push-item 'code_pop :exec state))
      state)))

(define-registered 
  code_do*
  (fn [state]
    (if (not (empty? (:code state)))
      (push-item (stack-ref :code 0 state)
                 :exec
                 (pop-item :code state))
      state)))

(define-registered 
  code_do*range
  (fn [state]
    (if (not (or (empty? (:code state))
                 (empty? (rest (:integer state)))))
      (let [to-do (first (:code state))
            current-index (first (rest (:integer state)))
            destination-index (first (:integer state))
            args-popped (pop-item :integer
                                  (pop-item :integer
                                            (pop-item :code state)))
            increment (cond (< current-index destination-index) 1
                            (> current-index destination-index) -1
                            true 0)
            continuation (if (zero? increment)
                           args-popped
                           (push-item (list (+' current-index increment)
                                            destination-index
                                            'code_quote
                                            to-do
                                            'code_do*range)
                                      :exec
                                      args-popped))]
        (push-item to-do :exec (push-item current-index :integer continuation)))
      state)))

(define-registered 
  exec_do*range 
  (fn [state] ; Differs from code.do*range only in the source of the code and the recursive call.
    (if (not (or (empty? (:exec state))
                 (empty? (rest (:integer state)))))
      (let [to-do (first (:exec state))
            current-index (first (rest (:integer state)))
            destination-index (first (:integer state))
            args-popped (pop-item :integer
                                  (pop-item :integer
                                            (pop-item :exec state)))
            increment (cond (< current-index destination-index) 1
                            (> current-index destination-index) -1
                            true 0)
            continuation (if (zero? increment)
                           args-popped
                           (push-item (list (+' current-index increment)
                                            destination-index
                                            'exec_do*range
                                            to-do)
                                      :exec
                                      args-popped))]
        (push-item to-do :exec (push-item current-index :integer continuation)))
      state)))

(define-registered 
  code_do*count
  (fn [state]
    (if (not (or (empty? (:integer state))
                 (< (first (:integer state)) 1)
                 (empty? (:code state))))
      (push-item (list 0 (dec (first (:integer state))) 
                       'code_quote (first (:code state)) 'code_do*range)
                 :exec
                 (pop-item :integer (pop-item :code state)))
      state)))

(define-registered 
  exec_do*count
  ;; differs from code.do*count only in the source of the code and the recursive call    
  (fn [state] 
    (if (not (or (empty? (:integer state))
                 (< (first (:integer state)) 1)
                 (empty? (:exec state))))
      (push-item (list 0 (dec (first (:integer state))) 'exec_do*range (first (:exec state)))
                 :exec
                 (pop-item :integer (pop-item :exec state)))
      state)))

(define-registered 
  code_do*times
  (fn [state]
    (if (not (or (empty? (:integer state))
                 (< (first (:integer state)) 1)
                 (empty? (:code state))))
      (push-item (list 0 (dec (first (:integer state))) 'code_quote 
                       (cons 'integer_pop 
                             (ensure-list (first (:code state)))) 'code_do*range)
                 :exec
                 (pop-item :integer (pop-item :code state)))
      state)))

(define-registered 
  exec_do*times
  ;; differs from code.do*times only in the source of the code and the recursive call
  (fn [state]
    (if (not (or (empty? (:integer state))
                 (< (first (:integer state)) 1)
                 (empty? (:exec state))))
      (push-item (list 0 (dec (first (:integer state))) 'exec_do*range
                       (cons 'integer_pop (ensure-list (first (:exec state)))))
                 :exec
                 (pop-item :integer (pop-item :exec state)))
      state)))

(define-registered 
  code_map
  (fn [state]
    (if (not (or (empty? (:code state))
                 (empty? (:exec state))))
      (push-item (concat
                   (doall (for [item (ensure-list (first (:code state)))]
                            (list 'code_quote
                                  item
                                  (first (:exec state)))))
                   '(code_wrap)
                   (doall (for [item (rest (ensure-list (first (:code state))))]
                            'code_cons)))
                 :exec
                 (pop-item :code (pop-item :exec state)))
      state)))

(defn codemaker
  "Returns a function that pops the stack of the given type and pushes the result on 
   the code stack."
  [type]
  (fn [state]
    (if (not (empty? (type state)))
      (push-item (first (type state))
                 :code
                 (pop-item type state))
      state)))

(define-registered code_fromboolean (codemaker :boolean))
(define-registered code_fromfloat (codemaker :float))
(define-registered code_frominteger (codemaker :integer))
(define-registered code_quote (codemaker :exec))

(define-registered 
  code_if
  (fn [state]
    (if (not (or (empty? (:boolean state))
                 (empty? (rest (:code state)))))
      (push-item (if (first (:boolean state))
                   (first (rest (:code state)))
                   (first (:code state)))
                 :exec
                 (pop-item :boolean (pop-item :code (pop-item :code state))))
      state)))

(define-registered 
  exec_if
  ;; differs from code.if in the source of the code and in the order of the if/then parts
  (fn [state]
    (if (not (or (empty? (:boolean state))
                 (empty? (rest (:exec state)))))
      (push-item (if (first (:boolean state))
                   (first (:exec state))
                   (first (rest (:exec state))))
                 :exec
                 (pop-item :boolean (pop-item :exec (pop-item :exec state))))
      state)))

(define-registered 
  exec_when
  (fn [state]
    (if (not (or (empty? (:boolean state))
                 (empty? (:exec state))))
      (if (first (:boolean state))
        (pop-item :boolean state)
        (pop-item :boolean (pop-item :exec state)))
      state)))

(define-registered 
  code_length
  (fn [state]
    (if (not (empty? (:code state)))
      (push-item (count (ensure-list (first (:code state))))
                 :integer
                 (pop-item :code state))
      state)))

(define-registered 
  code_list
  (fn [state]
    (if (not (empty? (rest (:code state))))
      (let [new-item (list (first (rest (:code state)))
                           (first (:code state)))]
        (if (<= (count-points new-item) @global-max-points-in-program)
          (push-item new-item
                     :code
                     (pop-item :code (pop-item :code state)))
          state))
      state)))

(define-registered 
  code_wrap
  (fn [state]
    (if (not (empty? (:code state)))
      (let [new-item (list (first (:code state)))]
        (if (<= (count-points new-item) @global-max-points-in-program)
          (push-item new-item
                     :code
                     (pop-item :code state))
          state))
      state)))

(define-registered 
  code_member
  (fn [state]
    (if (not (empty? (rest (:code state))))
      (push-item (not (not (some #{(first (rest (:code state)))} 
                                 (ensure-list (first (:code state))))))
                 :boolean
                 (pop-item :code (pop-item :code state)))
      state)))

(define-registered exec_noop (fn [state] state))
(define-registered code_noop (fn [state] state))

(define-registered 
  code_nth
  (fn [state]
    (if (not (or (empty? (:integer state))
                 (empty? (:code state))
                 (empty? (ensure-list (first (:code state))))))
      (push-item (nth (ensure-list (first (:code state)))
                      (mod (math/abs (first (:integer state)))
                           (count (ensure-list (first (:code state))))))
                 :code
                 (pop-item :integer (pop-item :code state)))
      state)))

(define-registered 
  code_nthcdr
  (fn [state]
    (if (not (or (empty? (:integer state))
                 (empty? (:code state))
                 (empty? (ensure-list (first (:code state))))))
      (push-item (drop (mod (math/abs (first (:integer state))) 
                            (count (ensure-list (first (:code state)))))
                       (ensure-list (first (:code state))))
                 :code
                 (pop-item :integer (pop-item :code state)))
      state)))

(define-registered 
  code_null
  (fn [state]
    (if (not (empty? (:code state)))
      (push-item (let [item (first (:code state))]
                   (not (not (and (seq? item) (empty? item)))))
                 :boolean
                 (pop-item :code state))
      state)))

(define-registered 
  code_size
  (fn [state]
    (if (not (empty? (:code state)))
      (push-item (count-points (first (:code state)))
                 :integer
                 (pop-item :code state))
      state))) 

(define-registered 
  code_extract
  (fn [state]
    (if (not (or (empty? (:code state))
                 (empty? (:integer state))))
      (push-item (code-at-point (first (:code state))
                                (first (:integer state)))
                 :code
                 (pop-item :code (pop-item :integer state)))
      state)))

(define-registered 
  code_insert
  (fn [state]
    (if (not (or (empty? (rest (:code state)))
                 (empty? (:integer state))))
      (let [new-item (insert-code-at-point (first (:code state))
                                           (first (:integer state))
                                           (second (:code state)))]
        (if (<= (count-points new-item) @global-max-points-in-program)
          (push-item new-item
                     :code
                     (pop-item :code (pop-item :code (pop-item :integer state))))
          state))
      state)))

(define-registered 
  code_subst
  (fn [state]
    (if (not (empty? (rest (rest (:code state)))))
      (let [new-item (subst (stack-ref :code 2 state)
                            (stack-ref :code 1 state)
                            (stack-ref :code 0 state))]
        (if (<= (count-points new-item) @global-max-points-in-program)
          (push-item new-item
                     :code
                     (pop-item :code (pop-item :code (pop-item :code state))))
          state))
      state)))

(define-registered 
  code_contains
  (fn [state]
    (if (not (empty? (rest (:code state))))
      (push-item (contains-subtree (stack-ref :code 1 state)
                                   (stack-ref :code 0 state))
                 :boolean
                 (pop-item :code (pop-item :code state)))
      state)))

(define-registered 
  code_container
  (fn [state]
    (if (not (empty? (rest (:code state))))
      (push-item (containing-subtree (stack-ref :code 0 state)
                                     (stack-ref :code 1 state))
                 :code
                 (pop-item :code (pop-item :code state)))
      state)))

;; clojure.contrib/positions disappeared from libraries, but this is the function
;;   with the old indexed function expanded.
(defn positions
  "Returns a lazy sequence containing the positions at which pred
   is true for items in coll."
  [pred coll]
  (for [[idx elt] (map vector (iterate inc 0) coll) :when (pred elt)] idx))

(define-registered 
  code_position
  (fn [state]
    (if (not (empty? (rest (:code state))))
      (push-item (or (first (positions #{(stack-ref :code 1 state)}
                                       (ensure-list (stack-ref :code 0 state))))
                     -1)
                 :integer
                 (pop-item :code (pop-item :code state)))
      state)))

(define-registered 
  exec_k
  (fn [state]
    (if (not (empty? (rest (:exec state))))
      (push-item (first (:exec state))
                 :exec
                 (pop-item :exec (pop-item :exec state)))
      state)))

(define-registered 
  exec_s
  (fn [state]
    (if (not (empty? (rest (rest (:exec state)))))
      (let [stk (:exec state)
            x (first stk)
            y (first (rest stk))
            z (first (rest (rest stk)))]
        (if (<= (count-points (list y z)) @global-max-points-in-program)
          (push-item x
                     :exec
                     (push-item z
                                :exec
                                (push-item (list y z)
                                           :exec
                                           (pop-item :exec 
                                                     (pop-item :exec 
                                                               (pop-item :exec state))))))
          state))
      state)))

(define-registered 
  exec_y
  (fn [state]
    (if (not (empty? (:exec state)))
      (let [new-item (list 'exec_y (first (:exec state)))]
        (if (<= (count-points new-item) @global-max-points-in-program)
          (push-item (first (:exec state))
                     :exec
                     (push-item new-item
                                :exec
                                (pop-item :exec state)))
          state))
      state)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; zip instructions

(defmacro ignore-errors
  "Returns the result of evaluating e, or nil if it throws an exception."
  [e]
  `(try ~e (catch java.lang.Exception _# nil)))

(defn zip-mover
  "Returns a function that moves the top zipper in the specified way,
   acting as a no-op if the movement would produce an error."
  [move-fn]
  (fn [state]
    (if (empty? (:zip state))
      state
      (let [result (ignore-errors (move-fn (top-item :zip state)))]
        (if (or (nil? result) (not (vector? result)))
          state
          (push-item result :zip (pop-item :zip state)))))))

(define-registered zip_next (zip-mover zip/next))
(define-registered zip_prev (zip-mover zip/prev))
(define-registered zip_down (zip-mover zip/down))
(define-registered zip_up (zip-mover zip/up))
(define-registered zip_left (zip-mover zip/left))
(define-registered zip_leftmost (zip-mover zip/leftmost))
(define-registered zip_right (zip-mover zip/right))
(define-registered zip_rightmost (zip-mover zip/rightmost))

(defn zip-tester
  [test-fn]
  (fn [state]
    (if (empty? (:zip state))
      state
      (let [result (ignore-errors (test-fn (top-item :zip state)))]
        (if (nil? result)
          state
          (push-item result :boolean (pop-item :zip state)))))))

(define-registered zip_end? (zip-tester zip/end?))
(define-registered zip_branch? (zip-tester zip/branch?))

(defn zip-inserter
  [source inserter]
  (fn [state]
    (if (or (empty? (:zip state)) (empty? (source state)))
      state
      (let [z (stack-ref :zip 0 state)
            c (stack-ref source 0 state)
            result (ignore-errors (inserter z c))]
        (if (and result (<= (count-points (zip/root result)) @global-max-points-in-program))
          (push-item result :zip (pop-item :zip (pop-item source state)))
          state)))))

(define-registered zip_replace_fromcode (zip-inserter :code zip/replace))
(define-registered zip_replace_fromexec (zip-inserter :exec zip/replace))

(define-registered zip_insert_right_fromcode (zip-inserter :code zip/insert-right))
(define-registered zip_insert_right_fromexec (zip-inserter :exec zip/insert-right))

(define-registered zip_insert_left_fromcode (zip-inserter :code zip/insert-left))
(define-registered zip_insert_left_fromexec (zip-inserter :exec zip/insert-left))

(define-registered zip_insert_child_fromcode (zip-inserter :code zip/insert-child))
(define-registered zip_insert_child_fromexec (zip-inserter :exec zip/insert-child))

(define-registered zip_append_child_fromcode (zip-inserter :code zip/append-child))
(define-registered zip_append_child_fromexec (zip-inserter :exec zip/append-child))

(define-registered 
  zip_remove
  (fn [state]
    (if (empty? (:zip state))
      state
      (let [result (ignore-errors (zip/remove (top-item :zip state)))]
        (if result
          (push-item result :zip (pop-item :zip state))
          state)))))

(define-registered 
  zip_fromcode
  (fn [state]
    (if (empty? (:code state))
      state
      (let [result (ignore-errors (zip/seq-zip (top-item :code state)))]
        (if result
          (push-item result :zip (pop-item :code state))
          state)))))

(define-registered 
  zip_fromexec
  (fn [state]
    (if (empty? (:exec state))
      state
      (let [result (ignore-errors (zip/seq-zip (top-item :exec state)))]
        (if result
          (push-item result :zip (pop-item :exec state))
          state)))))

(defn zip-extractor
  [destination extractor]
  (fn [state]
    (if (empty? (:zip state))
      state
      (let [z (stack-ref :zip 0 state)
            result (ignore-errors (extractor z))]
        (if result
          (push-item result destination (pop-item :zip state))
          state)))))

(define-registered code_fromzipnode (zip-extractor :code zip/node))
(define-registered exec_fromzipnode (zip-extractor :exec zip/node))

(define-registered code_fromziproot (zip-extractor :code zip/root))
(define-registered exec_fromziproot (zip-extractor :exec zip/root))

(define-registered code_fromzipchildren (zip-extractor :code zip/children))
(define-registered exec_fromzipchildren (zip-extractor :exec zip/children))

(define-registered code_fromziplefts (zip-extractor :code zip/lefts))
(define-registered exec_fromziplefts (zip-extractor :exec zip/lefts))

(define-registered code_fromziprights (zip-extractor :code zip/rights))
(define-registered exec_fromziprights (zip-extractor :exec zip/rights))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; print all registered instructions on loading

(printf "\nRegistered instructions: %s\n\n" @registered-instructions)
(flush)

;; also set default value for atom-generators
(reset! global-atom-generators 
        (concat @registered-instructions
                (list 
                  (fn [] (lrand-int 100))
                  (fn [] (lrand)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; tag pseudo-instructions

(defn tag-instruction? 
  [i]
  (and (symbol? i) 
       (or
         (.startsWith (name i) "tag")
         (.startsWith (name i) "untag"))))

(defn closest-association
  "Returns the key-val pair for the closest match to the given tag
   in the given state."
  [tag state]
  (loop [associations (conj (vec (:tag state)) (first (:tag state)))] ;; conj does wrap
    (if (or (empty? (rest associations))
            (<= tag (ffirst associations)))
      (first associations)
      (recur (rest associations)))))

  
  
(defn handle-tag-instruction
  "Executes the tag instruction i in the state. Tag instructions take one of
   the following forms:
   tag_<type>_<number> 
   create tage/value association, with the value taken from the stack
   of the given type and the number serving as the tag
   untag_<number>
   remove the association for the closest-matching tag
   tagged_<number> 
   push the value associated with the closest-matching tag onto the
   exec stack (or no-op if no associations).
   tagged_code_<number> 
   push the value associated with the closest-matching tag onto the
   code stack (or no-op if no associations).
   tagged_when_<number>
   requires a boolean; if true pushes the value associated with the
   closest-matching tag onto the exec stack (or no-op if no boolean
   or no associations).
   "
  [i state]
  (let [iparts (string/split (name i) #"_")]
    (cond
      ;; if it's of the form tag_<type>_<number>: CREATE TAG/VALUE ASSOCIATION
      (= (first iparts) "tag") 
      (let [source-type (read-string (str ":" (nth iparts 1)))
            the-tag (read-string (nth iparts 2))]
        (if (empty? (source-type state))
          state
          ((if @global-pop-when-tagging pop-item (fn [type state] state))
               source-type
               (assoc state :tag (assoc (or (:tag state) (sorted-map))
                                        the-tag 
                                        (first (source-type state)))))))
      ;; if it's of the form untag_<number>: REMOVE TAG ASSOCIATION
      (= (first iparts) "untag")
      (if (empty? (:tag state))
        state
        (let [the-tag (read-string (nth iparts 1))]
          (assoc state :tag (dissoc (:tag state) (first (closest-association the-tag state))))))
      ;; if we get here it must be one of the retrieval forms starting with "tagged_", so 
      ;; we check to see if there are assocations and consider the cases if so
      :else
      (if (empty? (:tag state))
        state ;; no-op if no associations
        (cond ;; it's tagged_code_<number>
              (= (nth iparts 1) "code") 
              (let [the-tag (read-string (nth iparts 2))]
                (push-item (second (closest-association the-tag state)) :code state))
              ;; it's tagged_when_<number>
              (= (nth iparts 1) "when") 
              (if (empty? (:boolean state))
                state
                (if (= true (first (:boolean state)))
                  (let [the-tag (read-string (nth iparts 2))]
                    (push-item (second (closest-association the-tag state))
                               :exec (pop-item :boolean state)))
                  (pop-item :boolean state)))
              ;; else it's just tagged_<number>, result->exec
              :else
              (let [the-tag (read-string (nth iparts 1))]
                (push-item (second (closest-association the-tag state)) :exec state)))))))

(defn tag-instruction-erc
  "Returns a function which, when called on no arguments, returns a symbol of the form
   tag_<type>_<number> where type is one of the specified types and number is in the range 
   from 0 to the specified limit (exclusive)."
  [types limit]
  (fn [] (symbol (str "tag_"
                      (name (lrand-nth types))
                      "_"
                      (str (lrand-int limit))))))

(defn untag-instruction-erc
  "Returns a function which, when called on no arguments, returns a symbol of the form
   untag_<number> where number is in the range from 0 to the specified limit (exclusive)."
  [limit]
  (fn [] (symbol (str "untag_"
                      (str (lrand-int limit))))))

(defn tagged-instruction-erc
  "Returns a function which, when called on no arguments, returns a symbol of the form
   tagged_<number> where number is in the range from 0 to the specified limit (exclusive)."
  [limit]
  (fn [] (symbol (str "tagged_"
                      (str (lrand-int limit))))))

(defn tagged-code-instruction-erc
  "Returns a function which, when called on no arguments, returns a symbol of the form
   tagged_code_<number> where number is in the range from 0 to the specified limit (exclusive)."
  [limit]
  (fn [] (symbol (str "tagged_code_"
                      (str (lrand-int limit))))))

(defn tagged-when-instruction-erc
  "Returns a function which, when called on no arguments, returns a symbol of the form
   tagged_when_<number> where number is in the range from 0 to the specified limit (exclusive)."
  [limit]
  (fn [] (symbol (str "tagged_when_"
                      (str (lrand-int limit))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; tagged-code macros

(defn tagged-code-macro?
  "Retruns true if i is a tagged-code macro call."
  [i]
  (and (map? i)
       (:tagged_code_macro i)))

(defn handle-tag-code-macro
  "Given a tagged-code macro call and a push state, this returns the push state with the
   call expanded on the exec stack."
  [i state]
  (if (and (not (empty? (:argument_tags i))) (empty? (:tag state)))
    state
    (assoc state :exec
           (concat (concat
                     ;; possibly grab arguments from tag space and push them on the code stack
                     (map #(symbol (str "tagged_code_" (str %))) (:argument_tags i))
                     ;; push additional args, if any
                     (:additional_args i)
                     ;; execute the code instruction
                     (list (:instruction i))
                     ;; possibly tag results
                     (map #(symbol (str "tag_code_" (str %))) (:result_tags i))
                     )
                   (:exec state)))))

(defn tagged-code-macro-erc
  "Returns a function which, when called on no arguments, returns a tagged-code macro,
   which is a map."
  ([instruction tag-limit num-argument-tags num-result-tags additional-arg-generator]
    (fn [] {:tagged_code_macro true :instruction instruction
            :argument_tags (repeatedly num-argument-tags #(lrand-int tag-limit))
            :additional_args (additional-arg-generator)
            :result_tags (repeatedly num-result-tags #(lrand-int tag-limit))}))
  ([instruction tag-limit num-argument-tags num-result-tags]
    (tagged-code-macro-erc instruction tag-limit num-argument-tags num-result-tags (fn [] ()))))

(defn abbreviate-tagged-code-macros
  "Returns a copy of program with macros abbreviated as symbols. The returned program will
   not run as-is."
  [program]
  (postwalklist (fn [item]
                  (if (tagged-code-macro? item)
                    (symbol (str "TC_"
                                 (:instruction item)
                                 (print-str (:argument_tags item))
                                 (print-str (:result_tags item))
                                 (if (empty? (:additional_args item)) 
                                   "" 
                                   (print-str (:additional_args item)))))
                    item))
                program))
  
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; push interpreter

(defn recognize-literal
  "If thing is a literal, return its type -- otherwise return false."
  [thing]
  (cond (integer? thing) :integer
        (number? thing) :float
        (string? thing) :string
        (or (= thing true) (= thing false)) :boolean
        true false))

(def debug-recent-instructions ())

(defn execute-instruction
  "Executes a single Push instruction."
  [instruction state]
  ;; for debugging only, e.g. for stress-test
  ;(def debug-recent-instructions (cons instruction debug-recent-instructions))
  ;(def debug-recent-state state)
  (if (= instruction nil) ;; tests for nil and ignores it
    state
    (let [literal-type (recognize-literal instruction)]
      (cond 
        literal-type (push-item instruction literal-type state)
        (tag-instruction? instruction) (handle-tag-instruction instruction state)
        (tagged-code-macro? instruction) (handle-tag-code-macro instruction state)
        :else ((instruction @instruction-table) state)))))


(defn eval-push 
  "Executes the contents of the exec stack, aborting prematurely if execution limits are 
exceeded. The resulting push state will map :termination to :normal if termination was 
normal, or :abnormal otherwise."
  ([state] (eval-push state false false))
  ([state print] (eval-push state print false))
  ([state print trace]
    (loop [iteration 1 s state
           time-limit (if (zero? @global-evalpush-time-limit)
                        0
                        (+' @global-evalpush-time-limit (System/nanoTime)))]
      (if (or (> iteration @global-evalpush-limit)
            (empty? (:exec s))
            (and (not (zero? time-limit))
              (> (System/nanoTime) time-limit)))
        (assoc s :termination (if (empty? (:exec s)) :normal :abnormal))
        (let [exec-top (top-item :exec s)
              s (pop-item :exec s)]
          (let [s (if (seq? exec-top)
                    (assoc s :exec (concat exec-top (:exec s)))
                    (let [execution-result (execute-instruction exec-top s)]
                      (cond 
                        (= trace false) execution-result
                        (= trace true) (assoc execution-result
                                         :trace
                                         (cons exec-top (let [t (:trace s)] (if (seq? t) t ()))))
                        (= trace :changes) (if (= execution-result s)
                                             execution-result
                                             (assoc execution-result
                                               :trace
                                               (cons exec-top (let [t (:trace s)] (if (seq? t) t ()))))))))]
            (when print
              (printf "\nState after %s steps (last step: %s):\n" 
                iteration (if (seq? exec-top) "(...)" exec-top))
              (state-pretty-print s))
            (recur (inc iteration) s time-limit)))))))

(defn run-push 
  "The top level of the push interpreter; calls eval-schush between appropriate code/exec 
pushing/popping. The resulting push state will map :termination to :normal if termination was 
normal, or :abnormal otherwise."
  ([code state]
    (run-push code state false false))
  ([code state print]
    (run-push code state print false))
  ([code state print trace]
    (let [s (if top-level-push-code (push-item code :code state) state)]
      (let [s (push-item code :exec s)]
        (when print
          (printf "\nState after 0 steps:\n")
          (state-pretty-print s))
        (let [s (eval-push s print trace)]
          (if top-level-pop-code
            (pop-item :code s)
            s))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; pushgp

;; Individuals are records.
;; Populations are vectors of agents with individuals as their states (along with error and
;; history information).


(defrecord individual [program errors total-error hah-error history ancestors])

(defn make-individual [& {:keys [program errors total-error hah-error history ancestors]
                          :or {program nil
                               errors nil
                               total-error nil ;; a non-number is used to indicate no value
                               hah-error nil
                               history nil
                               ancestors nil}}]
  (individual. program errors total-error hah-error history ancestors))

(defn compute-total-error
  [errors]
  (reduce +' errors))

(defn compute-hah-error
  [errors]
  (if @global-use-historically-assessed-hardness
    (reduce +' (doall (map (fn [rate e] (*' (- 1.01 rate) e))
                           @solution-rates
                           errors)))
    nil))

(defn choose-node-index-with-leaf-probability
  "Returns an index into tree, choosing a leaf with probability 
   @global-node-selection-leaf-probability."
  [tree]
  (if (seq? tree)
    (if (> (lrand) @global-node-selection-leaf-probability)
      (second (lrand-nth (filter #(seq? (first %)) (map #(list %1 %2) (all-items tree) (iterate inc 0)))))
      (let [indexed-leaves (filter #(not (seq? (first %))) (map #(list %1 %2) (all-items tree) (iterate inc 0)))]
        (if (empty? indexed-leaves) 0 (second (lrand-nth indexed-leaves)))))
    0))

(defn choose-node-index-by-tournament
  "Returns an index into tree, choosing the largest subtree found in 
   a tournament of size @global-node-selection-tournament-size."
  [tree]
  (let [c (count-points tree)
        tournament-set
        (for [_ (range @global-node-selection-tournament-size)]
          (let [point-index (lrand-int c)
                subtree-size (count-points (code-at-point tree point-index))]
            {:i point-index :size subtree-size}))]
    (:i (last (sort-by :size tournament-set)))))

(defn select-node-index
  "Returns an index into tree using the node selection method indicated
   by @global-node-selection-method."
  [tree]
  (let [method @global-node-selection-method]
    (cond 
      (= method :unbiased) (lrand-int (count-points tree))
      (= method :leaf-probability) (choose-node-index-with-leaf-probability tree)
      (= method :size-tournament) (choose-node-index-by-tournament tree))))

(defn flatten-seqs
  "A version of flatten that only flattens nested seqs."
  [x]
  (filter (complement seq?)
          (rest (tree-seq seq? seq x))))

(defn auto-simplify 
  "Auto-simplifies the provided individual."
  [ind error-function steps print? progress-interval]
  (when print? (printf "\nAuto-simplifying with starting size: %s" (count-points (:program ind))))
  (loop [step 0 program (:program ind) errors (:errors ind) total-errors (:total-error ind)]
    (when (and print? 
               (or (>= step steps)
                   (zero? (mod step progress-interval))))
      (printf "\nstep: %s\nprogram: %s\nerrors: %s\ntotal: %s\nsize: %s\n" 
              step (not-lazy program) (not-lazy errors) total-errors (count-points program))
      (flush))
    (if (>= step steps)
      (make-individual :program program :errors errors :total-error total-errors 
                       :history (:history ind) 
                       :ancestors (if maintain-ancestors
                                    (cons (:program ind) (:ancestors ind))
                                    (:ancestors ind)))
      (let [new-program (if (< (lrand-int 5) 4)
                          ;; remove a small number of random things
                          (loop [p program how-many (inc (lrand-int 2))]
                            (if (zero? how-many)
                              p
                              (recur (remove-code-at-point p (lrand-int (count-points p)))
                                     (dec how-many))))
                          ;; flatten something
                          (let [point-index (lrand-int (count-points program))
                                point (code-at-point program point-index)]
                            (if (seq? point)
                              (insert-code-at-point program point-index (flatten-seqs point))
                              program)))
            new-errors (error-function new-program)
            new-total-errors (compute-total-error new-errors)]
        (if (<= new-total-errors total-errors)
          (recur (inc step) new-program new-errors new-total-errors)
          (recur (inc step) program errors total-errors))))))

(defn default-problem-specific-report
  "Customize this for your own problem. It will be called at the end of the generational report."
  [best population generation error-function report-simplifications]
  :no-problem-specific-report-function-defined)

(defn report 
  "Reports on the specified generation of a pushgp run. Returns the best
   individual of the generation."
  ([population generation error-function report-simplifications]
    (report population generation error-function report-simplifications default-problem-specific-report))
  ([population generation error-function report-simplifications problem-specific-report]
    (printf "\n\n;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;")(flush)
    ;(println (map :total-error population))(flush) ;***
    (printf "\n;; -*- Report at generation %s" generation)(flush)
    (let [sorted (sort-by :total-error < population)
          best (first sorted)]
      (printf "\nCurrent time: %s" (System/currentTimeMillis))
      (printf "\nBest program: %s" (not-lazy (:program best)))(flush)
      (when (> report-simplifications 0)
        (printf "\nPartial simplification (may beat best): %s"
                (not-lazy (:program (auto-simplify best error-function report-simplifications false 1000)))))
      (flush)
      (printf "\nErrors: %s" (not-lazy (:errors best)))(flush)
      (printf "\nTotal: %s" (:total-error best))(flush)
      (printf "\nHAH-error: %s" (:hah-error best))(flush)
      (printf "\nHistory: %s" (not-lazy (:history best)))(flush)
      (printf "\nSize: %s" (count-points (:program best)))(flush)
      (print "\n--- Population Statistics ---\nAverage total errors in population: ")(flush)
      (print (*' 1.0 (/ (reduce +' (map :total-error sorted)) (count population))))(flush)
      (printf "\nMedian total errors in population: %s"
              (:total-error (nth sorted (truncate (/ (count sorted) 2)))))(flush)
      (printf "\nAverage program size in population (points): %s"
              (*' 1.0 (/ (reduce +' (map count-points (map :program sorted)))
                         (count population))))(flush)
      (let [frequency-map (frequencies (map :program population))]
        (println "\nNumber of unique programs in population: " (count frequency-map))
        (println "Max copy number of one program: " (apply max (vals frequency-map)))
        (println "Min copy number of one program: " (apply min (vals frequency-map)))
        (println "Median copy number: " (nth (sort (vals frequency-map)) (Math/floor (/ (count frequency-map) 2)))))
      (printf "\n;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;\n\n")
      (flush)
      (problem-specific-report best population generation error-function report-simplifications)
      best)))

(defn lexicase-selection
  "Returns an individual that does the best on a randomly selected set of fitness cases"
  [pop]
  (loop [survivors pop
         cases (shuffle (range (count (:errors (first pop)))))]
    (if (or (empty? cases)
            (empty? (rest survivors)))
      (first survivors)
      (let [min-err-for-case (apply min (map #(nth % (first cases))
                                             (map #(:errors %) survivors)))]
        (recur (filter #(= (nth (:errors %) (first cases)) min-err-for-case)
                       survivors)
               (rest cases))))))

(defn select
  "Returns a selected parent, using lexicase or tournament selection."
  [pop tournament-size radius location]
  (if @global-use-lexicase-selection
    (lexicase-selection pop)
    (let [tournament-set 
          (doall
            (for [_ (range tournament-size)]
              (nth pop
                   (if (zero? radius)
                     (lrand-int (count pop))
                     (mod (+ location (- (lrand-int (+ 1 (* radius 2))) radius))
                          (count pop))))))
          err-fn (if @global-use-historically-assessed-hardness :hah-error :total-error)]
      (reduce (fn [i1 i2] (if (< (err-fn i1) (err-fn i2)) i1 i2))
              tournament-set))))

(defn mutate 
  "Returns a mutated version of the given individual."
  [ind mutation-max-points max-points atom-generators]
  (let [new-program (insert-code-at-point (:program ind) 
                                          (select-node-index (:program ind))
                                          (random-code mutation-max-points atom-generators))]
    (if (> (count-points new-program) max-points)
      ind
      (make-individual :program new-program :history (:history ind)
                       :ancestors (if maintain-ancestors
                                    (cons (:program ind) (:ancestors ind))
                                    (:ancestors ind))))))

;; some utilities are required for gaussian mutation

(defn gaussian-noise-factor
  "Returns gaussian noise of mean 0, std dev 1."
  []
  (* (Math/sqrt (* -2.0 (Math/log (lrand))))
     (Math/cos (* 2.0 Math/PI (lrand)))))

(defn perturb-with-gaussian-noise 
  "Returns n perturbed with std dev sd."
  [sd n]
  (+' n (* sd (gaussian-noise-factor))))

(defn perturb-code-with-gaussian-noise
  "Returns code with each float literal perturbed with std dev sd and perturbation probability
   num-perturb-probability."
  [code per-num-perturb-probability sd]
  (postwalklist (fn [item]
                  (if (and (float? item)
                           (< (lrand) per-num-perturb-probability))
                    (perturb-with-gaussian-noise sd item)
                    item))
                code))

(defn gaussian-mutate 
  "Returns a gaussian-mutated version of the given individual."
  [ind per-num-perturb-probability sd]
  (make-individual 
    :program (perturb-code-with-gaussian-noise (:program ind) per-num-perturb-probability sd)
    :history (:history ind)
    :ancestors (if maintain-ancestors
                 (cons (:program ind) (:ancestors ind))
                 (:ancestors ind))))

(defn crossover 
  "Returns a copy of parent1 with a random subprogram replaced with a random 
   subprogram of parent2."
  [parent1 parent2 max-points]
  (let [new-program (insert-code-at-point 
                      (:program parent1) 
                      (select-node-index (:program parent1))
                      (code-at-point (:program parent2)
                                     (select-node-index (:program parent2))))]
    (if (> (count-points new-program) max-points)
      parent1
      (make-individual :program new-program :history (:history parent1)
                       :ancestors (if maintain-ancestors
                                    (cons (:program parent1) (:ancestors parent1))
                                    (:ancestors parent1))))))

(defn evaluate-individual
  "Returns the given individual with errors, total-errors, and hah-errors,
   computing them if necessary."
  [i error-function rand-gen]
  (binding [*thread-local-random-generator* rand-gen]
    (let [p (:program i)
          e (if (and (seq? (:errors i)) @global-reuse-errors)
              (:errors i)
              (error-function p))
          te (if (and (number? (:total-error i)) @global-reuse-errors)
               (:total-error i)
               (keep-number-reasonable (compute-total-error e)))
          he (compute-hah-error e)]
      (make-individual :program p :errors e :total-error te :hah-error he
                       :history (if maintain-histories (cons te (:history i)) (:history i))
                       :ancestors (:ancestors i)))))

(defn breed
  "Replaces the state of the given agent with an individual bred from the given population (pop), 
   using the given parameters."
  [agt location rand-gen pop error-function population-size max-points atom-generators 
   mutation-probability  mutation-max-points crossover-probability simplification-probability 
   tournament-size reproduction-simplifications trivial-geography-radius
   gaussian-mutation-probability gaussian-mutation-per-number-mutation-probability 
   gaussian-mutation-standard-deviation]
  (binding [*thread-local-random-generator* rand-gen]
    (let [n (lrand)]
      (cond 
        ;; mutation
        (< n mutation-probability)
        (mutate (select pop tournament-size trivial-geography-radius location) 
                mutation-max-points max-points atom-generators)
        ;; crossover
        (< n (+ mutation-probability crossover-probability))
        (let [first-parent (select pop tournament-size trivial-geography-radius location)
              second-parent (select pop tournament-size trivial-geography-radius location)]
          (crossover first-parent second-parent max-points))
        ;; simplification
        (< n (+ mutation-probability crossover-probability simplification-probability))
        (auto-simplify (select pop tournament-size trivial-geography-radius location)
                       error-function reproduction-simplifications false 1000)
        ;; gaussian mutation
        (< n (+ mutation-probability crossover-probability simplification-probability 
                gaussian-mutation-probability))
        (gaussian-mutate (select pop tournament-size trivial-geography-radius location) 
                         gaussian-mutation-per-number-mutation-probability gaussian-mutation-standard-deviation)
        ;; replication
        true 
        (select pop tournament-size trivial-geography-radius location)))))

(defmacro print-params
  [params]
  (cons 'do (doall (map #(list 'println (str %) "=" %) params))))

(defn decimate
  "Returns the subset of the provided population remaining after sufficiently many
   elimination tournaments to reach the provided target-size."
  [population target-size tournament-size radius]
  (let [popsize (count population)]
    (if (<= popsize target-size)
      population
      (recur (let [tournament-index-set 
                   (let [first-location (lrand-int popsize)]
                     (cons first-location
                           (doall
                             (for [_ (range (dec tournament-size))]
                               (if (zero? radius)
                                 (lrand-int popsize)
                                 (mod (+ first-location (- (lrand-int (+ 1 (* radius 2))) radius))
                                      popsize))))))
                   victim-index
                   (reduce (fn [i1 i2] 
                             (if (> (:total-error (nth population i1))
                                    (:total-error (nth population i2)))
                               i1 
                               i2))
                           tournament-index-set)]
               (vec (concat (subvec population 0 victim-index)
                            (subvec population (inc victim-index)))))
             target-size tournament-size radius))))

(defn scaled-errors
  "A utility function for use in error functions, to implement error-scaling as described
   by Maarten Keijzer in Scaled Symbolic Regression, in Genetic Programming and Evolvable
   Machines 5(3), pp. 259-269, September 2004. This returns a sequence of scaled errors given
   a sequence of outputs, a sequence of targets, and a penalty. If there are any non-numeric
   items in the outputs, or if all of the outputs are the same, then all of the scaled errors
   will be equal to the penalty -- note that this means that you cannot use this method
   to solve a problem for which all targets are the same. An optional fourth argument,
   if true, causes the scaling slope and intercept to be printed; this is necessary for
   unscaling outputs of an evolved solution -- see examples/scaled_sextic.clj for an
   example."
  ([outputs targets penalty]
    (scaled-errors outputs targets penalty false))
  ([outputs targets penalty print-slope-and-intercept]
    (if (or (some #(not (number? %)) outputs) (apply = outputs))
      (doall (repeat (count outputs) penalty))
      (let [average-output (/ (reduce +' outputs) (count outputs))
            average-target (/ (reduce +' targets) (count targets))
            slope (/ 
                    (reduce +' 
                            (map (fn [target output]
                                   (*' (-' target average-target) 
                                       (-' output average-output)))
                                 targets
                                 outputs))
                    (reduce +'
                            (map (fn [output] (math/expt (-' output average-output) 2))
                                 outputs)))
            intercept (-' average-target  (*' slope average-output))]
        (when print-slope-and-intercept 
          (println "slope " slope ", intercept " intercept))
        (doall (map (fn [target output]
                      (math/expt (float (-' target (+' intercept (*' slope output)))) 2))
                    targets
                    outputs))))))

(defn git-last-commit-hash
  "Returns the last Git commit hash"
  []
  (let [dir (local-file/project-dir)]
    (string/trim
      (slurp
        (str dir
             "/.git/"
             (subs
               (string/trim
                 (slurp
                   (str dir "/.git/HEAD")))
               5))))))

(defn pushgp
  "The top-level routine of pushgp."
  [& {:keys [error-function error-threshold population-size max-points atom-generators max-generations
             max-mutations mutation-probability mutation-max-points crossover-probability 
             simplification-probability tournament-size report-simplifications final-report-simplifications
             reproduction-simplifications trivial-geography-radius decimation-ratio decimation-tournament-size
             evalpush-limit evalpush-time-limit node-selection-method node-selection-leaf-probability
             node-selection-tournament-size pop-when-tagging gaussian-mutation-probability 
             gaussian-mutation-per-number-mutation-probability gaussian-mutation-standard-deviation
             reuse-errors problem-specific-report use-single-thread random-seed 
             use-historically-assessed-hardness use-lexicase-selection]
      :or {error-function (fn [p] '(0)) ;; pgm -> list of errors (1 per case)
           error-threshold 0
           population-size 1000
           max-points 50
           atom-generators (concat @registered-instructions
                                   (list 
                                     (fn [] (lrand-int 100))
                                     (fn [] (lrand))))
           max-generations 1001
           mutation-probability 0.4
           mutation-max-points 20
           crossover-probability 0.4
           simplification-probability 0.1
           tournament-size 7
           report-simplifications 100
           final-report-simplifications 1000
           reproduction-simplifications 1
           trivial-geography-radius 0
           decimation-ratio 1
           decimation-tournament-size 2
           evalpush-limit 150
           evalpush-time-limit 0
           node-selection-method :unbiased
           node-selection-leaf-probability 0.1
           node-selection-tournament-size 2
           pop-when-tagging true
           gaussian-mutation-probability 0.0
           gaussian-mutation-per-number-mutation-probability 0.5
           gaussian-mutation-standard-deviation 0.1
           reuse-errors true
           problem-specific-report default-problem-specific-report
           use-single-thread false
           random-seed (System/nanoTime)   
           use-historically-assessed-hardness false    
           use-lexicase-selection false    
           }}]
  (binding [*thread-local-random-generator* (java.util.Random. random-seed)]
    ;; set globals from parameters
    (reset! global-atom-generators atom-generators)
    (reset! global-max-points-in-program max-points)
    (reset! global-evalpush-limit evalpush-limit)
    (reset! global-evalpush-time-limit evalpush-time-limit)
    (reset! global-node-selection-method node-selection-method)
    (reset! global-node-selection-leaf-probability node-selection-leaf-probability)
    (reset! global-node-selection-tournament-size node-selection-tournament-size)
    (reset! global-pop-when-tagging pop-when-tagging)
    (reset! global-reuse-errors reuse-errors)
    (reset! global-use-historically-assessed-hardness use-historically-assessed-hardness)
    (reset! global-use-lexicase-selection use-lexicase-selection)
    (printf "\nStarting PushGP run.\n\n") (flush)
    (printf "Clojush version = ")
    (try
      (let [version-str (apply str (butlast (re-find #"\".*\""        
                                                     (first (string/split-lines
                                                              (local-file/slurp* "project.clj"))))))
            version-number (.substring version-str 1 (count version-str))]
        (if (empty? version-number)
          (throw Exception)
          (printf (str version-number "\n"))))
      (flush)
      (catch Exception e
             (printf "version number unavailable\n")
             (flush)))
    (try
      (let [git-hash (git-last-commit-hash)]
        (if (empty? git-hash)
          (throw Exception)
          (do
            ;; NOTES: - Last commit hash will only be correct if this code has
            ;;          been committed already.
            ;;        - GitHub link will only work if commit has been pushed
            ;;          to GitHub.
            (printf (str "Hash of last Git commit = " git-hash "\n"))
            (printf (str "GitHub link = https://github.com/lspector/Clojush/commit/"
                         git-hash
                         "\n"))
            (flush))))
      (catch Exception e
             (printf "Hash of last Git commit = unavailable\n")
             (printf "GitHub link = unavailable\n")
             (flush)))
    (print-params 
      (error-function error-threshold population-size max-points atom-generators max-generations 
                      mutation-probability mutation-max-points crossover-probability
                      simplification-probability gaussian-mutation-probability 
                      gaussian-mutation-per-number-mutation-probability gaussian-mutation-standard-deviation
                      tournament-size report-simplifications final-report-simplifications
                      trivial-geography-radius decimation-ratio decimation-tournament-size evalpush-limit
                      evalpush-time-limit node-selection-method node-selection-tournament-size
                      node-selection-leaf-probability pop-when-tagging reuse-errors
                      use-single-thread random-seed use-historically-assessed-hardness
                      use-lexicase-selection
                      ))
    (printf "\nGenerating initial population...\n") (flush)
    (let [pop-agents (vec (doall (for [_ (range population-size)] 
                                   ((if use-single-thread atom agent)
                                        (make-individual 
                                          :program (random-code max-points atom-generators))
                                        :error-handler (fn [agnt except] (println except))))))
          child-agents (vec (doall (for [_ (range population-size)]
                                     ((if use-single-thread atom agent)
                                          (make-individual)
                                          :error-handler (fn [agnt except] (println except))))))
          rand-gens (vec (doall (for [k (range population-size)]
                                  (java.util.Random. (+ random-seed (inc k))))))]
      (loop [generation 0]
        (printf "\n\n-----\nProcessing generation: %s\nComputing errors..." generation) (flush)
        (dorun (map #((if use-single-thread swap! send) % evaluate-individual error-function %2) pop-agents rand-gens))
        (when-not use-single-thread (apply await pop-agents)) ;; SYNCHRONIZE ; might this need a dorun?
        (printf "\nDone computing errors.") (flush)
        ;; calculate solution rates if necessary for historically-assessed hardness
        (when (and use-historically-assessed-hardness
                   (not use-lexicase-selection))
          (reset! solution-rates
                  (let [error-seqs (map :errors (map deref pop-agents))
                        num-cases (count (first error-seqs))]
                    (doall (for [i (range num-cases)]
                             (/ (count (filter #(<= % error-threshold) (map #(nth % i) error-seqs)))
                                population-size)))))
          (printf "\nSolution rates: ")
          (println (doall (map float @solution-rates))))
        ;; report and check for success
        (let [best (report (vec (doall (map deref pop-agents))) generation error-function 
                           report-simplifications problem-specific-report)]
          (if (<= (:total-error best) error-threshold)
            (do (printf "\n\nSUCCESS at generation %s\nSuccessful program: %s\nErrors: %s\nTotal error: %s\nHistory: %s\nSize: %s\n\n"
                        generation (not-lazy (:program best)) (not-lazy (:errors best)) (:total-error best) 
                        (not-lazy (:history best)) (count-points (:program best)))
                (when print-ancestors-of-solution
                  (printf "\nAncestors of solution:\n")
                  (println (:ancestors best)))
                (auto-simplify best error-function final-report-simplifications true 500))
            (do (if (>= generation max-generations)
                  (printf "\nFAILURE\n")
                  (do (printf "\nProducing offspring...") (flush)
                      (let [pop (decimate (vec (doall (map deref pop-agents))) 
                                          (int (* decimation-ratio population-size))
                                          decimation-tournament-size 
                                          trivial-geography-radius)]
                        (dotimes [i population-size]
                          ((if use-single-thread swap! send)
                               (nth child-agents i) 
                               breed i (nth rand-gens i) pop error-function population-size max-points atom-generators 
                               mutation-probability mutation-max-points crossover-probability 
                               simplification-probability tournament-size reproduction-simplifications 
                               trivial-geography-radius gaussian-mutation-probability 
                               gaussian-mutation-per-number-mutation-probability gaussian-mutation-standard-deviation)))
                      (when-not use-single-thread (apply await child-agents)) ;; SYNCHRONIZE
                      (printf "\nInstalling next generation...") (flush)
                      (dotimes [i population-size]
                        ((if use-single-thread swap! send)
                             (nth pop-agents i) (fn [av] (deref (nth child-agents i)))))
                      (when-not use-single-thread (apply await pop-agents)) ;; SYNCHRONIZE
                      (recur (inc generation)))))))))))

(defn pushgp-map
  "Calls pushgp with the args in argmap."
  [argmap]
  (apply pushgp (apply concat argmap)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; stress test

(defn stress-test
  "Performs a stress test of the registered instructions by generating and running n
   random programs. For more thorough testing and debugging of Push instructions you many
   want to un-comment code in execute-instruction that will allow you to look at recently
   executed instructions and the most recent state after an error. That code burns memory,
   however, so it is normally commented out. You might also want to comment out the handling
   of nil values in execute-instruction, do see if any instructions are introducing nils."
  [n]
  (let [completely-random-program
        (fn []
          (random-code 100 (concat @registered-instructions
                                   (list (fn [] (lrand-int 100))
                                         (fn [] (lrand))))))]
    (loop [i 0 p (completely-random-program)]
      (if (>= i n)
        (println :no-errors-found-in-stress-test)
        (let [result (run-push p (make-push-state) false)]
          (if result
            (recur (inc i) (completely-random-program))
            (println p)))))))

;(stress-test 10000)

(defn -main 
  "A main function for clojush, which assumes that the first/only argument is the name
   of a problem file that contains a top level call. Exits after completion of the call.
   This allows one to run an example with a call from the OS shell prompt like:
       lein run examples.simple-regression"
  [& args]
  (use (symbol (first args)))
  (System/exit 0))