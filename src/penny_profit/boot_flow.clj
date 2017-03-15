(ns penny-profit.boot-flow
  (:require [boot.core :as boot, :refer [deftask]]
            [boot.util :as util]
            [clj-jgit.internal :as giti]
            [clj-jgit.porcelain :as git]
            [clj-jgit.querying :as gitq]
            [clojure.set :as set])
  (:import (java.io FileNotFoundException)
           (org.eclipse.jgit.api MergeCommand$FastForwardMode)
           (org.eclipse.jgit.lib ObjectId Ref)))

(set! *warn-on-reflection* true)

(defn feature-finish [_] identity)
(defn feature-start [_] identity)
(defn feature-switch [_] identity)

(defn clean? [repo]
  (empty? (reduce set/union (vals (git/git-status repo)))))

(defn dirty? [repo]
  (not (clean? repo)))

(defn ensure-clean [repo]
  (when (dirty? repo)
    (throw (Exception. "Please commit or stash your changes"))))

(defn list-branches [repo]
  (into #{}
        (comp (map (fn [^Ref ref]
                     (nth (re-matches #"refs/heads/(.*)"
                                      (.getName ref))
                          1)))
              (filter some?))
        (git/git-branch-list repo)))

(deftask init []
  (boot/with-pass-thru _
    (let [repo     (try (git/load-repo ".")
                        (catch FileNotFoundException _
                          (util/info "Initializing Git repo...%n")
                          (git/git-init)))
          _        (when (empty? (gitq/rev-list repo))
                     (util/info "Creating initial commit...%n")
                     (git/git-commit repo "Initial commit"))
          branches (list-branches repo)]
      (when-not (contains? branches "master")
        (util/info "Creating master branch...")
        (git/git-branch-create repo "master"))
      (when-not (contains? branches "develop")
        (util/info "Creating develop branch...")
        (git/git-branch-create repo "develop")))))

(deftask feature [n name NAME str "feature to switch to"]
  (fn [handler]
    (fn [fileset]
      (let [repo (git/load-repo ".")]
        (ensure-clean repo)
        (let [branches (list-branches repo)
              features (into #{}
                             (filter #(re-matches #"feature/.*" %))
                             branches)
              branch   (cond
                         name                   (str "feature/" name)
                         (= (count features) 1) (first features))
              name     (or name (nth (re-matches #"feature/(.*)" branch) 1))]
          (cond
            (nil? branch)
            (throw (Exception. "Please specify feature name"))

            (contains? branches branch)
            (do (util/info "Resuming feature: %s...%n" name)
                (git/git-checkout repo branch)
                (((feature-start branch) handler) fileset))

            :else
            (do (util/info "Beginning feature: %s...%n" name)
                (git/git-checkout repo branch true false "develop")
                (((feature-switch branch) handler) fileset))))))))

(deftask finish []
  (fn [handler]
    (fn [fileset]
      (let [repo (git/load-repo ".")]
        (ensure-clean repo)
        (let [branch        (git/git-branch-current repo)
              [_ type name] (re-matches #"(feature)/(.*)" branch)]
          (util/info "Finishing %s: %s...%n" type name)
          (case type
            "feature"
            (do (git/git-checkout repo "develop")
                (.. repo
                    merge
                    (include ^ObjectId (giti/resolve-object branch repo))
                    (setFastForward MergeCommand$FastForwardMode/NO_FF)
                    (setMessage (str "Merge branch '" branch "' into develop"))
                    call)
                (git/git-branch-delete repo [branch])
                (((feature-finish branch) handler) fileset))))))))
