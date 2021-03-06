(ns clojure-lsp.parser
  (:require
   [clojure-lsp.clojure-core :as cc]
   [clojure-lsp.db :as db]
   [clojure-lsp.queries :as queries]
   [clojure-lsp.refactor.edit :as edit]
   [clojure.set :as set]
   [clojure.spec.alpha :as s]
   [clojure.string :as string]
   [clojure.tools.logging :as log]
   [clojure.walk :as walk]
   [rewrite-clj.node :as n]
   [rewrite-clj.zip :as z]
   [rewrite-clj.custom-zipper.core :as cz]
   [rewrite-clj.zip.edit :as ze]
   [rewrite-clj.zip.find :as zf]
   [rewrite-clj.zip.move :as zm]
   [rewrite-clj.zip.walk :as zw]
   [rewrite-clj.zip.subedit :as zsub])
  (:import
    (rewrite_clj.node.meta MetaNode)))

(declare find-references*)
(declare parse-destructuring)

(def core-refers
  (->> cc/core-syms
       (mapv (juxt identity (constantly 'clojure.core)))
       (into {})))

(def lang-imports
  (->> cc/java-lang-syms
       (mapv (juxt identity #(symbol (str "java.lang." (name %)))))
       (into {})))

(def default-env
  {:ns 'user
   :requires #{'clojure.core}
   :refer-all-syms {}
   :refers {}
   :imports {}
   :aliases {}
   :publics #{}
   :locals #{}
   :usages []
   :ignored? false})

(defmacro zspy [loc]
  `(do
     (log/warn '~loc (pr-str (z/sexpr ~loc)))
     ~loc))

(defn z-next-sexpr [loc]
  (z/find-next loc z/next #(not (n/printable-only? (z/node %)))))

(defn z-right-sexpr [loc]
  (z/find-next loc z/right #(not (n/printable-only? (z/node %)))))

(defn ident-split [ident-str]
  (let [ident-conformed (some-> ident-str (string/replace ":" ""))
        prefix (string/replace ident-str #"^(::?)?.*" "$1")
        idx (string/index-of ident-conformed "/")]
    (if (and idx (not= idx (dec (count ident-conformed))))
      (into [prefix] (string/split ident-conformed #"/" 2))
      [prefix nil ident-conformed])))

(defn qualify-ident [ident-node {:keys [usages aliases imports locals publics refers requires refer-all-syms file-type] :as context} scoped declaration?]
  (when (ident? (n/sexpr ident-node))
    (let [ident (n/sexpr ident-node)
          ident-str (loop [result ident-node]
                      (if (instance? MetaNode result)
                        (->> result
                             (n/children)
                             (remove n/printable-only?)
                             (second)
                             recur)
                        (n/string result)))
          [prefix ident-ns-str ident-name] (ident-split ident-str)
          ident-ns (some-> ident-ns-str symbol)
          java-sym (symbol (string/replace ident-name #"\.$" ""))
          class-sym (get imports java-sym)
          alias-ns (get aliases ident-ns-str)
          ns-sym (:ns context)
          declared (when (and ns-sym (or (get locals ident-name) (get publics ident-name)))
                              (symbol (name ns-sym) ident-name))
          refered (get refers ident-name)
          required-ns (get requires ident-ns)
          ctr (if (symbol? ident) symbol keyword)]
      (assoc
        (if-not ident-ns
          (cond
            declaration? {:sym (ctr (name (or ns-sym 'user)) ident-name)}
            (and (keyword? ident) (= prefix "::")) {:sym (ctr (name ns-sym) ident-name)}
            (keyword? ident) {:sym ident}
            (contains? scoped ident) {:sym (ctr (name (get-in scoped [ident :ns])) ident-name)}
            declared {:sym declared}
            refered {:sym refered}
            (contains? refer-all-syms ident) {:sym (get refer-all-syms ident)}
            (contains? imports java-sym) {:sym (get imports java-sym) :tags #{:norename}}
            (contains? core-refers ident) {:sym (ctr (name (get core-refers ident)) ident-name) :tags #{:norename}}
            (contains? lang-imports java-sym) {:sym (get lang-imports java-sym) :tags #{:norename}}
            (string/starts-with? ident-name ".") {:sym ident :tags #{:method :norename}}
            class-sym {:sym class-sym :tags #{:norename}}
            :else {:sym (ctr (name (gensym)) ident-name) :tags #{:unknown}})
          (cond
            (and alias-ns
                 (or
                   (and (keyword? ident) (= prefix "::"))
                   (symbol? ident)))
            {:sym (ctr (name alias-ns) ident-name)}

            (and (keyword? ident) (= prefix "::"))
            {:sym (ctr (name (gensym)) ident-name) :tags #{:unknown} :unknown-ns ident-ns}

            (keyword? ident)
            {:sym ident}

            required-ns
            {:sym ident}

            (contains? imports ident-ns)
            {:sym (symbol (name (get imports ident-ns)) ident-name) :tags #{:method :norename}}

            (contains? lang-imports ident-ns)
            {:sym (symbol (name (get lang-imports ident-ns)) ident-name) :tags #{:method :norename}}

            (and (= :cljs file-type) (= 'js ident-ns))
            {:sym ident :tags #{:method :norename}}

            :else
            {:sym (ctr (name (gensym)) ident-name) :tags #{:unknown} :unknown-ns ident-ns}))
        :str ident-str))))

(defn add-reference [context scoped node extra]
  (let [{:keys [row end-row col end-col] :as m} (meta node)
        sexpr (n/sexpr node)
        scope-bounds (get-in scoped [sexpr :bounds])
        ctx @context
        declaration? (and (get-in extra [:tags :declare])
                          (or (get-in extra [:tags :local])
                              (get-in extra [:tags :public])))
        ident-info (qualify-ident node ctx scoped declaration?)
        new-usage (cond-> {:row row
                           :end-row end-row
                           :col col
                           :end-col end-col
                           :file-type (:file-type @context)}
                    :always (merge ident-info)
                    (seq extra) (merge extra)
                    (and (:ignored? ctx) declaration?) (-> (update :sym (fn [s] (symbol (str "__lsp_comment_" (gensym)) (name s))))
                                                           (update :tags disj :local :public))
                    (seq scope-bounds) (assoc :scope-bounds scope-bounds))]
    (vswap! context update :usages conj new-usage)
    new-usage))

(defn destructure-map [map-loc scope-bounds context scoped]
  (loop [key-loc (z/down (zsub/subzip map-loc))
         scoped scoped]
    (if (and key-loc (not (z/end? key-loc)))
      (let [key-sexpr (z/sexpr key-loc)
            val-loc (z-right-sexpr key-loc)]
        (cond
          (#{:keys :strs :syms} key-sexpr)
          (recur (edit/skip-over val-loc)
                 (loop [child-loc (z/down val-loc)
                        scoped scoped]
                   (if child-loc
                     (let [sexpr (z/sexpr child-loc)
                           scoped-ns (gensym)
                           new-scoped (assoc scoped sexpr {:ns scoped-ns :bounds scope-bounds})]
                       (add-reference context scoped (z/node child-loc) {:tags #{:declare :param}
                                                                         :scope-bounds scope-bounds
                                                                         :sym (symbol (name scoped-ns)
                                                                                      (name sexpr))})
                       (if (nil? (z-right-sexpr child-loc))
                         new-scoped
                         (recur (z-right-sexpr child-loc) new-scoped)))
                     scoped)))

          (= :as key-sexpr)
          (let [val-node (z/node val-loc)
                sexpr (n/sexpr val-node)
                scoped-ns (gensym)
                new-scoped (assoc scoped sexpr {:ns scoped-ns :bounds scope-bounds})]
            (add-reference context scoped (z/node val-loc) {:tags #{:declare :param}
                                                            :scope-bounds scope-bounds
                                                            :sym (symbol (name scoped-ns)
                                                                         (name sexpr))})
            (recur (z-right-sexpr val-loc) new-scoped))

          (keyword? key-sexpr)
          (recur (edit/skip-over val-loc) scoped)

          (not= '& key-sexpr)
          (recur (z-right-sexpr val-loc) (parse-destructuring key-loc scope-bounds context scoped))

          :else
          (recur (edit/skip-over val-loc) scoped)))
      scoped)))

(defn handle-rest
  "Crawl each form from `loc` to the end of the parent-form
  `(fn [x 1] | (a) (b) (c))`
  With cursor at `|` find references for `(a)`, `(b)`, and `(c)`"
  [loc context scoped]
  (loop [sub-loc loc]
    (when sub-loc
      (find-references* (zsub/subzip sub-loc) context scoped)
      (recur (zm/right sub-loc)))))

(defn parse-destructuring [param-loc scope-bounds context scoped]
  (loop [param-loc (zsub/subzip param-loc)
         scoped scoped]
    ;; MUTATION Updates scoped AND adds declared param references
    (if (and param-loc (not (z/end? param-loc)))
      (let [node (z/node param-loc)
            sexpr (n/sexpr node)]
        (cond
          ;; TODO handle map and seq destructing
          (symbol? sexpr)
          (let [scoped-ns (gensym)
                new-scoped (assoc scoped sexpr {:ns scoped-ns :bounds scope-bounds})]
            (add-reference context new-scoped node {:tags #{:declare :param}
                                                    :scope-bounds scope-bounds
                                                    :sym (symbol (name scoped-ns)
                                                                 (name sexpr))})
            (recur (z-next-sexpr param-loc) new-scoped))

          (vector? sexpr)
          (recur (z-next-sexpr param-loc) scoped)

          (map? sexpr)
          (recur (edit/skip-over param-loc) (destructure-map param-loc scope-bounds context scoped))

          :else
          (recur (edit/skip-over param-loc) scoped)))
      scoped)))

(defn end-bounds [loc]
  (select-keys (meta (z/node loc)) [:end-row :end-col]))

(defn parse-bindings [bindings-loc context end-scope-bounds scoped]
  (try
    (loop [binding-loc (zm/down (zsub/subzip bindings-loc))
           scoped scoped]
      (let [not-done? (and binding-loc (not (z/end? binding-loc)))]
        ;; MUTATION Updates scoped AND adds declared param references AND adds references in binding vals)
        (cond
          (and not-done? (= :uneval (z/tag binding-loc)))
          (recur (edit/skip-over binding-loc) scoped)

          not-done?
          (let [right-side-loc (z-right-sexpr binding-loc)
                binding-sexpr (z/sexpr binding-loc)]
            ;; Maybe for/doseq needs to use different bindings
            (cond
              (= :let binding-sexpr)
              (let [new-scoped (parse-bindings right-side-loc context end-scope-bounds scoped)]
                (recur (edit/skip-over right-side-loc) new-scoped))

              (#{:when :while} binding-sexpr)
              (do
                (handle-rest (zsub/subzip right-side-loc) context scoped)
                (recur (edit/skip-over right-side-loc) scoped))

              :else
              (let [{:keys [end-row end-col]} (meta (z/node (or (z-right-sexpr right-side-loc) (z/up right-side-loc) bindings-loc)))
                    scope-bounds (assoc end-scope-bounds :row end-row :col end-col)
                    new-scoped (parse-destructuring binding-loc scope-bounds context scoped)]
                (handle-rest (zsub/subzip right-side-loc) context scoped)
                (recur (edit/skip-over right-side-loc) new-scoped))))

          :else
          scoped)))
    (catch Throwable e
      (log/warn "bindings" (.getMessage e) (z/sexpr bindings-loc) (z/sexpr (z/up bindings-loc)))
      (throw e))))

(defn parse-params [params-loc context scoped]
  (try
    (let [{:keys [row col]} (meta (z/node params-loc))
          {:keys [end-row end-col]} (meta (z/node (z/up params-loc)))
          scoped-ns (gensym)
          scope-bounds {:row row :col col :end-row end-row :end-col end-col}]
      (loop [param-loc (z/down params-loc)
             scoped scoped]
        (if param-loc
          (let [new-scoped (parse-destructuring param-loc scope-bounds context scoped)]
            (if (nil? (z-right-sexpr param-loc))
              new-scoped
              (recur (z-right-sexpr param-loc) new-scoped)))
          scoped)))
    (catch Exception e
      (log/warn "params" (.getMessage e) (z/sexpr params-loc))
      (throw e))))

(defn handle-ignored
  [rest-loc context scoped]
  (let [curr-ignored? (:ignored? @context false)]
    (vswap! context assoc :ignored? true)
    (handle-rest rest-loc context scoped)
    (vswap! context assoc :ignored? curr-ignored?)))

(defn handle-comment
  [op-loc loc context scoped]
  (handle-ignored (z-right-sexpr op-loc) context scoped))

(defn add-imports [conformed context]
  (when-first [import-clause (filter (comp #{:import} first) (:clauses conformed))]
    (let [all-imports (:classes (second import-clause))
          {classes :class packages :package-list} (group-by first all-imports)
          simple-classes (map second classes)]
      (vswap! context assoc :imports
              (into (zipmap simple-classes simple-classes)
                    (for [[_ {:keys [package classes]}] packages
                          cls classes]
                      [cls (symbol (str (name package) "." (name cls)))]))))))

(defn add-libspec [libtype context scoped entry-loc prefix-ns]
  (let [entry-ns-loc (z/down entry-loc)
        required-ns (z/sexpr entry-ns-loc)
        full-ns (if prefix-ns
                  (symbol (str (name prefix-ns) "." (name required-ns)))
                  required-ns)
        alias-loc (some-> entry-ns-loc (z/find-value :as) (z-right-sexpr))
        refer-loc (some-> entry-ns-loc (z/find-value :refer) (z-right-sexpr))
        refer-all? (when refer-loc (= (z/sexpr refer-loc) :all))]
    (when (= libtype :require)
      (vswap! context update :requires conj full-ns)
      (when refer-all?
        (vswap! context update :refer-all-syms merge
                (->> (for [[_ usages] (:file-envs @db/db)
                           :when (->> usages
                                      (filter (comp #(set/subset? #{:ns :public} %) :tags))
                                      (filter (comp #{full-ns} :sym))
                                      (seq))
                           {:keys [sym tags]} usages
                           :when (set/subset? #{:public :declare} tags)]
                       [(symbol (name sym)) sym])
                     (into {})))))
    (add-reference context scoped (z/node entry-ns-loc)
                   (cond-> {:tags #{libtype} :sym full-ns}
                     alias-loc (assoc :alias (z/sexpr alias-loc))))
    (when alias-loc
      (vswap! context update :aliases assoc (z/string alias-loc) full-ns)
      (add-reference context scoped (z/node alias-loc)
                     {:tags #{:alias :declare}
                      :ns full-ns
                      :sym (z/sexpr alias-loc)}))
    (when (and refer-loc (not refer-all?))
      (doseq [refer-node (remove n/printable-only? (n/children (z/node refer-loc)))
              :let [refered (symbol (name full-ns) (n/string refer-node))
                    referee (n/string refer-node)]]
        (vswap! context update :refers assoc referee refered)
        (add-reference context scoped refer-node
                       {:tags #{:refer :declare}
                        :sym refered})))))

(defn add-libspecs [libtype context scoped entry-loc prefix-ns]
  (let [libspec? (fn [sexpr] (or (vector? sexpr) (list? sexpr)))
        prefix? (fn [sexpr] (or (symbol? (second sexpr)) (libspec? (second sexpr))))]
    (loop [entry-loc entry-loc]
      (when (and entry-loc (not (-> entry-loc z/node n/printable-only?)))
        (let [sexpr (z/sexpr entry-loc)]
          (cond
            (symbol? sexpr)
            (let [full-ns (if prefix-ns
                            (symbol (str (name prefix-ns) "." (name sexpr)))
                            sexpr)
                  class-sym (when (= libtype :import)
                              (-> full-ns
                                  name
                                  (string/split #"\.")
                                  last
                                  symbol))]
              (when class-sym
                (vswap! context update :imports assoc class-sym full-ns full-ns full-ns))

              (when (= libtype :require)
                (vswap! context update :requires conj full-ns))
              (add-reference context scoped (z/node entry-loc)
                             {:tags #{libtype} :sym full-ns}))
            (and (libspec? sexpr) (prefix? sexpr))
            (add-libspecs libtype context scoped (z-right-sexpr (z/down entry-loc)) (z/sexpr (z/down entry-loc)))

            (libspec? sexpr)
            (add-libspec libtype context scoped entry-loc prefix-ns)))
        (recur (z-right-sexpr entry-loc))))))

(defn handle-ns
  [op-loc loc context scoped]
  (let [name-loc (z-right-sexpr (z/down (zsub/subzip loc)))
        first-list-loc (z/find-tag name-loc z-right-sexpr :list)
        require-loc (z/find first-list-loc z-right-sexpr (comp #{:require} z/sexpr z/down))
        import-loc (z/find first-list-loc z-right-sexpr (comp #{:import} z/sexpr z/down))]
    (vswap! context assoc :ns (z/sexpr name-loc))
    (add-reference context scoped (z/node name-loc) {:tags #{:declare :public :ns} :kind :module :sym (z/sexpr name-loc)})
    (add-libspecs :require context scoped (some-> require-loc z/down z-right-sexpr) nil)
    (add-libspecs :import context scoped (some-> import-loc z/down z-right-sexpr) nil)))

(defn handle-let
  [op-loc loc context scoped]
  (let [bindings-loc (zf/find-tag op-loc :vector)
        scoped (parse-bindings bindings-loc context (end-bounds loc) scoped)]
    (handle-rest (z-right-sexpr bindings-loc) context scoped)))

(defn handle-if-let
  [op-loc loc context scoped]
  (let [bindings-loc (zf/find-tag op-loc :vector)
        if-loc (z-right-sexpr bindings-loc)
        if-scoped (parse-bindings bindings-loc context (end-bounds if-loc) scoped)]
    (handle-rest if-loc context if-scoped)
    (handle-rest (z-right-sexpr if-loc) context scoped)))

(defn- local? [op-loc]
  (let [op (z/sexpr op-loc)
        op-name (name op)
        name-sexpr (z/sexpr (z-right-sexpr op-loc))]
    (or (and (symbol? name-sexpr) (:private (meta name-sexpr)))
        (not (string/starts-with? op-name "def"))
        (string/ends-with? op-name "-"))))

(defn handle-def
  [op-loc loc context scoped]
  (let [def-sym (z/node (z-right-sexpr op-loc))
        op-local? (local? op-loc)
        current-ns (->> (:usages @context)
                        (filter (comp :ns :tags))
                        (some :sym))]
    (if op-local?
      (vswap! context update :locals conj (n/string def-sym))
      (vswap! context update :publics conj (n/string def-sym)))
    (add-reference context scoped def-sym {:tags (if op-local?
                                                   #{:declare :local}
                                                   #{:declare :public})})
    (handle-rest (z-right-sexpr (z-right-sexpr op-loc))
                 context scoped)))

(defn- function-signatures [name-loc multi?]
  (if multi?
    (loop [list-loc (z/find-tag name-loc :list)
           params []]
      (let [params (conj params (z/string (z/down list-loc)))]
        (if-let [next-list (z/find-next-tag list-loc :list)]
          (recur next-list params)
          params)))
    [(z/string (z/find-tag name-loc z-next-sexpr :vector))]))

(defn- function-params-and-body [params-loc context scoped]
  (let [body-loc (z-right-sexpr params-loc)]
    (->> (parse-params params-loc context scoped)
         (handle-rest body-loc context))))

(defn handle-function
  [op-loc loc context scoped name-tags]
  (let [op-local? (local? op-loc)
        op-fn? (= "fn" (name (z/sexpr op-loc)))
        name-loc (z-right-sexpr op-loc)
        multi? (= :list (z/tag (z/find op-loc (fn [loc] (#{:vector :list} (z/tag loc))))))
        current-ns (->> (:usages @context)
                        (filter (comp :ns :tags))
                        (some :sym))]
    (when (symbol? (z/sexpr name-loc))
      (cond
        op-fn? nil
        op-local? (vswap! context update :locals conj (z/string name-loc))
        :else (vswap! context update :publics conj (z/string name-loc)))
      (add-reference context scoped (z/node name-loc)
                     {:tags (cond
                              op-fn? name-tags
                              op-local? (conj name-tags :local)
                              :else (conj name-tags :public))
                      :kind :function
                      :signatures (function-signatures name-loc multi?)}))
    (if multi?
      (loop [list-loc (z/find-tag op-loc :list)]
        (function-params-and-body (z/down list-loc) context scoped)
        (when-let [next-list (z/find-next-tag list-loc :list)]
          (recur next-list)))
      (function-params-and-body (z/find-tag op-loc :vector) context scoped))))

(defn handle-defmethod
  [op-loc loc context scoped]
  (handle-function op-loc loc context scoped #{}))

(defn handle-fn
  [op-loc loc context scoped]
  (handle-function op-loc loc context scoped #{:declare}))

(defn handle-defn
  [op-loc loc context scoped]
  (handle-function op-loc loc context scoped #{:declare}))

(defn handle-catch
  [op-loc loc context scoped]
  (let [type-loc (z-right-sexpr op-loc)
        e-loc (z-right-sexpr type-loc)
        scoped-ns (gensym)
        e-node (z/node e-loc)
        scope-bounds (merge (meta e-node) (end-bounds loc))
        e-sexpr (z/sexpr e-loc)
        new-scoped (assoc scoped e-sexpr {:ns scoped-ns :bounds scope-bounds})]
    (add-reference context scoped (z/node type-loc) {})
    (add-reference context scoped e-node {:tags #{:declare :params}
                                          :scope-bounds scope-bounds
                                          :sym (symbol (name scoped-ns)
                                                       (name e-sexpr))})
    (handle-rest (z-right-sexpr e-loc) context new-scoped)))

(defn handle-type-methods
  [proto-loc context scoped]
  (loop [prev-loc proto-loc
         method-loc (z-right-sexpr proto-loc)]
    (if (and method-loc (= :list (z/tag method-loc)))
      (let [name-loc (z/down method-loc)
            params-loc (z-right-sexpr name-loc)]
        (add-reference context scoped (z/node name-loc) {:tags #{:method :norename}})
        (let [new-scoped (parse-params params-loc context scoped)]
          (handle-rest (z-right-sexpr params-loc) context new-scoped))
        (recur method-loc (z-right-sexpr method-loc)))
      prev-loc)))

(defn handle-deftype
  [op-loc loc context scoped]
  (let [type-loc (z-right-sexpr op-loc)
        fields-loc (z-right-sexpr type-loc)]
      (add-reference context scoped (z/node type-loc) {:tags #{:declare :public}
                                                       :kind :class})
      (let [field-scope (parse-params fields-loc context scoped)]
        (loop [proto-loc (z-right-sexpr fields-loc)]
          (when proto-loc
            (add-reference context field-scope (z/node proto-loc) {})
            (let [next-loc (z-right-sexpr proto-loc)]
              (cond
                (= (z/tag next-loc) :token)
                (recur next-loc)

                (= (z/tag next-loc) :list)
                (some-> proto-loc
                        (handle-type-methods context field-scope)
                        (z-right-sexpr)
                        (recur)))))))))

(defn handle-defmacro
  [op-loc loc context scoped]
  (let [op-local? (local? op-loc)
        defn-loc (z-right-sexpr op-loc)
        defn-sym (z/node defn-loc)
        multi? (= :list (z/tag (z/find defn-loc (fn [loc]
                                                  (#{:vector :list} (z/tag loc))))))]

    (if op-local?
      (vswap! context update :locals conj (n/string defn-sym))
      (vswap! context update :publics conj (n/string defn-sym)))
    (add-reference context scoped defn-sym {:tags #{:declare (if op-local? :local :public)}})
    (if multi?
      (loop [list-loc (z/find-tag defn-loc :list)]
        (let [params-loc (z/down list-loc)]
          (parse-params params-loc context scoped))
        (when-let [next-list (z/find-next-tag list-loc :list)]
          (recur next-list)))
      (let [params-loc (z/find-tag defn-loc :vector)]
        (parse-params params-loc context scoped)))))

(defn handle-dispatch-macro
  [loc context scoped]
  (->>
    (loop [sub-loc (z-next-sexpr (zsub/subzip loc))
          scoped scoped]
     (if (and sub-loc (not (z/end? sub-loc)))
       (let [sexpr (z/sexpr sub-loc)]
         (if (and (symbol? sexpr)
                  (re-find #"^%(:?\d+|&)?$" (name sexpr)))
           (recur (z-next-sexpr sub-loc) (assoc scoped sexpr {:ns (gensym) :bounds (meta (z/node sub-loc))}))
           (recur (z-next-sexpr sub-loc) scoped)))
       scoped))
   (handle-rest (z/down loc) context)))

(comment
  '[clojure.core/with-open
    clojure.core/dotimes
    clojure.core/letfn
    clojure.core/with-local-vars
    clojure.core/as->])

(def ^:dynamic *sexpr-handlers*
  {'clojure.core/ns handle-ns
   'clojure.core/defn handle-defn
   'clojure.core/defn- handle-defn
   'clojure.core/fn handle-fn
   'clojure.core/declare handle-def
   'clojure.core/defmulti handle-def
   'clojure.core/defmethod handle-defmethod
   'clojure.core/deftype handle-deftype
   'clojure.core/def handle-def
   'clojure.core/defonce handle-def
   'clojure.core/defmacro handle-defmacro
   'clojure.core/let handle-let
   'clojure.core/catch handle-catch
   'clojure.core/when-let handle-let
   'clojure.core/when-some handle-let
   'clojure.core/when-first handle-let
   'clojure.core/if-let handle-if-let
   'clojure.core/if-some handle-if-let
   'clojure.core/with-open handle-let
   'clojure.core/loop handle-let
   'clojure.core/for handle-let
   'clojure.core/doseq handle-let
   'clojure.core/comment handle-comment})

(defn handle-sexpr [loc context scoped]
  (let [op-loc (some-> loc (zm/down))]
    (cond
      (and op-loc (symbol? (z/sexpr op-loc)))
      (let [usage (add-reference context scoped (z/node op-loc) {})
            handler (get *sexpr-handlers* (:sym usage))]
        (if handler
          (handler op-loc loc context scoped)
          (handle-rest (zm/right op-loc) context scoped)))
      op-loc
      (handle-rest op-loc context scoped))))

(defn find-references* [loc context scoped]
  (loop [loc loc
         scoped scoped]
    (if (or (not loc) (zm/end? loc))
      (:usages @context)

      (let [tag (z/tag loc)]
        (cond
          (#{:quote :syntax-quote} tag)
          (recur (edit/skip-over loc) scoped)

          (= :uneval tag)
          (do
            (handle-ignored (z/next loc) context scoped)
            (recur (edit/skip-over loc) scoped))

          (= :list tag)
          (do
            (handle-sexpr loc context scoped)
            (recur (edit/skip-over loc) scoped))

          (= :fn tag)
          (do
            (handle-dispatch-macro loc context scoped)
            (recur (edit/skip-over loc) scoped))

          (and (= :token tag) (ident? (z/sexpr loc)))
          (do
            (add-reference context scoped (z/node loc) {})
            (recur (edit/skip-over loc) scoped))

          :else
          (recur (zm/next loc) scoped))))))

(defn process-reader-macro [loc file-type]
  (loop [loc loc]
    (if-let [reader-macro-loc (and (= :reader-macro (z/tag loc))
                                   (contains? #{"?@" "?"} (z/string (z/down loc))))]
      (if-let [file-type-loc (-> loc
                                 z/down
                                 z/right
                                 z/down
                                 (z/find-value z/right file-type))]
        (let [file-type-expr (z/node (z/right file-type-loc))
              splice? (= "?@" (z/string (z/down loc)))]
          (recur (cond-> (z/replace loc file-type-expr)
                   splice? (z/splice))))
        (recur (z/next (z/remove loc))))
      (if (and loc (z/next loc) (not (zm/end? loc)))
        (recur (z/next loc))
        (vary-meta (z/skip z/prev #(z/prev %) loc) assoc ::zm/end? false)))))

(defn find-references [code file-type]
  (let [code-loc (-> code
                     (string/replace #"(\w)/(\s|$)" "$1 $2")
                     (z/of-string)
                     (zm/up))]
    (if (= :cljc file-type)
      (into (-> code-loc
                (process-reader-macro :clj)
                (find-references* (volatile! (assoc default-env :file-type :clj)) {}))
            (-> code-loc
                (process-reader-macro :cljs)
                (find-references* (volatile! (assoc default-env :file-type :cljs)) {})))
      (-> code-loc
          (find-references* (volatile! (assoc default-env :file-type file-type)) {})))))

;; From rewrite-cljs
(defn in-range? [{:keys [row col end-row end-col] :as form-pos}
                 {r :row c :col er :end-row ec :end-col :as selection-pos}]
  (and (>= r row)
       (<= er end-row)
       (if (= r row) (>= c col) true)
       (if (= er end-row) (< ec end-col) true)))

;; From rewrite-cljs
(defn find-forms
  "Find last node (if more than one node) that is in range of pos and
  satisfying the given predicate depth first from initial zipper
  location."
  [zloc p?]
  (->> zloc
       (iterate z-next-sexpr)
       (take-while identity)
       (take-while (complement z/end?))
       (filter p?)))

(defn find-last-by-pos
  [zloc pos]
  (last (find-forms zloc (fn [loc]
                           (in-range?
                            (-> loc z/node meta) pos)))))

(defn find-top-forms-in-range
  [code pos]
  (->> (find-forms (z/of-string code) #(in-range?
                                         pos (-> % z/node meta)))
       (mapv (fn [loc] (z/find loc z/up edit/top?)))
       (distinct)))

(defn loc-at-pos [code row col]
  (-> code
      (z/of-string)
      (find-last-by-pos {:row row :col col :end-row row :end-col col})))

(comment
  (loc-at-pos  "foo" 1 5)
  (in-range? {:row 23, :col 1, :end-row 23, :end-col 9} {:row 23, :col 7, :end-row 23, :end-col 7})
  (let [code (string/join "\n"
                          ["(ns thinger.foo"
                           "  (:refer-clojure :exclude [update])"
                           "  (:require"
                           "    [thinger [my.bun :as bun]"
                           "             [bung.bong :as bb :refer [bing byng]]]))"
                           "(comment foo)"
                           "(let [x 1] (inc x))"
                           "(def x 1)"
                           "(defn y [y] (y x))"
                           "(inc x)"
                           "(bun/foo)"
                           "(bing)"])]
    (n/string (z/node (z/of-string "::foo")))
    (find-references code :clj))


  (do
    (require '[taoensso.tufte :as tufte :refer (defnp p profiled profile)])

    (->>
      (find-references (slurp "test/clojure_lsp/parser_test.clj") :clj)
      (tufte/profiled {})
      (second)
      deref
      :stats
      (map (juxt key (comp #(int (quot % 1000000)) :max val) (comp :n val) (comp #(int (quot % 1000000)) :sum val)))
      clojure.pprint/pprint
      with-out-str
      (spit "x.edn")))

  (let [code (slurp "bad.clj")]
    (find-references code :clj)
    #_
    (println (tufte/format-pstats prof)))

  (let [code "(ns foob) (defn ^:private chunk [] :a)"]
    (find-references code :clj))
  (let [code "(def #?@(:clj a :cljs b) 1)"]
    (n/children (z/node (z/of-string code)))
    (find-references code :cljc))

  (do (defmacro x [] (let [y 2] `(let [z# ~y] [z# ~'y]))) (let [y 3]  (x)))
  (do (defmacro x [] 'y)
      (let [y 3] (y)))

  (z/of-string "##NaN")
  (z/sexpr (z/next (z/of-string "#_1")))

  (find-references "(deftype JSValue [val])" :clj)
  (z/sexpr (loc-at-pos code 1 2)))
