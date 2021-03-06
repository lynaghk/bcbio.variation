(ns bcbio.variation.filter.intervals
  "Combined interval lists from filtered variants prepared via multiple calls.
  Multiple call approaches and technologies result in reduced call regions due
  to coverage. These functions manage creation of reduced BED files."
  (:import [org.broadinstitute.sting.utils.interval IntervalUtils
            IntervalMergingRule IntervalSetRule]
           [org.broadinstitute.sting.utils GenomeLocParser])
  (:use [clojure.java.io]
        [clojure.set :only [intersection]]
        [bcbio.align.ref :only [get-seq-dict]]
        [bcbio.variation.callable :only [get-callable-bed get-bed-iterator]])
  (:require [bcbio.run.itx :as itx]))

(defn- bed-to-intervals
  [bed-file ref-file loc-parser]
  (with-open [bed-iter (get-bed-iterator bed-file ref-file)]
    (doall (map #(.createGenomeLoc loc-parser %) bed-iter))))

(defn- intersect-by-contig
  "Intersect a group of intervals present on a contig."
  [start-intervals]
  (loop [final []
         intervals start-intervals]
    (if (empty? intervals)
      final
      (recur (IntervalUtils/mergeListsBySetOperator final (first intervals)
                                                    IntervalSetRule/INTERSECTION)
             (rest intervals)))))

(defn intersection-of-bed-files
  "Generate list of intervals that intersect in all provided BED files."
  [all-beds ref loc-parser]
  (letfn [(intervals-by-chrom [bed-file]
            (group-by #(.getContig %) (bed-to-intervals bed-file ref loc-parser)))
          (get-by-contig [interval-groups contig]
            (map #(get % contig []) interval-groups))]
    (let [interval-groups (map intervals-by-chrom all-beds)
          contigs (vec (apply intersection (map #(set (keys %)) interval-groups)))]
      (mapcat #(intersect-by-contig (get-by-contig interval-groups %))
              contigs))))

(defn combine-multiple-intervals
  "Combine intervals from an initial BED and coverage BAM files."
  [initial-bed align-bams ref & {:keys [out-dir name]}]
  (let [all-beds (cons initial-bed (map #(get-callable-bed % ref :out-dir out-dir
                                                           :intervals initial-bed)
                                        align-bams))
        loc-parser (GenomeLocParser. (get-seq-dict ref))
        out-file (itx/add-file-part initial-bed
                                    (str (if name (str name "-") "") "multicombine")
                                    out-dir)]
    (when (itx/needs-run? out-file)
      (with-open [wtr (writer out-file)]
        (doseq [x (IntervalUtils/sortAndMergeIntervals
                   loc-parser (intersection-of-bed-files all-beds ref loc-parser)
                   IntervalMergingRule/ALL)]
          (.write wtr (format "%s\t%s\t%s\n" (.getContig x) (dec (.getStart x)) (.getStop x))))))
    out-file))

(defn pipeline-combine-intervals
  "Combine multiple intervals as part of processing and filtering pipeline."
  [exp config]
  (let [base-intervals (:intervals exp)
        all-aligns (set (remove nil? (map :align (cons exp (:calls exp)))))]
    (when (and base-intervals (seq all-aligns))
      (combine-multiple-intervals base-intervals all-aligns
                                  (:ref exp)
                                  :name (:sample exp)
                                  :out-dir (get-in config [:dir :prep] (get-in config [:dir :out]))))))