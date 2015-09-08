(ns comportexviz.plots
  (:require [reagent.core :as reagent :refer [atom]]
            [monet.canvas :as c]
            [comportexviz.plots-canvas :as plt]
            [comportexviz.helpers :refer [canvas resizing-canvas
                                          window-resize-listener]]
            [comportexviz.bridge.channel-proxy :as channel-proxy]
            [comportexviz.selection :as sel]
            [org.nfrac.comportex.core :as core]
            [org.nfrac.comportex.util :as util :refer [round]]
            [clojure.string :as str]
            [goog.dom :as dom]
            [cljs.core.async :as async :refer [chan put! <!]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(defprotocol PCompressible
  (compress [this factor]))

(defprotocol PBucketed
  (buckets [this])
  (bucket-size [this]))

(defprotocol PCapped
  (max-count [this]))

(deftype SequenceCompressor [bucket-size* fcompress xs unfilled-bucket]
  PBucketed
  (bucket-size [_]
    bucket-size*)
  (buckets [_]
    (vec xs))

  PCompressible
  (compress [_ factor]
    (SequenceCompressor. (* factor bucket-size*) fcompress
                         (->> xs
                              (partition factor)
                              (mapv (partial apply fcompress)))
                         unfilled-bucket))

  ICollection
  (-conj [_ x]
    (let [bucket (conj unfilled-bucket x)]
      (if (< (count bucket) bucket-size*)
        (SequenceCompressor. bucket-size* fcompress xs bucket)
        (SequenceCompressor. bucket-size* fcompress
                             (conj xs (apply fcompress bucket)) (empty xs)))))

  ICounted
  (-count [_]
    (count xs))

  ISeqable
  (-seq [_]
    (seq xs)))

(deftype SequenceCompressorCapped [max-bucket-count fcompress seq-compressor]
  PBucketed
  (bucket-size [_]
    (bucket-size seq-compressor))
  (buckets [_]
    (buckets seq-compressor))

  PCompressible
  (compress [_ factor]
    (SequenceCompressorCapped. max-bucket-count fcompress
                               (compress seq-compressor factor)))

  PCapped
  (max-count [_]
    max-bucket-count)

  ICollection
  (-conj [_ x]
    (let [r (SequenceCompressorCapped. max-bucket-count fcompress
                                       (conj seq-compressor x))]
      (if (< (count r) max-bucket-count)
        r
        (compress r 2))))

  ICounted
  (-count [_]
    (count seq-compressor))

  ISeqable
  (-seq [_]
    (seq seq-compressor)))

(defn sequence-compressor
  "A sequence builder that does not necessarily grow on `conj`.
  Individual items are the result of compressing buckets of values
  into a single value. Compress the sequence or check its bucket size
  via methods. Specify a `max-bucket-count` to make it automatically
  recompress as it grows.

  Extract the compressed sequence via `seq` or `buckets`.

  `fcompress` must take an arbitrary number of args and must return a
  value of the same format, i.e. a value that will later be used as a
  fcompress arg."
  ([fcompress]
   (SequenceCompressor. 1 fcompress [] []))
  ([max-bucket-count fcompress]
   (SequenceCompressorCapped. max-bucket-count fcompress
                              (sequence-compressor fcompress))))

(defn mean
  [xs]
  (/ (apply + xs) (count xs)))

(defn aggregate-by
  [f maps]
  (let [ks (keys (first maps))]
    (->>
     (for [k ks]
       [k (f (map #(get % k) maps))])
     (into {}))))

(defn stacked-ts-plot
  [ctx col-state-freqs-log series-keys series-colors]
  (let [bucket-size (bucket-size col-state-freqs-log)
        buckets (buckets col-state-freqs-log)
        n-timesteps (* bucket-size (max-count col-state-freqs-log))
        ncol (:size (peek buckets))
        v-max (* ncol 0.06)
        cnv (.-canvas ctx)
        plot-size {:w (- (.-width cnv) 25)
                   :h (- (.-height cnv) 18)}
        plot (plt/xy-plot ctx plot-size
                          [0 n-timesteps]
                          [v-max 0])
        ]

    (c/clear-rect ctx {:x 0 :y 0 :w (.-width cnv) :h (.-height cnv)})
    (c/stroke-width ctx 0)
    (doseq [[i x] (plt/indexed buckets)]
      (reduce (fn [from-y k]
                (let [val (get x k)]
                  (c/fill-style ctx (series-colors k))
                  (plt/rect! plot (* i bucket-size) from-y
                             bucket-size val)
                  (+ from-y val)))
              0 series-keys))
    (plt/frame! plot)
    (c/fill-style ctx "black")
    (c/stroke-style ctx "black")
    (c/stroke-width ctx 1)
    ;; draw x labels
    (c/text-baseline ctx :top)
    (doseq [x (range 0 (inc n-timesteps)
                     (/ n-timesteps 8))
            :let [[xpx ypx] (plt/->px plot x 0)]]
      (doto ctx
        (c/begin-path)
        (c/move-to xpx ypx)
        (c/line-to xpx (+ ypx 5))
        (c/stroke))
      (c/text ctx {:x xpx
                   :y (+ ypx 5)
                   :text x}))
    ;; draw y labels
    (c/text-baseline ctx :middle)
    (let [labx n-timesteps]
      (doseq [f [0 0.02 0.04]
              :let [y (* ncol f)
                    [xpx ypx] (plt/->px plot labx y)]]
        (doto ctx
          (c/begin-path)
          (c/move-to xpx ypx)
          (c/line-to (+ xpx 5) ypx)
          (c/stroke))
        (c/text ctx {:x (+ xpx 10)
                     :y ypx
                     :text y})))
    ))

(defn empty-col-state-freqs-log
  []
  (sequence-compressor 200
                       (fn [& col-state-freqs-seq]
                         (aggregate-by mean col-state-freqs-seq))))

(defn ts-freqs-plot-cmp
  [_ _]
  (let [size-invalidates-c (async/chan)]
    (fn [col-state-freqs-log series-colors]
      [:div nil
       [window-resize-listener size-invalidates-c]
       [resizing-canvas
        {:style {:width "100%"
                 :height 180}}
        []
        (fn [ctx]
          (stacked-ts-plot ctx col-state-freqs-log
                           [:active :active-predicted :predicted]
                           series-colors))
        size-invalidates-c]])))

(def excitation-colors
  {:proximal-unstable :active
   :proximal-stable :active-predicted
   :boost :highlight
   :temporal-pooling :temporal-pooling
   :distal :predicted
   })

(def excitation-order
  [:proximal-unstable
   :proximal-stable
   :boost
   :temporal-pooling
   :distal])

(defn viz-rgn-shades
  [step-template]
  (let [srcs (concat (keys (:senses @step-template))
                     (keys (:regions @step-template)))]
    (zipmap srcs (range -0.3 0.31 (/ 1.0 (count srcs))))))

(defn- abs [x] (if (neg? x) (- x) x))

(defn draw-cell-excitation-plot!
  [ctx breakdowns step-template sel-col series-colors]
  (let [width-px (.-width (.-canvas ctx))
        height-px (.-height (.-canvas ctx))
        plot-size {:w width-px
                   :h 200}

        src-shades (viz-rgn-shades step-template)
        y-max (* 1.1 (apply max (map :total (vals breakdowns))))
        x-lim [-0.5 (+ (count breakdowns) 3)] ;; space for legend
        y-lim [y-max 0]
        bot-lab-y (- (* y-max 0.02))
        draw-cell-bar
        (fn [plot x-coord bd labels?]
          (let [series (for [k excitation-order
                             :let [v (get bd k)]
                             [src x] (if (map? v)
                                       (sort-by (comp src-shades key) v)
                                       {nil v})
                             :when (and x (pos? x))]
                         [k src x])]
            (c/stroke-style ctx "black")
            (reduce (fn [offset [k src x]]
                      (let [color (excitation-colors k)
                            shade (if src (src-shades src) 0.0)]
                        (c/fill-style ctx (get series-colors color))
                        (plt/rect! plot x-coord offset 0.5 x)
                        (when-not (zero? shade)
                          (c/fill-style ctx (if (pos? shade) "white" "black"))
                          (c/alpha ctx (abs shade))
                          (plt/rect! plot x-coord offset 0.25 x)
                          (c/alpha ctx 1.0))
                        (when labels?
                          (c/fill-style ctx "black")
                          (let [labs (concat (str/split (name k) #"-")
                                             (if src [(str "(" (name src) ")")]))]
                            (plt/texts! plot (+ x-coord 0.5) (+ offset (* 0.5 x))
                                        labs 10)))
                        (+ offset (or x 0))))
                    0.0
                    series)))]
    (c/save ctx)
    (c/clear-rect ctx {:x 0 :y 0 :w width-px :h height-px})
    (let [plot (plt/xy-plot ctx plot-size x-lim y-lim)]
      (doseq [[i [cell-id bd]] (->> breakdowns
                                    (sort-by key)
                                    (sort-by (comp :total val) >)
                                    (map-indexed vector))
              :let [x-coord i
                    [col _] cell-id
                    total-exc (:total bd)]]
        (draw-cell-bar plot x-coord bd false)
        (when (= col sel-col)
          (c/fill-style ctx (:highlight series-colors))
          (plt/rect! plot (- x-coord 0.25) -100 1.0 100))
        (c/fill-style ctx "black")
        (plt/text! plot x-coord (+ total-exc 0.5) total-exc)
        (plt/text-rotated! plot x-coord bot-lab-y (if (= col sel-col) cell-id col)))
      ;; draw legend
      (let [sep-x (count breakdowns)
            leg-x (inc sep-x)
            key-bd* (->
                     (apply util/deep-merge-with + (vals breakdowns))
                     (core/update-excitation-breakdown #(if (pos? %) 1.0 0.0)))
            key-bd (core/update-excitation-breakdown key-bd* #(* % (/ y-max (:total key-bd*))))]
        (c/fill-style ctx (:background series-colors))
        (plt/rect! plot sep-x 0 (- (second x-lim) sep-x) y-max)
        (c/text-align ctx :center)
        (draw-cell-bar plot leg-x key-bd true)
        (c/fill-style ctx "black")
        (c/text-align ctx :left)
        (plt/text-rotated! plot leg-x bot-lab-y "KEY")
        (plt/frame! plot)))
    (c/restore ctx)))

(defn fetch-excitation-data!
  [excitation-data sel into-journal local-targets]
  (let [{:keys [model-id bit] :as sel1} (first (filter sel/layer sel))
        [region layer] (sel/layer sel1)]
    (if layer
      (let [response-c (async/chan)]
        (put! @into-journal
              [:get-cell-excitation-data model-id region layer bit
               (channel-proxy/register! local-targets
                                        response-c)])
        (go
          (reset! excitation-data (<! response-c))))
      (reset! excitation-data {}))))

(defn cell-excitation-plot-cmp
  [_ selection _ _ _ into-journal local-targets]
  (let [excitation-data (atom {})]
    (add-watch selection :fetch-excitation-data
               (fn [_ _ _ sel]
                 (fetch-excitation-data! excitation-data sel into-journal
                                         local-targets)))

    (fetch-excitation-data! excitation-data @selection into-journal
                            local-targets)

    (fn [step-template _ series-colors region-key layer-id _ _]
      [canvas
       {}
       300
       240
       [excitation-data]
       (fn [ctx]
         (let [sel1 (first (filter sel/layer @selection))
               [sel-rgn sel-lyr] (sel/layer sel1)
               sel-col (when (and sel-lyr
                                  (= region-key sel-rgn)
                                  (= layer-id sel-lyr))
                         (:bit sel1))]
           (draw-cell-excitation-plot! ctx @excitation-data step-template
                                       sel-col series-colors)))
       nil])))

(defn draw-cell-sdrs-plot!
  [ctx {:keys [sdr-transitions sdr-label-counts matching-sdrs sdr-sizes sdr-growth
               threshold title]}
   hide-below-count]
  (let [lc-sdrv (:learn matching-sdrs)
        ac-sdrv (:active matching-sdrs)
        pc-sdrv (:pred matching-sdrs)
        sdr-label-counts* (if (> hide-below-count 1)
                            (into {} (filter (fn [[sdr label-counts]]
                                               (or (lc-sdrv sdr)
                                                   (>= (reduce + (vals label-counts))
                                                       hide-below-count))))
                                  sdr-label-counts)
                            sdr-label-counts)
        gap threshold
        [sdr-ordinate y-max] (loop [sdrs (sort (keys sdr-label-counts*))
                                    m {}
                                    offset 0]
                               (if-let [sdr (first sdrs)]
                                 (let [dy (sdr-sizes sdr)
                                       gy (sdr-growth sdr 0)
                                       ord (+ offset gap (/ dy 2))]
                                   (recur (rest sdrs)
                                          (assoc m sdr ord)
                                          (+ offset gap dy gy)))
                                 [m offset]))
        title-px 16
        width-px (.-width (.-canvas ctx))
        full-height-px (.-height (.-canvas ctx))
        height-px (- full-height-px title-px)
        y-scale (/ height-px (max (* 50 gap) (inc y-max)))
        sdr-max-count (->> (vals sdr-label-counts*)
                           (map (fn [label-counts]
                                  (reduce + (vals label-counts))))
                           (reduce max)
                           (max 5))
        x-scale (/ width-px sdr-max-count)
        mid-x (quot width-px 2)
        label-width 50]
    (c/save ctx)
    (c/clear-rect ctx {:x 0 :y 0 :w width-px :h full-height-px})
    (c/translate ctx mid-x title-px)
    ;; draw title
    (let [title-y 0]
      (c/text-align ctx :center)
      (c/text-baseline ctx :bottom)
      (c/text ctx {:x 0
                   :y title-y
                   :text title})
      (c/text ctx {:x (* mid-x 0.8)
                   :y title-y
                   :text "forward"})
      (c/text ctx {:x (- (* mid-x 0.8))
                   :y title-y
                   :text "back"}))
    ;; draw transitions
    (c/stroke-style ctx "hsl(210,50%,50%)")
    (c/stroke-width ctx 4)
    (doseq [[from-sdr to-sdrs] sdr-transitions
            :when (contains? sdr-label-counts* from-sdr)
            :let [from-y (* y-scale (sdr-ordinate from-sdr))]
            to-sdr to-sdrs
            :when (contains? sdr-label-counts* to-sdr)
            :let [to-y (* y-scale (sdr-ordinate to-sdr))
                  mid-y (/ (+ to-y from-y) 2)
                  off-x (* 1.0 width-px
                           (/ (- to-y from-y)
                              height-px))]]
      (doto ctx
        (c/begin-path)
        (c/move-to 0 from-y)
        (c/quadratic-curve-to off-x mid-y
                              0 to-y)
        (c/stroke)))
    (c/stroke-width ctx 1)
    ;; draw states
    (c/text-baseline ctx :middle)
    (doseq [[sdr label-counts] sdr-label-counts*
            :let [y-mid (* y-scale (sdr-ordinate sdr))
                  sdr-tot-count (reduce + (vals label-counts))
                  sdr-width (* x-scale sdr-tot-count)
                  sdr-height (* y-scale (sdr-sizes sdr))
                  y-top (- y-mid (quot sdr-height 2))
                  active-color (cond
                                (ac-sdrv sdr) "#fbb"
                                (pc-sdrv sdr) "#bbf"
                                :else nil)
                  active-size (or (ac-sdrv sdr)
                                  (pc-sdrv sdr))
                  sdr-rect {:x (- (quot sdr-width 2))
                            :y y-top
                            :w sdr-width
                            :h sdr-height
                            :r 5}]]
      ;; outline/background of state
      (doto ctx
        (c/stroke-style "#aaa")
        (c/rounded-rect (update sdr-rect :r min (/ sdr-height 2)))
        (c/alpha 0.8)
        (c/fill-style "#ddd")
        (c/fill)
        (c/alpha 1.0))
      ;; activation / prediction level
      (when active-size
        (let [h (* y-scale active-size)]
          (doto ctx
            (c/alpha 0.4)
            (c/fill-style active-color)
            (c/rounded-rect (-> (assoc sdr-rect :h h)
                                (update :r min (/ h 2))))
            (c/fill)
            (c/alpha 1.0))))
      ;; growth
      (when-let [growth (sdr-growth sdr)]
        (let [h (* y-scale growth)]
          (doto ctx
            (c/alpha 0.8)
            (c/fill-style "#bfb")
            (c/rounded-rect (-> (assoc sdr-rect :h h)
                                (update :r min (/ h 2))
                                (update :y + sdr-height)))
            (c/fill)
            (c/alpha 1.0))))
      ;; outline of exact matching cells (learning cells)
      (when-let [lc-size (lc-sdrv sdr)]
        (let [h (* y-scale lc-size)]
          (doto ctx
            (c/stroke-style "#000")
            (c/rounded-rect (-> (assoc sdr-rect :h h)
                                (update :r min (/ h 2)))))))
      ;; draw labels; center label on new growth if that's all there is
      (let [gy (* y-scale (sdr-growth sdr))
            lab-y (if (pos? sdr-height)
                    y-mid
                    (+ y-mid (/ gy 2)))]
        (c/fill-style ctx "#000")
        (reduce (fn [offset [label n]]
                  (c/text ctx {:x (* x-scale (+ offset (/ n 2)))
                               :y lab-y
                               :text (str label)})
                  (+ offset n))
                (- (/ sdr-tot-count 2))
                label-counts)))
    (c/restore ctx)))

(defn fetch-transitions-data
  [sel cell-sdr-counts into-journal local-targets]
  (when-let [[region layer] (sel/layer sel)]
    (let [model-id (:model-id sel)
          response-c (async/chan)]
      (put! @into-journal [:get-transitions-data
                           model-id region layer cell-sdr-counts
                           (channel-proxy/register!
                            local-targets response-c)])
      response-c)))

(defn calc-sdr-sizes
  [cell-sdr-fracs]
  (persistent!
   (reduce (fn [m sdr-fracs]
             (reduce-kv (fn [m sdr frac]
                          (assoc! m sdr
                                  (+ (get m sdr 0)
                                     frac)))
                        m
                        sdr-fracs))
           (transient {})
           (vals cell-sdr-fracs))))

(defn update-cell-sdrs-plot-data!
  [plot-data states sel into-journal local-targets]
  (when-let [[region layer] (sel/layer sel)]
    (let [model-id (:model-id sel)]
      (when-let [state* (get-in @states [model-id [region layer]])]
        (let [state (assoc state* :title (str (name region) " " (name layer)))]
          (if-not (:sdr-transitions state)
           ;; request transitions new data
           (go
             ;; immediate update from local state while waiting:
             (reset! plot-data state)
             ;; another update when receive server data
             (let [x (<! (fetch-transitions-data sel (:cell-sdr-counts state)
                                                 into-journal local-targets))]
               (swap! states assoc-in [model-id [region layer] :sdr-transitions] x)
               (swap! plot-data assoc :sdr-transitions x)))
           ;; otherwise - we have the data
           (reset! plot-data state)))))))

(defn sdr-votes
  [cells cell-sdr-fracs]
  (->> (map cell-sdr-fracs cells)
       (apply merge-with + {})))

(defn- freqs->fracs
  [freqs]
  (let [total (reduce + (vals freqs))]
    (util/remap #(/ % total) freqs)))

(def empty-cell-sdrs-state
  {:cell-sdr-counts {}
   :sdr-label-counts {}
   :sdr-sizes {}
   :sdr-growth {}
   :matching-sdrs {:learn {}
                   :active {}
                   :pred {}}
   :threshold 0})

(defn update-cell-sdrs-states!
  [states step-template step prev-step into-journal local-targets]
  (for [[region layer-map] (:regions @step-template)
        layer (keys layer-map)
        :let [response-c (async/chan)
              model-id (:model-id step)]]
    (go
      (put! @into-journal [:get-cells-by-state model-id
                           region layer
                           (channel-proxy/register! local-targets
                                                    response-c)])
      (let [cells-by-state (<! response-c)
            state (or (get-in @states [(:model-id prev-step) [region layer]])
                      empty-cell-sdrs-state)
            lc (:learn-cells cells-by-state)
            ac (:active-cells cells-by-state)
            pc (:pred-cells cells-by-state)
            threshold (get-in @step-template [:regions region layer
                                              :spec :seg-learn-threshold])
            ;; for each cell, represents its specificity to each SDR
            cell-sdr-fracs (->> (:cell-sdr-counts state)
                                (util/remap freqs->fracs))
            ac-sdrv (sdr-votes ac cell-sdr-fracs)
            pc-sdrv (sdr-votes pc cell-sdr-fracs)
            lc-sdrv (sdr-votes lc cell-sdr-fracs)
            learn-sdrs* (keep (fn [[sdr vote]] (when (>= vote threshold) sdr))
                              lc-sdrv)
            new-sdr (when (empty? learn-sdrs*) (count (:sdr-label-counts state)))
            learn-sdrs (if new-sdr [new-sdr] learn-sdrs*)
            sdr-sizes (or (:updated-sdr-sizes state)
                          (calc-sdr-sizes cell-sdr-fracs))
            inc-learn-sdrs (partial merge-with +
                                    (zipmap learn-sdrs (repeat 1)))
            label (:label (:input-value step))
            inc-label (partial merge-with + {label (/ 1 (count learn-sdrs))})
            new-state* (-> state
                           (update :cell-sdr-counts
                                   (fn [m]
                                     (util/update-each (or m {}) lc
                                                       inc-learn-sdrs)))
                           (update :sdr-label-counts
                                   (fn [m]
                                     (reduce (fn [m learn-sdr]
                                               (update m learn-sdr
                                                       inc-label))
                                             m
                                             learn-sdrs)))
                           (assoc :sdr-transitions nil ;; updated lazily
                                  :matching-sdrs {:learn lc-sdrv
                                                  :active ac-sdrv
                                                  :pred pc-sdrv}
                                  :sdr-sizes sdr-sizes
                                  :threshold threshold))
            new-sdr-sizes (calc-sdr-sizes (->> (:cell-sdr-counts new-state*)
                                               (util/remap freqs->fracs)))
            sdr-growth (merge-with - (select-keys new-sdr-sizes learn-sdrs)
                                   (select-keys sdr-sizes learn-sdrs))
            new-state (cond-> (assoc new-state*
                                     :updated-sdr-sizes new-sdr-sizes
                                     :sdr-growth sdr-growth)
                        new-sdr (assoc-in [:matching-sdrs :learn new-sdr]
                                          (sdr-growth new-sdr)))]
        (swap! states assoc-in [model-id [region layer]] new-state)))))

(defn cell-sdrs-plot-builder
  [steps step-template selection into-journal local-targets hide-below-count]
  (let [states (atom {})
        plot-data (atom (assoc empty-cell-sdrs-state :title ""))]
    (add-watch steps ::cell-sdrs-plot
               (fn [_ _ _ [step prev-step]]
                 (when (:model-id step)
                   (let [procs (update-cell-sdrs-states! states step-template
                                                         step prev-step
                                                         into-journal local-targets)]
                     (go
                       ;; await update processes for all layers...
                       (doseq [c procs] (<! c))
                       ;; ...before refreshing plot
                       (let [[sel] @selection]
                         (update-cell-sdrs-plot-data! plot-data states sel
                                                      into-journal local-targets))
                       )))))
    (add-watch selection ::cell-sdrs-plot
               (fn [_ _ _ [sel]]
                 (update-cell-sdrs-plot-data! plot-data states sel
                                              into-journal local-targets)))
    ;; at build time, pull in existing steps starting from current selection
    (let [[sel] @selection]
      (when-let [model-id (:model-id sel)]
        (let [to-ingest (->> (reverse @steps)
                             (drop-while #(not= model-id (:model-id %))))]
          (go
            (doseq [[prev-step step] (partition 2 1 (cons nil to-ingest))]
              (println "cell-sdrs plot ingesting" (:model-id step))
              (let [procs (update-cell-sdrs-states! states step-template
                                                    step prev-step
                                                    into-journal local-targets)]
                ;; await update processes before going on to next time step
                (doseq [c procs] (<! c))))
            ;; finally, redraw
            (update-cell-sdrs-plot-data! plot-data states sel
                                         into-journal local-targets)))))
    {:content
     (let [size-invalidates-c (async/chan)]
       (fn []
         [:div nil
          [window-resize-listener size-invalidates-c]
          [resizing-canvas
           {:style {:width "100%"
                    :height "100vh"}}
           [plot-data
            hide-below-count]
           (fn [ctx]
             (draw-cell-sdrs-plot! ctx @plot-data @hide-below-count))
           size-invalidates-c]]))
     :teardown
     (fn []
       (remove-watch steps ::cell-sdrs-plot)
       (remove-watch selection ::cell-sdrs-plot))}))
