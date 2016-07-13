(ns otplike.process-test
  (:require [clojure.test :refer [is deftest]]
            [otplike.process :as process :refer [!]]
            [otplike.trace :as trace]
            [clojure.core.async :as async :refer [<!! <! >! put! go go-loop]]
            [clojure.core.async.impl.protocols :as ap]
            [clojure.core.match :refer [match]]))

(trace/set-trace  (fn  [_pid _event]))

(defn- uuid-keyword []
  (keyword (str (java.util.UUID/randomUUID))))

(defn- await-message [inbox timeout-ms]
  (go
    (let [timeout (async/timeout timeout-ms)]
      (match (async/alts! [inbox timeout])
        [[:EXIT _ reason] inbox] [:exit-message [:reason reason]]
        [nil inbox] :inbox-closed
        [msg inbox] [:message msg]
        [nil timeout] :timeout))))

(defn- await-completion [chan timeout-ms]
  (let [timeout (async/timeout timeout-ms)]
    (match (async/alts!! [chan timeout])
      [nil chan] :ok
      [nil timeout] (throw (Exception. (str "timeout " timeout-ms))))))

(defn stack-trace
  ([e]
   (let [s-trace (stack-trace e '())]
     (reduce (fn [acc x] (assoc x :cause acc)) (first s-trace) (rest s-trace))))
  ([e acc]
   (if e
     (recur (.getCause e)
            (conj acc {:message (.getMessage e)
                       :class (.getName (class e))
                       :stack-trace (mapv str (.getStackTrace e))}))
     acc)))

(defmacro defproc [args & body]
  `(fn ~args
     (go
       (try
         ~@body
         :normal
         (catch Exception e#
           [:exception (stack-trace e#)])
         (catch Error e#
           (println e#)
           (System/exit 1))))))

;; ====================================================================
;; (self [])
;;   Returns the process identifier of the calling process. Throws when
;;   called not in process context.

(deftest ^:parallel self-returns-process-pid-in-process-context
  (let [done (async/chan)]
    (process/spawn
      (defproc [inbox]
        (is (process/pid? (process/self))
            "self must return pid when called in process context")
        (is (= (process/self) (process/self))
            "self must return the same pid when called by the same process")
        (! (process/self) :msg)
        (is (= [:message :msg] (<! (await-message inbox 100)))
            "message sent to self must appear in inbox")
        (async/close! done))
      []
      {})
    (await-completion done 500)))

(deftest ^:parallel self-fails-in-non-process-context
  (is (thrown? Exception (process/self))
      "self must throw when called not in process context"))

;; ====================================================================
;; (pid? [term])
;;   Returns true if term is a process identifier, false otherwise.

(deftest ^:parallel pid?-returns-false-on-non-pid
  (is (not (process/pid? nil)) "pid? must return false on nonpid arguement")
  (is (not (process/pid? 1)) "pid? must return false on nonpid arguement")
  (is (not (process/pid? "not-a-pid"))
      "pid? must return false on nonpid arguement")
  (is (not (process/pid? [])) "pid? must return false on nonpid arguement")
  (is (not (process/pid? '())) "pid? must return false on nonpid arguement")
  (is (not (process/pid? #{})) "pid? must return false on nonpid arguement")
  (is (not (process/pid? {})) "pid? must return false on nonpid arguement"))

(deftest ^:parallel pid?-returns-true-on-pid
  (is (process/pid? (process/spawn (defproc [_inbox]) [] {}))
      "pid? must return true on pid argument"))

;; ====================================================================
;; (pid->str [pid])
;;   Returns a string corresponding to the text representation of pid.
;;   Throws if pid is not a process identifier.
;;   Warning: this function is intended for debugging and is not to be
;;   used in application programs.

(deftest ^:parallel pid->str-returns-string
  (is (string? (process/pid->str (process/spawn (defproc [_inbox]) [] {})))
      "pid->str must return string on pid argument"))

(deftest ^:parallel pid->str-fails-on-non-pid
  (is (thrown? Throwable (process/pid->str nil))
      "pid->str must throw on nonpid arguement")
  (is (thrown? Throwable (process/pid->str 1))
      "pid->str must throw on nonpid arguement")
  (is (thrown? Throwable (process/pid->str "not-a-pid"))
      "pid->str must throw on nonpid arguement")
  (is (thrown? Throwable (process/pid->str []))
      "pid->str must throw on nonpid arguement")
  (is (thrown? Throwable (process/pid->str '()))
      "pid->str must throw on nonpid arguement")
  (is (thrown? Throwable (process/pid->str #{}))
      "pid->str must throw on nonpid arguement")
  (is (thrown? Throwable (process/pid->str {}))
      "pid->str must throw on nonpid arguement"))

;; ====================================================================
;; (whereis [reg-name])
;;   Returns the process identifier with the registered name reg-name,
;;   or nil if the name is not registered. Throws on nil argument.

(deftest ^:parallel whereis-returns-process-pid-on-registered-name
  (let [done (async/chan)
        reg-name (uuid-keyword)
        pid (process/spawn
              (defproc [_inbox]
                (is (= (process/self) (process/whereis reg-name))
                    "whereis must return process pid on registered name")
                (await-completion done 100))
              []
              {:register reg-name})]
    (is (= pid (process/whereis reg-name))
        "whereis must return process pid on registered name")
    (async/close! done)))

(deftest ^:parallel whereis-returns-nil-on-not-registered-name
  (is (nil? (process/whereis "name"))
      "whereis must return nil on not registered name")
  (is (nil? (process/whereis :name))
      "whereis must return nil on not registered name")
  (is (nil? (process/whereis [:some :name]))
      "whereis must return nil on not registered name")
  (is (nil? (process/whereis 123))
      "whereis must return nil on not registered name")
  (is (nil? (process/whereis '(:a :b)))
      "whereis must return nil on not registered name")
  (is (nil? (process/whereis {:a 1}))
      "whereis must return nil on not registered name")
  (is (nil? (process/whereis #{:b}))
      "whereis must return nil on not registered name"))

(deftest ^:parallel whereis-throws-on-nil-argument
  (is (thrown? Throwable (process/whereis nil))
      "whereis must throw on nil argument"))

;; ====================================================================
;; (! [dest message])
;;   Sends a message to dest. dest can be a process identifier, or a
;;   registered name. Returns true if message was sent (process was
;;   alive), false otherwise. Throws if any of the arguments is nil.

(deftest ^:parallel !-returns-true-sending-to-alive-process-by-pid
  (let [done (async/chan)
        pid (process/spawn (defproc [_] (await-completion done 100)) [] {})]
    (is (= true (! pid :msg)) "! must return true sending to alive process")
    (async/close! done)))

(deftest ^:parallel !-returns-true-sending-to-alive-process-by-reg-name
  (let [done (async/chan)
        reg-name (uuid-keyword)]
    (process/spawn (defproc [_] (await-completion done 100))
                   []
                   {:register reg-name})
    (is (= true (! reg-name :msg)) "! must return true sending to alive process")
    (async/close! done)))

(deftest ^:parallel !-returns-false-sending-to-terminated-process-by-reg-name
  (let [reg-name (uuid-keyword)]
    (process/spawn (defproc [_inbox]) [] {:register reg-name})
    (<!! (async/timeout 50))
    (is (= false (! reg-name :msg))
        "! must return false sending to terminated process")))

(deftest ^:parallel !-returns-false-sending-to-unregistered-name
  (is (= false (! (uuid-keyword) :msg))
      "! must return false sending to unregistered name"))

(deftest ^:parallel !-returns-false-sending-to-terminated-process-by-pid
  (let [done (async/chan)
        pid (process/spawn (defproc [_] (async/close! done)) [] {})]
    (await-completion done 100)
    (<!! (async/timeout 50))
    (is (= false (! pid :msg))
        "! must return false sending to terminated process")))

(deftest ^:parallel !-throws-on-nil-arguments
  (is (thrown? Throwable (! nil :msg))
      "! must throw on when dest argument is nil")
  (is (thrown? Throwable (! (process/spawn (defproc [_]) [] {}) nil))
      "! must throw on when message argument is nil")
  (is (thrown? Throwable (! nil nil))
      "! must throw on when both arguments are nil"))

(deftest ^:parallel !-delivers-message-sent-by-pid-to-alive-process
  (let [done (async/chan)
        proc-fn (defproc [inbox]
                  (is (= [:message :msg] (<! (await-message inbox 100)))
                      "message sent with ! to pid must appear in inbox")
                  (async/close! done))
        pid (process/spawn proc-fn [] {})]
    (! pid :msg)
    (await-completion done 300)))

(deftest ^:parallel !-delivers-message-sent-by-registered-name-to-alive-process
  (let [done (async/chan)
        proc-fn (defproc [inbox]
                  (is (= [:message :msg] (<! (await-message inbox 100)))
                      (str "message sent with ! to registered name"
                           " must appear in inbox"))
                  (async/close! done))
        reg-name (uuid-keyword)]
    (process/spawn proc-fn [] {:register reg-name})
    (! reg-name :msg)
    (await-completion done 200)))

;; ====================================================================
;; (exit [pid reason])
;;   Sends an exit signal with exit reason to the process identified
;;   by pid.
;;   If reason is any term, except :normal or :kill:
;;   - if pid is not trapping exits, pid itself exits with exit reason.
;;   - if pid is trapping exits, the exit signal is transformed into a
;;     message [:EXIT, from, reason] and delivered to the message queue
;;     of pid. from is the process identifier of the process that sent
;;     the exit signal.
;;   If reason is :normal, pid does not exit. If pid is trapping exits,
;;   the exit signal is transformed into a message
;;   [:EXIT, from, :normal] and delivered to its message queue.
;;   If reason is :kill, an untrappable exit signal is sent to pid,
;;   which unconditionally exits with reason :killed.
;;   Returns true if exit signal was sent (process was alive), false
;;   otherwise.
;;   Throws if pid is not a pid, or message is nil.

    ; inbox becomes closed
    ; future messages do not arrive to the process' inbox
    ; all linked/monitoring processes receive exit signal/message
    ; process no longer registered

(deftest ^:parallel exit-throws-on-nil-reason
  (let [done (async/chan)
        pid (process/spawn
              (defproc [_] (await-completion done 100)) [] {})]
    (is (thrown? Throwable (process/exit pid nil))
        "exit must throw when reason argument is nil")
    (async/close! done)))

(deftest ^:parallel exit-throws-on-not-a-pid
  (is (thrown? Throwable (process/exit nil :normal))
      "exit must throw on not a pid argument")
  (is (thrown? Throwable (process/exit 1 :normal))
      "exit must throw on not a pid argument")
  (is (thrown? Throwable (process/exit "pid1" :normal))
      "exit must throw on not a pid argument")
  (is (thrown? Throwable (process/exit [] :normal))
      "exit must throw on not a pid argument")
  (is (thrown? Throwable (process/exit '() :normal))
      "exit must throw on not a pid argument")
  (is (thrown? Throwable (process/exit {} :normal))
      "exit must throw on not a pid argument")
  (is (thrown? Throwable (process/exit #{} :normal))
      "exit must throw on not a pid argument"))

(deftest ^:parallel exit-normal-no-trap-exit
  (let [done (async/chan)
        proc-fn (defproc [inbox]
                  (is (= :timeout (<! (await-message inbox 100)))
                      "exit with reason :normal must not close process' inbox")
                  (async/close! done))
        pid (process/spawn proc-fn [] {})]
    (process/exit pid :normal)
    (await-completion done 500)))

(deftest ^:parallel exit-normal-trap-exit
  (let [done (async/chan)
        proc-fn (defproc [inbox]
                  (is (= [:exit-message [:reason :normal]]
                         (<! (await-message inbox 100)))
                      (str "exit must send [:EXIT pid :normal] message"
                           " to process trapping exits"))
                  (async/close! done))
        pid (process/spawn proc-fn [] {:flags {:trap-exit true}})]
    (process/exit pid :normal)
    (await-completion done 500)))

(deftest ^:parallel exit-normal-registered-process
  (let [done (async/chan)
        reg-name (uuid-keyword)
        pid (process/spawn (defproc [_] (await-completion done 300))
                           []
                           {:register reg-name})]
    (is ((into #{} (process/registered)) reg-name)
        "registered process must be in list of registered before exit")
    (async/close! done)
    (<!! (async/timeout 50))
    (is (nil? ((into #{} (process/registered)) reg-name))
        "process must not be registered after exit")))

(deftest ^:parallel exit-abnormal-no-trap-exit
  (let [done (async/chan)
        proc-fn (defproc [inbox]
                  (is (= :inbox-closed (<! (await-message inbox 100)))
                      (str "exit with reason other than :normal must close"
                           "process' inbox"))
                  (async/close! done))
        pid (process/spawn proc-fn [] {})]
    (process/exit pid :abnormal)
    (await-completion done 500)))

(deftest ^:parallel exit-abnormal-trap-exit
  (let [done (async/chan)
        proc-fn (defproc [inbox]
                  (is (= [:exit-message [:reason :abnormal]]
                         (<! (await-message inbox 100)))
                      (str "exit must send [:EXIT _ reason] message"
                           " to process trapping exits"))
                  (async/close! done))
        pid (process/spawn proc-fn [] {:flags {:trap-exit true}})]
    (process/exit pid :abnormal)
    (await-completion done 500)))

(deftest ^:parallel exit-kill-no-trap-exit
  (let [done (async/chan)
        proc-fn (defproc [inbox]
                  (is (= :inbox-closed (<! (await-message inbox 300)))
                      "exit with reason :kill must close process' inbox")
                  (async/close! done))
        pid (process/spawn proc-fn [] {})]
    (process/exit pid :kill)
    (await-completion done 500)))

(deftest ^:parallel exit-kill-trap-exit
  (let [done (async/chan)
        proc-fn (defproc [inbox]
                  (is (= :inbox-closed (<! (await-message inbox 300)))
                      "exit with reason :kill must close process' inbox")
                  (async/close! done))
        pid (process/spawn proc-fn [] {:flags {:trap-exit true}})]
    (process/exit pid :kill)
    (await-completion done 500)))

(deftest ^:parallel exit-returns-true-on-alive-process
  (let [proc-fn (defproc [_inbox] (<! (async/timeout 100)))]
    (let [pid (process/spawn proc-fn [] {})]
      (is (= true (process/exit pid :normal))
          "exit must return true on alive process"))
    (let [pid (process/spawn proc-fn [] {})]
      (is (= true (process/exit pid :abnormal))
          "exit must return true on alive process"))
    (let [pid (process/spawn proc-fn [] {})]
      (is (= true (process/exit pid :kill))
          "exit must return true on alive process"))
    (let [pid (process/spawn proc-fn [] {:flags {:trap-exit true}})]
      (is (= true (process/exit pid :normal))
          "exit must return true on alive process"))
    (let [pid (process/spawn proc-fn [] {:flags {:trap-exit true}})]
      (is (= true (process/exit pid :abnormal))
          "exit must return true on alive process"))
    (let [pid (process/spawn proc-fn [] {:flags {:trap-exit true}})]
      (is (= true (process/exit pid :kill))
          "exit must return true on alive process"))))

(deftest ^:parallel exit-returns-false-on-terminated-process
  (let [pid (process/spawn (defproc [_inbox]) [] {})]
    (<!! (async/timeout 50))
    (is (= false (process/exit pid :normal))
        "exit must return false on terminated process")
    (is (= false (process/exit pid :abnormal))
        "exit must return false on terminated process")
    (is (= false (process/exit pid :kill))
        "exit must return false on terminated process")
    (is (= false (process/exit pid :normal))
        "exit must return false on terminated process")
    (is (= false (process/exit pid :abnormal))
        "exit must return false on terminated process")
    (is (= false (process/exit pid :kill))
        "exit must return false on terminated process")))

(deftest ^:parallel exit-self
  (let [done (async/chan)]
    (process/spawn
      (defproc [inbox]
        (process/exit (process/self) :normal)
        (is (= [:exit-message [:reason :normal]]
               (<! (await-message inbox 100)))
            (str "exit with reason :normal must send [:EXIT pid :normal]"
                 " message to process trapping exits"))
        (async/close! done))
      []
      {:flags {:trap-exit true}})
    (await-completion done 500))
  (let [done (async/chan)]
    (process/spawn
      (defproc [inbox]
        (process/exit (process/self) :abnormal-1)
        (is (= [:exit-message [:reason :abnormal-1]]
               (<! (await-message inbox 300)))
            (str "exit must send [:EXIT pid reason]"
                 " message to process trapping exits"))
        (process/exit (process/self) :abnormal-2)
        (is (= [:exit-message [:reason :abnormal-2]]
               (<! (await-message inbox 300)))
            (str "exit must send [:EXIT pid reason]"
                 " message to process trapping exits"))
        (async/close! done))
      []
      {:flags {:trap-exit true}})
    (await-completion done 1000))
  (let [done (async/chan)]
    (process/spawn
      (defproc [inbox]
        (process/exit (process/self) :kill)
        (is (= :inbox-closed (<! (await-message inbox 300)))
            "exit with reason :kill must close inbox of process trapping exits")
        (async/close! done))
      []
      {:flags {:trap-exit true}})
    (await-completion done 500))
  (let [done (async/chan)]
    (process/spawn
      (defproc [inbox]
        (process/exit (process/self) :normal)
        (is (= :timeout (<! (await-message inbox 300)))
            (str "exit with reason :normal must do nothing"
                 " to process not trapping exits"))
        (async/close! done))
      []
      {})
    (await-completion done 500))
  (let [done (async/chan)]
    (process/spawn
      (defproc [inbox]
        (process/exit (process/self) :abnormal)
        (is (= :inbox-closed (<! (await-message inbox 300)))
            (str "exit with any reason except :normal must close"
                 " inbox of proces not trapping exits"))
        (async/close! done))
      []
      {})
    (await-completion done 500))
  (let [done (async/chan)]
    (process/spawn
      (defproc [inbox]
        (process/exit (process/self) :kill)
        (is (= :inbox-closed (<! (await-message inbox 300)))
            (str "exit with reason :kill must close inbox of process"
                 " not trapping exits"))
        (async/close! done))
      []
      {})
    (await-completion done 500)))

; TODO
(deftest ^:parallel exit-kill-reason-killed) ; use link or monitor to test the reason

;; ====================================================================
;; (flag [flag value])
;;   Sets the value of a process flag. See description of each flag
;;   below.
;;   Returns the old value of a flag.
;;   Throws when called not in process context.
;;
;;   :trap-exit
;;   When :trap-exit is set to true, exit signals arriving to a process
;;   are converted to [:EXIT, from, reason] messages, which can be
;;   received as ordinary messages. If :trap-exit is set to false, the
;;   process exits if it receives an exit signal other than :normal and
;;   the exit signal is propagated to its linked processes. Application
;;   processes are normally not to trap exits.

(deftest ^:parallel flag-trap-exit-true-makes-process-to-trap-exits
  (let [done1 (async/chan)
        done2 (async/chan)
        proc-fn (defproc [inbox]
                  (process/flag :trap-exit true)
                  (async/close! done1)
                  (is (= [:exit-message [:reason :normal]]
                         (<! (await-message inbox 100)))
                      (str "flag :trap-exit set to true in process must"
                           " make process to trap exits"))
                  (async/close! done2))
        pid (process/spawn proc-fn [] {})]
    (await-completion done1 100)
    (match (process/exit pid :normal) true :ok)
    (await-completion done2 200)))

(deftest ^:parallel flag-trap-exit-false-makes-process-not-to-trap-exits
  (let [done1 (async/chan)
        done2 (async/chan)
        proc-fn (defproc [inbox]
                  (process/flag :trap-exit false)
                  (async/close! done1)
                  (is (= :timeout (<! (await-message inbox 100)))
                      (str "flag :trap-exit set to false in process must"
                           " make process not to trap exits"))
                  (async/close! done2))
        pid (process/spawn proc-fn [] {:flags {:trap-exit true}})]
    (await-completion done1 100)
    (match (process/exit pid :normal) true :ok)
    (await-completion done2 200)))

(deftest ^:parallel flag-trap-exit-switches-trapping-exit
  (let [done1 (async/chan)
        done2 (async/chan)
        done3 (async/chan)
        proc-fn (defproc [inbox]
                  (process/flag :trap-exit true)
                  (async/close! done1)
                  (is (= [:exit-message [:reason :abnormal]]
                         (<! (await-message inbox 50)))
                      (str "flag :trap-exit set to true in process must"
                           " make process to trap exits"))
                  (process/flag :trap-exit false)
                  (async/close! done2)
                  (is (= :inbox-closed (<! (await-message inbox 100)))
                      (str "flag :trap-exit switched second time  in process"
                           " must make process to switch trapping exits"))
                  (async/close! done3))
        pid (process/spawn proc-fn [] {})]
    (await-completion done1 100)
    (match (process/exit pid :abnormal) true :ok)
    (await-completion done2 200)
    (match (process/exit pid :abnormal) true :ok)
    (await-completion done3 300)))

(deftest ^:parallel flag-trap-exit-returns-old-value
  (let [done (async/chan)
        proc-fn (defproc [inbox]
                  (is (= true (process/flag :trap-exit false))
                      "setting flag :trap-exit must return its previous value")
                  (is (= false (process/flag :trap-exit false))
                      "setting flag :trap-exit must return its previous value")
                  (is (= false (process/flag :trap-exit true))
                      "setting flag :trap-exit must return its previous value")
                  (is (= true (process/flag :trap-exit true))
                      "setting flag :trap-exit must return its previous value")
                  (async/close! done))]
    (process/spawn proc-fn [] {:flags {:trap-exit true}})
    (await-completion done 300)))

(deftest ^:parallel flag-throws-on-unknown-flag
  (let [done (async/chan)
        proc-fn (defproc [inbox]
                  (is (thrown? Throwable (process/flag [] false))
                      "flag must throw on unknown flag")
                  (is (thrown? Throwable (process/flag 1 false))
                      "flag must throw on unknown flag")
                  (is (thrown? Throwable (process/flag :unknown false))
                      "flag must throw on unknown flag")
                  (is (thrown? Throwable (process/flag nil false))
                      "flag must throw on unknown flag")
                  (is (thrown? Throwable (process/flag nil true))
                      "flag must throw on unknown flag")
                  (is (thrown? Throwable (process/flag :trap-exit1 false))
                      "flag must throw on unknown flag")
                  (async/close! done))]
    (process/spawn proc-fn [] {})
    (await-completion done 300)))

(deftest ^:parallel flag-throws-when-called-not-in-process-context
  (is (thrown? Exception (process/flag :trap-exit true))
      "flag must throw when called not in process context")
  (is (thrown? Exception (process/flag :trap-exit false))
      "flag must throw when called not in process context"))

;; ====================================================================
;; (registered [])
;;   Returns a set of names of the processes that have been registered.

(deftest ^:serial registered-returns-empty-seq-when-nothing-registered
  (is (empty? (process/registered))
      "registered must return empty seq of names when nothing registered"))

(deftest ^:serial registered-returns-registered-names
  (let [n1 (uuid-keyword)
        n2 (uuid-keyword)
        n3 (uuid-keyword)
        registered #{n1 n2 n3}
        done (async/chan)
        proc-fn (defproc [_inbox] (await-completion done 300))]
    (process/spawn proc-fn [] {:register n1})
    (process/spawn proc-fn [] {:register n2})
    (process/spawn proc-fn [] {:register n3})
    (is (= registered (process/registered))
      "registered must return registered names")
    (async/close! done)))

(deftest ^:serial registered-returns-empty-seq-after-registered-terminated
  (let [proc-fn (defproc [_inbox])]
    (process/spawn proc-fn [] {:register (uuid-keyword)})
    (process/spawn proc-fn [] {:register (uuid-keyword)})
    (process/spawn proc-fn [] {:register (uuid-keyword)})
    (<!! (async/timeout 50))
    (is (empty? (process/registered))
      (str "registered must return empty seq after all registered processes"
           " had terminated"))))

;; ====================================================================
;; (link [pid])
;;   Creates a link between the calling process and another process
;;   identified by pid, if there is not such a link already. If a
;;   process attempts to create a link to itself, nothing is done.
;;   If pid does not exist and the calling process
;;   1. is trapping exits - an exit signal with reason :noproc is sent
;;   to the calling process.
;;   2. is not trapping exits - link closes process' inbox and may throw.
;;   Returns true.
;;   Throws when called not in process context, or pid is not a pid.

(deftest ^:parallel link-returns-true
  (let [pid (process/spawn (defproc [_]) [] {})
        _ (<!! (async/timeout 50))
        done (async/chan)
        proc-fn (defproc [_inbox]
                  (is (= true (process/link pid))
                      "link must return true when called on terminated process")
                  (async/close! done))]
    (process/spawn proc-fn [] {})
    (await-completion done 300))
  (let [done1 (async/chan)
        pid (process/spawn
              (defproc [_] (await-completion done1 1000)) [] {})
        done2 (async/chan)
        proc-fn (defproc [_inbox]
                  (is (= true (process/link pid))
                      "link must return true when called on alive process")
                  (async/close! done1)
                  (async/close! done2))]
    (process/spawn proc-fn [] {})
    (await-completion done2 500))
  (let [done1 (async/chan)
        pid (process/spawn
              (defproc [_] (await-completion done1 1000)) [] {})
        done2 (async/chan)
        proc-fn (defproc [_inbox]
                  (is (= true (process/link pid))
                      "link must return true when called on alive process")
                  (async/close! done1)
                  (async/close! done2))]
    (process/spawn proc-fn [] {})
    (await-completion done2 500)))

(deftest ^:parallel link-throws-when-called-not-in-process-context
  (is (thrown? Throwable (process/link (process/spawn (defproc [_]) [] {})))
      "link must throw when called not in process context")
  (let [proc-fn (defproc [_] (<! (async/timeout 100)))]
    (is (thrown? Throwable (process/link (process/spawn proc-fn [] {})))
        "link must throw when called not in process context")))

(deftest ^:parallel link-throws-when-called-with-not-a-pid
  (is (thrown? Throwable (process/link nil))
      "link must throw when called with not a pid argument")
  (is (thrown? Throwable (process/link 1))
      "link must throw when called with not a pid argument")
  (is (thrown? Throwable (process/link "pid1"))
      "link must throw when called with not a pid argument")
  (is (thrown? Throwable (process/link '()))
      "link must throw when called with not a pid argument")
  (is (thrown? Throwable (process/link []))
      "link must throw when called with not a pid argument")
  (is (thrown? Throwable (process/link {}))
      "link must throw when called with not a pid argument")
  (is (thrown? Throwable (process/link #{}))
      "link must throw when called with not a pid argument"))

(deftest ^:parallel link-creates-link-with-alive-process-not-trapping-exits
  (let [done1 (async/chan)
        done2 (async/chan)
        proc-fn2 (defproc [inbox]
                   (is (= :inbox-closed (<! (await-message inbox 100)))
                       (str "process must exit when linked process exits"
                            " with reason other than :normal"))
                   (async/close! done2))
        pid2 (process/spawn proc-fn2 [] {})
        proc-fn1 (defproc [inbox]
                   (process/link pid2)
                   (async/close! done1)
                   (is (= :inbox-closed (<! (await-message inbox 100)))
                       "exit must close inbox of process not trapping exits"))
        pid1 (process/spawn proc-fn1 [] {})]
    (await-completion done1 100)
    (process/exit pid1 :abnormal)
    (await-completion done2 300)))

(deftest ^:parallel link-creates-link-with-alive-process-trapping-exits
  (let [done1 (async/chan)
        done2 (async/chan)
        proc-fn2 (defproc [inbox]
                   (is (= [:exit-message [:reason :abnormal]]
                          (<! (await-message inbox 100)))
                       (str "process trapping exits must get exit message"
                            " when linked process exits with reason"
                            " other than :normal"))
                   (async/close! done2))
        pid2 (process/spawn proc-fn2 [] {:flags {:trap-exit true}})
        proc-fn1 (defproc [inbox]
                   (process/link pid2)
                   (async/close! done1)
                   (is (= :inbox-closed (<! (await-message inbox 100)))
                       "exit must close inbox of process not trapping exits"))
        pid1 (process/spawn proc-fn1 [] {})]
    (await-completion done1 100)
    (process/exit pid1 :abnormal)
    (await-completion done2 300)))

(deftest ^:parallel link-multiple-times-work-as-single-link
  (let [done1 (async/chan)
        done2 (async/chan)
        proc-fn2 (defproc [inbox]
                   (is (= :inbox-closed (<! (await-message inbox 100)))
                       (str "process must exit when linked process exits"
                            " with reason other than :normal"))
                   (async/close! done2))
        pid2 (process/spawn proc-fn2 [] {})
        proc-fn1 (defproc [inbox]
                   (process/link pid2)
                   (process/link pid2)
                   (process/link pid2)
                   (async/close! done1)
                   (is (= :inbox-closed (<! (await-message inbox 100)))
                       "exit must close inbox of process not trapping exits"))
        pid1 (process/spawn proc-fn1 [] {})]
    (await-completion done1 100)
    (process/exit pid1 :abnormal)
    (await-completion done2 300))
  (let [done1 (async/chan)
        done2 (async/chan)
        proc-fn2 (defproc [inbox]
                   (is (= [:exit-message [:reason :abnormal]]
                          (<! (await-message inbox 100)))
                       (str "process trapping exits must get exit message"
                            " when linked process exits with reason"
                            " other than :normal"))
                   (async/close! done2))
        pid2 (process/spawn proc-fn2 [] {:flags {:trap-exit true}})
        proc-fn1 (defproc [inbox]
                   (process/link pid2)
                   (process/link pid2)
                   (process/link pid2)
                   (async/close! done1)
                   (is (= :inbox-closed (<! (await-message inbox 100)))
                       "exit must close inbox of process not trapping exits"))
        pid1 (process/spawn proc-fn1 [] {})]
    (await-completion done1 100)
    (process/exit pid1 :abnormal)
    (await-completion done2 300)))

(deftest ^:parallel link-creates-exactly-one-link-when-called-multiple-times
  (let [done1 (async/chan)
        done2 (async/chan)
        proc-fn2 (defproc [inbox]
                   (is (= :timeout
                          (<! (await-message inbox 100)))
                       (str "process must not exit when linked process was"
                            " unlinked before exit with reason"
                            " other than :normal"))
                   (async/close! done2))
        pid2 (process/spawn proc-fn2 [] {:flags {:trap-exit true}})
        proc-fn1 (defproc [inbox]
                   (process/link pid2)
                   (process/link pid2)
                   (process/link pid2)
                   (process/unlink pid2)
                   (async/close! done1)
                   (is (= :inbox-closed (<! (await-message inbox 100)))
                       "exit must close inbox of process not trapping exits"))
        pid1 (process/spawn proc-fn1 [] {})]
    (await-completion done1 100)
    (process/exit pid1 :abnormal)
    (await-completion done2 300)))

(deftest ^:parallel link-does-not-affect-processes-linked-to-normally-exited-one
  (let [done (async/chan)
        proc-fn2 (defproc [inbox]
                   (is (= :timeout
                          (<! (await-message inbox 100)))
                       (str "process must not exit when linked process exits"
                            " with reason :normal"))
                   (async/close! done))
        pid2 (process/spawn proc-fn2 [] {})
        proc-fn1 (defproc [inbox]
                   (process/link pid2)
                   :normal)
        pid1 (process/spawn proc-fn1 [] {})]
    (await-completion done 200)))

(deftest ^:parallel linking-to-terminated-process-sends-exit-message
  (let [done (async/chan)
        proc-fn2 (defproc [inbox] :normal)
        pid2 (process/spawn proc-fn2 [] {})
        _ (<!! (async/timeout 50))
        proc-fn1 (defproc [inbox]
                   (try
                     (process/link pid2)
                     (is (= [:exit-message [:reason :noproc]]
                            (<! (await-message inbox 50)))
                         (str "linking to terminated process must either"
                              " throw or send exit message to process"
                              " trapping exits"))
                     (catch Throwable t :ok))
                   (async/close! done))
        pid1 (process/spawn proc-fn1 [] {:flags {:trap-exit true}})]
    (await-completion done 200)))

(deftest ^:parallel linking-to-terminated-process-causes-exit
  (let [done (async/chan)
        proc-fn2 (defproc [inbox] :normal)
        pid2 (process/spawn proc-fn2 [] {})
        _ (<!! (async/timeout 50))
        proc-fn1 (defproc [inbox]
                   (try
                     (process/link pid2)
                     (is (= :inbox-closed (<! (await-message inbox 50)))
                         (str "linking to terminated process must either"
                              " throw or close inbox of process"
                              " not trapping exits"))
                     (catch Throwable t :ok))
                   (async/close! done))
        pid1 (process/spawn proc-fn1 [] {})]
    (await-completion done 200)))

(deftest ^:parallel link-to-self-does-not-throw
  (let [done (async/chan)
        proc-fn (defproc [_]
                  (is (try
                        (process/link (process/self))
                        true
                        (catch Throwable t
                          (.printStackTrace t)
                          false))
                      "link to self must not throw when process is alive")
                  (async/close! done))]
    (process/spawn proc-fn [] {})
    (await-completion done 200)))

;; ====================================================================
;; (unlink [pid])
;;   Removes the link, if there is one, between the calling process and
;;   the process referred to by pid.
;;   Returns true.
;;   Does not fail if there is no link to pid, if pid is self pid, or
;;   if pid does not exist.
;;   Once unlink has returned, it is guaranteed that the link between
;;   the caller and the entity referred to by pid has no effect on the
;;   caller in the future (unless the link is setup again).
;;   If the caller is trapping exits, an [:EXIT pid _] message from
;;   the link can have been placed in the caller's message queue before
;;   the call.
;;   Notice that the [:EXIT pid _] message can be the result of the
;;   link, but can also be the result of pid calling exit. Therefore,
;;   it can be appropriate to clean up the message queue when trapping
;;   exits after the call to unlink.
;;   Throws when called not in process context, or pid is not a pid.

(deftest ^:parallel unlink-removes-link-to-alive-process
  (let [done (async/chan)
        done1 (async/chan)
        proc-fn1 (defproc [_inbox]
                   (is (await-completion done1 200) "test failed")
                   (throw (Exception.
                            (str "TEST: terminating abnormally to test"
                                 " unlink removes link to alive process"))))
        pid (process/spawn proc-fn1 [] {})
        proc-fn2 (defproc [inbox]
                   (process/link pid)
                   (<! (async/timeout 50))
                   (process/unlink pid)
                   (<! (async/timeout 50))
                   (async/close! done1)
                   (is (= :timeout (<! (await-message inbox 100)))
                       (str "abnormally failed unlinked process must"
                            " not affect previously linked process"))
                   (async/close! done))]
    (process/spawn proc-fn2 [] {})
    (await-completion done 300)))

(deftest ^:parallel unlink-returns-true-if-link-exists
  (let [done (async/chan)
        done1 (async/chan)
        proc-fn1 (defproc [_inbox]
                   (is (await-completion done1 100) "test failed"))
        pid (process/spawn proc-fn1 [] {})
        proc-fn2 (defproc [_inbox]
                   (process/link pid)
                   (<! (async/timeout 50))
                   (is (= true (process/unlink pid)) "unlink must return true")
                   (async/close! done1)
                   (async/close! done))]
    (process/spawn proc-fn2 [] {})
    (await-completion done 200)))

(deftest ^:parallel unlink-returns-true-there-is-no-link
  (let [done (async/chan)
        done1 (async/chan)
        proc-fn1 (defproc [_inbox]
                   (is (await-completion done1 100) "test failed"))
        pid (process/spawn proc-fn1 [] {})
        proc-fn2 (defproc [_inbox]
                   (is (= true (process/unlink pid)) "unlink must return true")
                   (async/close! done1)
                   (async/close! done))]
    (process/spawn proc-fn2 [] {})
    (await-completion done 200)))

(deftest ^:parallel unlink-self-returns-true
  (let [done (async/chan)
        proc-fn (defproc [_inbox]
                   (is (= true (process/unlink (process/self)))
                       "unlink must return true")
                   (async/close! done))]
    (process/spawn proc-fn [] {})
    (await-completion done 200)))

(deftest ^:parallel unlink-terminated-process-returns-true
  (let [done (async/chan)
        pid (process/spawn (defproc [_inbox]) [] {})
        proc-fn2 (defproc [inbox]
                   (<! (async/timeout 100))
                   (is (= true (process/unlink pid)) "unlink must return true")
                   (async/close! done))]
    (process/spawn proc-fn2 [] {})
    (await-completion done 200))
  (let [done (async/chan)
        done1 (async/chan)
        proc-fn1 (defproc [_inbox]
                   (is (await-completion done1 100) "test failed"))
        pid (process/spawn proc-fn1 [] {})
        proc-fn2 (defproc [_inbox]
                   (process/link pid)
                   (async/close! done1)
                   (<! (async/timeout 100))
                   (is (= true (process/unlink pid)) "unlink must return true")
                   (async/close! done))]
    (process/spawn proc-fn2 [] {:flags {:trap-exit true}})
    (await-completion done 200)))

(deftest ^:parallel unlink-throws-on-not-a-pid
  (let [done (async/chan)
        proc-fn2 (defproc [inbox]
                   (is (thrown? Throwable (process/unlink nil))
                       "unlink must throw on not a pid argument")
                   (is (thrown? Throwable (process/unlink 1))
                       "unlink must throw on not a pid argument")
                   (is (thrown? Throwable (process/unlink "pid1"))
                       "unlink must throw on not a pid argument")
                   (is (thrown? Throwable (process/unlink {}))
                       "unlink must throw on not a pid argument")
                   (is (thrown? Throwable (process/unlink #{}))
                       "unlink must throw on not a pid argument")
                   (is (thrown? Throwable (process/unlink '()))
                       "unlink must throw on not a pid argument")
                   (is (thrown? Throwable (process/unlink []))
                       "unlink must throw on not a pid argument")
                   (async/close! done))]
    (process/spawn proc-fn2 [] {})
    (await-completion done 200)))

(deftest ^:parallel unlink-throws-when-calld-not-in-process-context
  (is (thrown? Throwable
               (process/unlink (process/spawn (defproc [_inbox]) [] {})))
      "unlink must throw when called not in process context"))

(deftest ^:parallel unlink-prevents-exit-message-after-linked-process-failed
  (let [done (async/chan)
        done1 (async/chan)
        proc-fn1 (defproc [_inbox]
                   (is (await-completion done1 100) "test failed")
                   (throw (Exception.
                            (str "TEST: terminating abnormally to test unlink"
                                 " prevents exit message when previously linked"
                                 " process have exited abnormally"))))
        pid (process/spawn proc-fn1 [] {})
        proc-fn2 (defproc [inbox]
                   (process/flag :trap-exit true)
                   (process/link pid)
                   (<! (async/timeout 50))
                   (process/unlink pid)
                   (async/close! done1)
                   (is (= :timeout
                          (<! (await-message inbox 100)))
                       (str "exit message from abnormally terminated linked"
                            " process, terminated before unlink have been"
                            " called, must not appear in process' inbox after"
                            " unlink have been called"))
                   (async/close! done))]
    (process/spawn proc-fn2 [] {})
    (await-completion done 300)))

(deftest ^:parallel
  unlink-does-not-prevent-exit-message-after-it-has-been-placed-to-inbox
  (let [done (async/chan)
        done1 (async/chan)
        proc-fn1 (defproc [_inbox]
                   (is (await-completion done1 200) "test failed")
                   (process/exit (process/self) :abnormal))
        pid (process/spawn proc-fn1 [] {})
        proc-fn2 (defproc [inbox]
                   (process/flag :trap-exit true)
                   (process/link pid)
                   (<! (async/timeout 50))
                   (async/close! done1)
                   (<! (async/timeout 100))
                   (process/unlink pid)
                   (is (= [:exit-message [:reason :abnormal]]
                          (<! (await-message inbox 100)))
                       (str "exit message from abnormally terminated linked"
                            " process, terminated before unlink have been"
                            " called, must not appear in process' inbox after"
                            " unlink have been called"))
                   (async/close! done))]
    (process/spawn proc-fn2 [] {})
    (await-completion done 300)))

(deftest ^:parallel unlink-does-not-affect-process-when-called-multiple-times
  (let [done (async/chan)
        done1 (async/chan)
        proc-fn1 (defproc [inbox]
                   (is (= :timeout (<! (await-message inbox 150)))
                       "test failed")
                   (is (await-completion done1 100) "test failed")
                   (throw (Exception.
                            (str "TEST: terminating abnormally to test unlink"
                                 " doesn't affect process when called multiple"
                                 " times"))))
        pid (process/spawn proc-fn1 [] {})
        proc-fn2 (defproc [inbox]
                   (process/flag :trap-exit true)
                   (process/link pid)
                   (<! (async/timeout 50))
                   (process/unlink pid)
                   (process/unlink pid)
                   (process/unlink pid)
                   (process/unlink pid)
                   (process/unlink pid)
                   (async/close! done1)
                   (is (= :timeout
                          (<! (await-message inbox 100)))
                       (str "exit message from abnormally terminated linked"
                            " process, terminated before unlink have been"
                            " called, must not appear in process' inbox after"
                            " unlink have been called"))
                   (async/close! done))]
    (process/spawn proc-fn2 [] {})
    (await-completion done 500)))

;; ====================================================================
;; (spawn [proc-fun args options])
;;   Returns the process identifier of a new process started by the
;;   application of proc-fun to args.
;;   options argument is a map of option names (keyword) to its values.
;;   The following options are allowed:
;;     :flags - a map of process' flags (e.g. {:trap-exit true})
;;     :register - any valid name to register process
;;     :link-to - pid or sequence of pids to link process to
;;     :inbox-size -
;;     :name -

(deftest ^:parallel spawn-calls-proc-fn
  (let [done (async/chan)]
    (process/spawn (defproc [_inbox] (async/close! done)) [] {})
    (is (await-completion done 100) "spawn must call process fn")))

(deftest ^:parallel spawn-calls-proc-fn-with-arguments
  (let [done (async/chan)
        proc-fn (defproc [_inbox a b]
                  (is (and (= :a a) (= 1 b))
                      "spawn must pass process fn params")
                  (async/close! done))]
    (process/spawn proc-fn [:a 1] {})
    (await-completion done 100)))

(deftest ^:parallel spawn-returns-process-pid
  (let [done (async/chan)
        proc-fn (defproc [_inbox] (async/put! done (process/self)))
        pid1 (process/spawn proc-fn [] {})
        timeout (async/timeout 100)
        pid2 (match (async/alts!! [done timeout])
               [nil timeout] :timeout
               [pid done] pid)]
    (is (= pid1 pid2) (str "spawn must return the same pid as returned"
                           " by self called from started process"))
    (async/close! done)))

(deftest ^:parallel spawn-throws-on-illegal-arguments
  (is (thrown? Throwable (process/spawn nil [] {}))
      "spawn must throw if proc-fn is not a function")
  (is (thrown? Throwable (process/spawn 1 [] {}))
      "spawn must throw if proc-fn is not a function")
  (is (thrown? Throwable (process/spawn "fn" [] {}))
      "spawn must throw if proc-fn is not a function")
  (is (thrown? Throwable (process/spawn {} [] {}))
      "spawn must throw if proc-fn is not a function")
  (is (thrown? Throwable (process/spawn [] [] {}))
      "spawn must throw if proc-fn is not a function")
  (is (thrown? Throwable (process/spawn #{} [] {}))
      "spawn must throw if proc-fn is not a function")
  (is (thrown? Throwable (process/spawn '() [] {}))
      "spawn must throw if proc-fn is not a function")
  (is (thrown? Throwable (process/spawn (defproc []) [] {}))
      "spawn must throw if proc-fn's arity is less than 1")
  (is (thrown? Throwable (process/spawn (fn [_inbox a b]) [1] {}))
      "spawn must throw if proc-fn's arity doesn't match args")
  (is (thrown? Throwable (process/spawn (fn [_inbox]) [1 :a] {}))
      "spawn must throw if proc-fn's arity doesn't match args")
  (is (thrown? Throwable (process/spawn (fn [_inbox]) [] {}))
      "spawn must throw if proc-fn doesn't return ReadPort")
  (is (thrown? Throwable (process/spawn (defproc [_]) 1 {}))
      "spawn must throw if args is not sequential")
  (is (thrown? Throwable (process/spawn (defproc [_]) #{} {}))
      "spawn must throw if args is not sequential")
  (is (thrown? Throwable (process/spawn (defproc [_]) {} {}))
      "spawn must throw if args is not sequential")
  (is (thrown? Throwable (process/spawn (defproc [_]) 1 {}))
      "spawn must throw if args is not sequential")
  (is (thrown? Throwable (process/spawn (defproc [_]) "args" {}))
      "spawn must throw if args is not sequential")
  (is (thrown? Throwable (process/spawn (defproc [_]) (fn []) {}))
      "spawn must throw if args is not sequential")
  (is (thrown? Throwable (process/spawn (defproc [_]) [] nil))
      "spawn must throw if options is not a map")
  (is (thrown? Throwable (process/spawn (defproc [_]) [] 1))
      "spawn must throw if options is not a map")
  (is (thrown? Throwable (process/spawn (defproc [_]) [] "opts"))
      "spawn must throw if options is not a map")
  (is (thrown? Throwable (process/spawn (defproc [_]) [] [1 2 3]))
      "spawn must throw if options is not a map")
  (is (thrown? Throwable (process/spawn (defproc [_]) [] '(1)))
      "spawn must throw if options is not a map")
  (is (thrown? Throwable (process/spawn (defproc [_]) [] #{}))
      "spawn must throw if options is not a map")
  (is (thrown? Throwable (process/spawn (defproc [_]) [] {:flags 1}))
      "spawn must throw if :flags option is not a map")
  (is (thrown? Throwable (process/spawn (defproc [_]) [] {:flags true}))
      "spawn must throw if :flags option is not a map")
  (is (thrown? Throwable (process/spawn (defproc [_]) [] {:flags "str"}))
      "spawn must throw if :flags option is not a map")
  (is (thrown? Throwable (process/spawn (defproc [_]) [] {:flags []}))
      "spawn must throw if :flags option is not a map")
  (is (thrown? Throwable (process/spawn (defproc [_]) [] {:flags #{}}))
      "spawn must throw if :flags option is not a map")
  (is (thrown? Throwable (process/spawn (defproc [_]) [] {:flags '()}))
      "spawn must throw if :flags option is not a map")
  (is (thrown? Throwable
               (process/spawn (defproc [_]) [] {:link-to 1}))
      "spawn must throw if :link-to option is not a pid or collection of pids")
  (is (thrown? Throwable
               (process/spawn (defproc [_]) [] {:link-to [1]}))
      "spawn must throw if :link-to option is not a pid or collection of pids")
  (is (thrown? Throwable (process/spawn (defproc [_]) [] {:inbox-size -1}))
      "spawn must throw if :inbox-size option is not a non-negative integer")
  (is (thrown? Throwable (process/spawn (defproc [_]) [] {:inbox-size 1.1}))
      "spawn must throw if :inbox-size option is not a non-negative integer")
  (is (thrown? Throwable (process/spawn (defproc [_]) [] {:inbox-size []}))
      "spawn must throw if :inbox-size option is not a non-negative integer"))

(deftest ^:parallel spawn-throws-if-proc-fn-throws
  (is (thrown? InterruptedException
               (process/spawn (fn [_] (throw (InterruptedException.))) [] {}))
      "spawn must throw when proc-fn throws")
  (is (thrown? RuntimeException
               (process/spawn (fn [_] (throw (RuntimeException.))) [] {}))
      "spawn must throw when proc-fn throws")
  (is (thrown? Error (process/spawn (fn [_] (throw (Error.))) [] {}))
      "spawn must throw when proc-fn throws"))

(deftest ^:parallel spawn-passes-opened-read-port-to-proc-fn-as-inbox
  (let [done (async/chan)
        proc-fn (defproc [inbox]
                  (is (= :timeout (<! (await-message inbox 100)))
                      "spawn must pass opened ReadPort as inbox to process fn")
                  (async/close! done))]
    (process/spawn proc-fn [] {})
    (await-completion done 200)))

(deftest ^:parallel spawned-process-is-reachable
  (let [done (async/chan)
        proc-fn (defproc [inbox]
                  (is (= [:message :msg] (<! (await-message inbox 100)))
                      (str "messages sent to spawned process must appear"
                           " in its inbox"))
                  (async/close! done))
        pid (process/spawn proc-fn [] {})]
    (! pid :msg)
    (await-completion done 200)))

; options

(deftest ^:parallel spawned-process-traps-exits-according-options
  (let [done (async/chan)
        proc-fn (defproc [inbox]
                  (is (= :inbox-closed (<! (await-message inbox 100)))
                      "process spawned with no options must not trap exits")
                  (async/close! done))
        pid (process/spawn proc-fn [] {})]
    (process/exit pid :abnormal)
    (await-completion done 200))
  (let [done (async/chan)
        proc-fn (defproc [inbox]
                  (is (= [:exit-message [:reason :abnormal]]
                         (<! (await-message inbox 100)))
                      (str "process spawned option :trap-exit set to true"
                           " must trap exits"))
                  (async/close! done))
        pid (process/spawn proc-fn [] {:flags {:trap-exit true}})]
    (process/exit pid :abnormal)
    (await-completion done 200)))

(deftest ^:parallel spawned-process-linked-according-options
  (let [done (async/chan)
        done1 (async/chan)
        proc-fn (defproc [_inbox]
                  (is (await-completion done1 100) "test failed")
                  (throw (Exception. "TEST")))
        pid (process/spawn proc-fn [] {})
        proc-fn1 (defproc [inbox]
                   (async/close! done1)
                   (is (= :inbox-closed (<! (await-message inbox 200)))
                       (str "process spawned with option :link-to must exit"
                            " when linked process exited"))
                   (async/close! done))]
    (process/spawn proc-fn1 [] {:link-to pid})
    (await-completion done 300))
  (let [done (async/chan)
        proc-fn (defproc [inbox]
                  (is (= :inbox-closed (<! (await-message inbox 100)))
                      "test failed"))
        pid (process/spawn proc-fn [] {})
        proc-fn1 (defproc [inbox]
                   (is (= [:exit-message [:reason :abnormal]]
                          (<! (await-message inbox 200)))
                       (str "process trapping exits and spawned with option"
                            " :link-to must receive exit message when linked"
                            " process exited"))
                   (async/close! done))]
    (process/spawn proc-fn1 [] {:link-to pid :flags {:trap-exit true}})
    (<!! (async/timeout 50))
    (process/exit pid :abnormal)
    (await-completion done 300)))

(deftest ^:parallel spawned-process-registered-according-options
  (let [reg-name (uuid-keyword)
        done (async/chan)
        proc-fn (defproc [_] (is (await-completion done 100) "test failed"))]
    (is (not ((process/registered) reg-name)) "test failed")
    (process/spawn proc-fn [] {:register reg-name})
    (is ((process/registered) reg-name)
        "spawn must register proces when called with :register option")
    (async/close! done)))

(deftest ^:parallel spawn-throws-when-reg-name-already-registered
  (let [reg-name (uuid-keyword)
        done (async/chan)
        proc-fn (defproc [_] (is (await-completion done 100) "test failed"))]
    (process/spawn proc-fn [] {:register reg-name})
    (is (thrown? Throwable
                 (process/spawn (defproc [_]) [] {:register reg-name}))
        "spawn must throw when name to register is already registered")
    (async/close! done)))

(deftest ^:parallel spawned-process-does-not-trap-exits-by-default
  (let [done (async/chan)
        proc-fn (defproc [inbox]
                   (is (= :inbox-closed (<! (await-message inbox 100)))
                       (str "process' inbox must be closed after exit with"
                            " reason other than :normal was called if process"
                            " doesn't trap exits"))
                   (async/close! done))
        pid (process/spawn proc-fn [] {})]
    (process/exit pid :abnormal)
    (await-completion done 200)))

;; ====================================================================
;; (spawn-link [proc-fun args options])
;;   Returns the process identifier of a new process started by the
;;   application of proc-fun to args. A link is created between the
;;   calling process and the new process, atomically. Otherwise works
;;   like spawn/3.
;;   Throws when called not in process context.

(deftest ^:parallel spawn-link-links-to-spawned-process
  (let [done (async/chan)
        proc-fn (defproc [inbox]
                  (process/exit (process/self) :abnormal)
                  (is (= :inbox-closed (<! (await-message inbox 100)))))
        proc-fn1 (defproc [inbox]
                   (process/spawn-link proc-fn [] {})
                   (is (= :inbox-closed
                          (<! (await-message inbox 200)))
                       (str "process trapping exits and spawned with option"
                            " :link-to must receive exit message when linked"
                            " process exited"))
                   (async/close! done))]
    (process/spawn proc-fn1 [] {})
    (await-completion done 300))
  (let [done (async/chan)
        proc-fn (defproc [inbox]
                  (process/exit (process/self) :abnormal)
                  (is (= :inbox-closed (<! (await-message inbox 100)))
                      "test failed"))
        proc-fn1 (defproc [inbox]
                   (process/spawn-link proc-fn [] {})
                   (is (=  [:exit-message [:reason :abnormal]]
                          (<! (await-message inbox 200)))
                       (str "process trapping exits and spawned with option"
                            " :link-to must receive exit message when linked"
                            " process exited"))
                   (async/close! done))]
    (process/spawn proc-fn1 [] {:flags {:trap-exit true}})
    (await-completion done 300)))

; TODO check if spawn-link works like spawn

;; ====================================================================
;; (monitor [pid])
;;   ;



;; ====================================================================
;; (demonitor [pid])
;;   ;



; -----------

#_(deftest spawn-terminate-normal []
  (let [result (trace/trace-collector [:p1])]
    (process/spawn
      (fn [inbox p1 p2 & other]
        (is (process/pid? (process/self)) "self must be instance of Pid")
        (is (satisfies? ap/ReadPort inbox) "inbox must be a ReadPort")
        (is (and (= p1 :p1) (= p2 :p2) "formal parameters must match actuals"))
        (is (= (count other) 0) "no extra parameters")
        :normal)
      [:p1 :p2]
      {:name :p1})
    (let [trace (result 1000)]
      (is
        (match trace
          [[_ _ [:start _ _ _]]
           [_ _ [:return :normal]]
           [_ _ [:terminate :normal]]] true)))))

#_(deftest spawn-terminate-nil []
  (let [result (trace/trace-collector [:p1])]
    (process/spawn (fn [_inbox]) [] {:name :p1})
    (let [trace (result 1000)]
      (is
        (match trace
          [[_ _ [:start _ _ _]]
           [_ _ [:return :nil]]
           [_ _ [:terminate :nil]]] true)))))

#_(defn- link-to-normal* []
  (let [p1-fn (fn [_inbox]
                (go
                  (async/<! (async/timeout 500))
                  :blah))
        p2-fn (fn [inbox] (go (<! inbox)))
        p1 (process/spawn p1-fn [] {:name :p1})
        p2 (process/spawn p2-fn [] {:name :p2 :link-to p1})] [p1 p2]))

#_(deftest link-to-normal []
  (let [result (trace/trace-collector [:p1 :p2])
        [p1 p2] (link-to-normal*)
        trace (result 1000)]
    (is (trace/terminated? trace p1 :blah))
    (is (trace/terminated? trace p2 :blah))))

#_(defn- link-to-terminated* []
  (let [p1 (process/spawn (fn [_inbox]) [] {:name :p1})
        _  (async/<!! (async/timeout 500))
        p2 (process/spawn (fn [inbox] (go (<! inbox))) [] {:name :p2 :link-to p1})]
    [p1 p2]))

#_(deftest link-to-terminated []
  (let [result (trace/trace-collector [:p1 :p2])
        [p1 p2] (link-to-terminated*)
        trace (result 1000)]
    (is (trace/terminated? trace p1 :nil))
    (is (trace/terminated? trace p2 :noproc))))
