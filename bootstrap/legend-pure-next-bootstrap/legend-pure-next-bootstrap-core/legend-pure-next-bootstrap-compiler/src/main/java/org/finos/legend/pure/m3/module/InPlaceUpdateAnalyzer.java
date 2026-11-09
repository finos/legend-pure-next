// Copyright 2026 Goldman Sachs
// ©2026 JP Morgan Chase & Co. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.finos.legend.pure.m3.module;

import org.finos.legend.pure.m3.pureLanguage.metadata.lazyFunctions.FunctionIndexEntry;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Multiplicity;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement;
import platform.pure.metamodel.PackageableElement;
import platform.pure.metamodel.constraint.Constraint;
import platform.pure.metamodel.extension.ElementWithStereotypes;
import platform.pure.metamodel.extension.ElementWithTaggedValues;
import platform.pure.metamodel.extension.Stereotype;
import platform.pure.metamodel.extension.Tag;
import platform.pure.metamodel.extension.TaggedValue;
import platform.pure.metamodel.function.Function;
import platform.pure.metamodel.function.FunctionDefinition;
import platform.pure.metamodel.function.LambdaFunction;
import platform.pure.metamodel.function.NativeFunction;
import platform.pure.metamodel.function.PackageableFunction;
import platform.pure.metamodel.function.UserDefinedFunction;
import platform.pure.metamodel.function.property.Property;
import platform.pure.metamodel.function.property.QualifiedProperty;
import platform.pure.metamodel.multiplicity.ConcreteMultiplicity;
import platform.pure.metamodel.multiplicity.Multiplicity;
import platform.pure.metamodel.pointer.StereotypePointer;
import platform.pure.metamodel.pointer.TagPointer;
import platform.pure.metamodel.relationship.Association;
import platform.pure.metamodel.type.Class;
import platform.pure.metamodel.type.Enumeration;
import platform.pure.metamodel.type.PrimitiveType;
import platform.pure.metamodel.type.Type;
import platform.pure.metamodel.type.generics.GenericType;
import platform.pure.metamodel.valuespecification.AtomicValue;
import platform.pure.metamodel.valuespecification.Collection;
import platform.pure.metamodel.valuespecification.FunctionExpression;
import platform.pure.metamodel.valuespecification.ValueSpecification;
import platform.pure.metamodel.valuespecification.VariableExpression;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The updatable in place-value analysis (pure/specification/IN_PLACE_UPDATE_ANALYSIS.md): marks a variable use
 * {@code updatable in place} when it holds the only reference to its value — the variable is bound to a fresh value,
 * this is its last use, and no alias of it escaped — so the platform consuming it may mutate the value in
 * place; and {@code updatableInPlaceIfParameter = p} when the same holds provided the caller passed parameter p updatable in place.
 * Also records, per function and lambda, whether its result is updatable in place ({@code resultUpdatableInPlace}) or updatable in place when
 * a parameter is ({@code resultUpdatableInPlaceIfParameter}).
 *
 * <p>Defensive by construction: without an {@code updatability} annotation on a native, every argument is
 * assumed retained, every lambda escapes and no result is fresh. Annotations are trusted on native functions
 * only. Calls to user-defined functions leak their arguments.</p>
 *
 * <p>Runs after pass 3, on the resolved graph (every {@code func} set). Mirrors compiler-pure's
 * {@code inPlaceUpdateAnalyzer.pure}.</p>
 */
public final class InPlaceUpdateAnalyzer
{
    static final String PROFILE = "platform::pure::profiles::inPlaceUpdate";

    private InPlaceUpdateAnalyzer()
    {
    }

    /**
     * Annotate every body of {@code elements}. {@code resolveElement} maps a path to the element (as the
     * lean-references validator does): a native called from another module is reached through it, which reads
     * its annotations without loading the function through the function index.
     */
    public static void annotate(List<? extends PackageableElement> elements, java.util.function.Function<String, PackageableElement> resolveElement,
                                Map<String, ? extends java.util.Collection<String>> referencedBy)
    {
        Natives natives = new Natives(resolveElement);
        // User functions first, callees before callers, so a call reads its callee's summary; within a recursive
        // group none is known yet, so calls between its members are treated as unknown, whatever the order.
        Map<String, UserDefinedFunction> functions = new java.util.TreeMap<>();
        for (PackageableElement element : elements)
        {
            if (element instanceof UserDefinedFunction fn)
            {
                functions.put(_PackageableElement.path(fn), fn);
            }
        }
        for (List<String> group : recursiveGroups(functions.keySet(), referencedBy))
        {
            natives.recursive = new java.util.HashSet<>(group);
            for (String path : group)
            {
                UserDefinedFunction fn = functions.get(path);
                analyzeRoot(fn, natives);
                fn._preConstraints().forEach(c -> analyzeConstraint(c, natives));
                fn._postConstraints().forEach(c -> analyzeConstraint(c, natives));
            }
        }
        natives.recursive = Set.of();
        for (PackageableElement element : elements)
        {
            if (element instanceof Class cls)
            {
                cls._qualifiedProperties().forEach(qp -> analyzeRoot(qp, natives));
                cls._constraints().forEach(c -> analyzeConstraint(c, natives));
                cls._properties().forEach(p -> analyzeDefault(p._defaultValue(), natives));
            }
            else if (element instanceof Association assoc)
            {
                assoc._qualifiedProperties().forEach(qp -> analyzeRoot(qp, natives));
                assoc._properties().forEach(p -> analyzeDefault(p._defaultValue(), natives));
            }
            else if (element instanceof PrimitiveType pt)
            {
                pt._constraints().forEach(c -> analyzeConstraint(c, natives));
            }
        }
    }

    /**
     * The strongly connected groups of {@code functions} under "references" ({@code referencedBy}: a function to the
     * elements referencing it), callees' groups before their callers' (Tarjan's order). Groups do not depend on the
     * order functions are visited in, and neither does the analysis: it reads summaries only across groups.
     */
    static List<List<String>> recursiveGroups(Set<String> functions, Map<String, ? extends java.util.Collection<String>> referencedBy)
    {
        Map<String, java.util.TreeSet<String>> calls = new HashMap<>();
        for (String target : functions)
        {
            java.util.Collection<String> callers = referencedBy.get(target);
            if (callers != null)
            {
                for (String caller : callers)
                {
                    if (functions.contains(caller))
                    {
                        calls.computeIfAbsent(caller, k -> new java.util.TreeSet<>()).add(target);
                    }
                }
            }
        }
        Tarjan tarjan = new Tarjan(calls);
        for (String f : functions)
        {
            if (!tarjan.index.containsKey(f))
            {
                tarjan.connect(f);
            }
        }
        return tarjan.groups;
    }

    private static final class Tarjan
    {
        final Map<String, java.util.TreeSet<String>> calls;
        final Map<String, Integer> index = new HashMap<>();
        final Map<String, Integer> low = new HashMap<>();
        final java.util.ArrayDeque<String> stack = new java.util.ArrayDeque<>();
        final Set<String> onStack = new java.util.HashSet<>();
        final List<List<String>> groups = new ArrayList<>();

        Tarjan(Map<String, java.util.TreeSet<String>> calls)
        {
            this.calls = calls;
        }

        void connect(String v)
        {
            index.put(v, index.size());
            low.put(v, index.get(v));
            stack.push(v);
            onStack.add(v);
            for (String w : calls.getOrDefault(v, new java.util.TreeSet<>()))
            {
                if (!index.containsKey(w))
                {
                    connect(w);
                    low.put(v, Math.min(low.get(v), low.get(w)));
                }
                else if (onStack.contains(w))
                {
                    low.put(v, Math.min(low.get(v), index.get(w)));
                }
            }
            if (low.get(v).equals(index.get(v)))
            {
                List<String> group = new ArrayList<>();
                String w;
                do
                {
                    w = stack.pop();
                    onStack.remove(w);
                    group.add(w);
                }
                while (!w.equals(v));
                java.util.Collections.sort(group);
                groups.add(group);
            }
        }
    }

    /**
     * A constraint never solely holds what it checks: the instance, or the function's arguments and result, are used again
     * once it passes. Its own parameters are shared.
     */
    private static void analyzeConstraint(Constraint c, Natives natives)
    {
        if (c._functionDefinition() != null)
        {
            new Body(natives, false).run(c._functionDefinition());
        }
        if (c._messageFunction() != null)
        {
            new Body(natives, false).run(c._messageFunction());
        }
    }

    private static void analyzeDefault(LambdaFunction defaultValue, Natives natives)
    {
        if (defaultValue != null)
        {
            analyzeRoot(defaultValue, natives);
        }
    }

    private static void analyzeRoot(FunctionDefinition root, Natives natives)
    {
        Body body = new Body(natives, true);
        body.run(root);
    }

    // ------------------------------------------------------------------------
    // Model
    // ------------------------------------------------------------------------

    /** How a native treats a lambda argument. */
    enum LambdaKind
    {
        ROOT, ONCE, REPEATED, ESCAPING
    }

    /** Where a variable use sits. */
    enum Position
    {
        /** A direct argument of a function call: a consumer that may take or mutate the value. */
        ARGUMENT,
        /** The value bound by a {@code let}, or a branch result flowing into one: the value moves. */
        MOVE,
        /** The value a body returns. */
        RETURN,
        /** The receiver of a property read: neither consumed nor retained. */
        READ,
        /** Anywhere else (a collection element, a lambda's result handed to a native, ...): an alias. */
        OTHER
    }

    /**
     * Who solely holds a value: nobody else (fresh), nobody else provided the caller passed {@code parameter} updatable in place, or
     * possibly someone else (shared).
     */
    record Updatability(Binding parameter)
    {
        static final Updatability ALWAYS = new Updatability(null);
        static final Updatability NEVER = new Updatability(null);

        static Updatability ifParameter(Binding parameter)
        {
            return new Updatability(parameter);
        }

        boolean conditional()
        {
            return parameter != null;
        }

        /** The updatability of a value that is one of two alternatives (the branches of an if). */
        Updatability either(Updatability other)
        {
            if (this == NEVER || other == NEVER)
            {
                return NEVER;
            }
            if (this == ALWAYS)
            {
                return other;
            }
            if (other == ALWAYS || other.parameter == parameter)
            {
                return this;
            }
            return NEVER;
        }
    }

    static final class LambdaScope
    {
        final LambdaScope parent;
        final LambdaKind kind;
        final FunctionDefinition function;
        final Set<Binding> captures = new LinkedHashSet<>();
        final List<Binding> parameters = new ArrayList<>();

        LambdaScope(LambdaScope parent, LambdaKind kind, FunctionDefinition function)
        {
            this.parent = parent;
            this.kind = kind;
            this.function = function;
        }
    }

    static final class Binding
    {
        final String name;
        final boolean parameter;
        final LambdaScope home;
        final GenericType genericType;
        final Multiplicity multiplicity;
        final ValueSpecification value;
        final List<Use> uses = new ArrayList<>();
        boolean poisoned;
        Updatability updatability = Updatability.NEVER;
        /** Each element of the list bound here is referenced only by that list, once. */
        boolean elementsUpdatableInPlace;

        Binding(String name, boolean parameter, LambdaScope home, GenericType genericType, Multiplicity multiplicity, ValueSpecification value)
        {
            this.name = name;
            this.parameter = parameter;
            this.home = home;
            this.genericType = genericType;
            this.multiplicity = multiplicity;
            this.value = value;
        }
    }

    static final class Use
    {
        final VariableExpression expression;
        final Binding binding;
        final Position position;
        final boolean released;
        boolean last;
        boolean updatableInPlace;
        Binding updatableInPlaceIf;

        Use(VariableExpression expression, Binding binding, Position position, boolean released)
        {
            this.expression = expression;
            this.binding = binding;
            this.position = position;
            this.released = released;
        }
    }

    /** One argument of a call, as the analysis sees it. */
    record Argument(ValueSpecification value, LambdaFunction lambda, LambdaKind kind)
    {
    }

    /** The call a variable use is a direct argument of, and at which index. */
    record ArgumentOf(FunctionExpression call, int index)
    {
    }

    /** A lexical scope: the names visible at a point, innermost first. */
    static final class Scope
    {
        final Scope parent;
        final LambdaScope lambda;
        final Map<String, Binding> names = new HashMap<>();

        Scope(Scope parent, LambdaScope lambda)
        {
            this.parent = parent;
            this.lambda = lambda;
        }

        Binding lookup(String name)
        {
            for (Scope s = this; s != null; s = s.parent)
            {
                Binding b = s.names.get(name);
                if (b != null)
                {
                    return b;
                }
            }
            return null;
        }
    }

    // ------------------------------------------------------------------------
    // One root body: forward walk, backward liveness, forward decision
    // ------------------------------------------------------------------------

    static final class Body
    {
        final Natives natives;
        final List<Binding> bindings = new ArrayList<>();
        final Map<VariableExpression, Use> uses = new IdentityHashMap<>();
        final Map<FunctionExpression, List<Argument>> calls = new IdentityHashMap<>();
        final Map<LambdaFunction, LambdaScope> lambdas = new IdentityHashMap<>();
        final Map<FunctionExpression, Binding> lets = new IdentityHashMap<>();
        /** The property read each use in a READ position is the receiver of. */
        final Map<VariableExpression, FunctionExpression> reads = new IdentityHashMap<>();
        final Map<VariableExpression, ArgumentOf> argumentOf = new IdentityHashMap<>();
        /** Every copy with keys, and the value of every key setting a property (a construction's or a copy's). */
        final List<FunctionExpression> copies = new ArrayList<>();
        final List<FunctionExpression> keyValues = new ArrayList<>();
        /** The property reads proved to hold the only reference to a field's collection, and on what condition. */
        final Map<FunctionExpression, Updatability> updatableReads = new IdentityHashMap<>();

        /** Whether the root's own parameters may be passed updatable in place by its caller (not a constraint's). */
        final boolean rootParametersUpdatableInPlace;

        Body(Natives natives, boolean rootParametersUpdatableInPlace)
        {
            this.natives = natives;
            this.rootParametersUpdatableInPlace = rootParametersUpdatableInPlace;
        }

        void run(FunctionDefinition root)
        {
            LambdaScope rootScope = new LambdaScope(null, LambdaKind.ROOT, root);
            Scope scope = new Scope(null, rootScope);
            bindParameters(root, rootScope, scope);
            walkSequence(root._expressionSequence(), scope, Position.RETURN);

            Set<Binding> live = new LinkedHashSet<>();
            liveSequence(root._expressionSequence(), live);

            for (Binding b : bindings)
            {
                decide(b);
            }
            markFields();
            for (Map.Entry<LambdaFunction, LambdaScope> e : lambdas.entrySet())
            {
                summarize(e.getKey());
            }
            summarize(root);
            decideElements(root);
            // A lambda's open variables are captures, not uses, but the compiler records them with the very objects
            // of the uses: give each lambda its own unmarked copy of a use marked updatable in place.
            for (LambdaFunction lambda : lambdas.keySet())
            {
                org.eclipse.collections.api.list.MutableList<VariableExpression> open = lambda._openVariables();
                if (open != null && open.anySatisfy(InPlaceUpdateAnalyzer::marked))
                {
                    lambda._openVariables(open.collect(v -> marked(v) ? ((VariableExpression) v._copy())._updatableInPlace(null)._updatableInPlaceIfParameter(null)._elementsUpdatableInPlace(null) : v));
                }
            }
        }

        private void bindParameters(FunctionDefinition function, LambdaScope lambdaScope, Scope scope)
        {
            for (VariableExpression p : function._parameters())
            {
                Binding b = new Binding(p._name(), true, lambdaScope, p._genericType(), p._multiplicity(), null);
                scope.names.put(p._name(), b);
                lambdaScope.parameters.add(b);
                bindings.add(b);
            }
        }

        // -- forward walk ----------------------------------------------------

        /** A body: each statement in order; the last one is the body's result, in {@code resultPosition}. */
        private void walkSequence(List<? extends ValueSpecification> sequence, Scope scope, Position resultPosition)
        {
            for (int i = 0; i < sequence.size(); i++)
            {
                boolean result = i == sequence.size() - 1;
                walk(sequence.get(i), scope, result ? resultPosition : Position.OTHER, false);
            }
        }

        /**
         * {@code vs} in {@code position}; {@code released} when a variable there does not leak (an argument
         * the callee declares it does not retain, or the receiver of a property read).
         */
        private void walk(ValueSpecification vs, Scope scope, Position position, boolean released)
        {
            if (vs instanceof VariableExpression ve)
            {
                use(ve, scope, position, released);
            }
            else if (vs instanceof FunctionExpression fe)
            {
                if (isLet(fe))
                {
                    walkLet(fe, scope);
                }
                else
                {
                    walkCall(fe, scope, position);
                }
            }
            else if (vs instanceof AtomicValue av && av._value() instanceof LambdaFunction lambda)
            {
                // A lambda value not handed straight to a native that declares how it runs it: it may run any
                // time, any number of times.
                walkLambda(lambda, scope, LambdaKind.ESCAPING, Position.OTHER);
            }
            else if (vs instanceof Collection col)
            {
                for (ValueSpecification v : col._values())
                {
                    walk(v, scope, Position.OTHER, false);
                }
            }
        }

        private void use(VariableExpression ve, Scope scope, Position position, boolean released)
        {
            String name = ve._name();
            if (name == null || name.startsWith("~"))
            {
                return;
            }
            Binding b = scope.lookup(name);
            if (b == null)
            {
                return;
            }
            // A use from inside a lambda the variable was not bound in is a capture; one through a lambda that
            // escapes means the value may be read at any later time, so no use of it is ever updatable in place.
            for (LambdaScope l = scope.lambda; l != null && l != b.home; l = l.parent)
            {
                l.captures.add(b);
                if (l.kind == LambdaKind.ESCAPING)
                {
                    b.poisoned = true;
                }
            }
            Use u = new Use(ve, b, position, released);
            b.uses.add(u);
            uses.put(ve, u);
        }

        private void walkLet(FunctionExpression fe, Scope scope)
        {
            List<? extends ValueSpecification> args = fe._parametersValues();
            ValueSpecification value = args.size() > 1 ? args.get(1) : null;
            if (value != null)
            {
                walk(value, scope, Position.MOVE, false);
            }
            String name = letName(fe);
            if (name != null)
            {
                Binding b = new Binding(name, false, scope.lambda, fe._genericType(), fe._multiplicity(), value);
                scope.names.put(name, b);
                bindings.add(b);
                lets.put(fe, b);
            }
        }

        private void walkCall(FunctionExpression fe, Scope scope, Position position)
        {
            String path = fe._func() instanceof PackageableFunction pf ? _PackageableElement.path(pf) : null;
            if (COPY.equals(path))
            {
                copies.add(fe);
            }
            else if (KEY.equals(path) && fe._parametersValues().size() == 2 && fe._parametersValues().get(1) instanceof FunctionExpression value && !isLet(value))
            {
                keyValues.add(value);
            }
            Callee callee = natives.of(fe._func());
            List<? extends ValueSpecification> args = fe._parametersValues();
            List<Argument> classified = new ArrayList<>();
            // A property read only looks at its receiver (a qualified property runs a body, which may keep it).
            boolean read = fe._func() instanceof platform.pure.metamodel.function.property.Property;
            for (int i = 0; i < args.size(); i++)
            {
                ValueSpecification arg = args.get(i);
                VariableExpression parameter = callee.parameter(i);
                LambdaKind kind = callee.lambdaKind(parameter);
                if (arg instanceof AtomicValue av && av._value() instanceof LambdaFunction lambda)
                {
                    classified.add(new Argument(arg, lambda, kind));
                    // A branch of an exclusive-alternatives native (if, match) in a position where its result moves
                    // (a let, a return) hands that result straight on.
                    Position branchResult = kind == LambdaKind.ONCE && (position == Position.MOVE || position == Position.RETURN) ? position : Position.OTHER;
                    walkLambda(lambda, scope, kind, branchResult);
                }
                else if (arg instanceof Collection col && col._values().anySatisfy(v -> v instanceof AtomicValue a && a._value() instanceof LambdaFunction))
                {
                    // match's arms: a literal collection of lambdas handed to the native as one parameter.
                    for (ValueSpecification v : col._values())
                    {
                        if (v instanceof AtomicValue a && a._value() instanceof LambdaFunction lambda)
                        {
                            classified.add(new Argument(v, lambda, kind));
                            Position branchResult = kind == LambdaKind.ONCE && (position == Position.MOVE || position == Position.RETURN) ? position : Position.OTHER;
                            walkLambda(lambda, scope, kind, branchResult);
                        }
                        else
                        {
                            classified.add(new Argument(v, null, null));
                            walk(v, scope, Position.OTHER, false);
                        }
                    }
                }
                else
                {
                    classified.add(new Argument(arg, null, null));
                    if (read && i == 0)
                    {
                        if (arg instanceof VariableExpression ve)
                        {
                            reads.put(ve, fe);
                        }
                        walk(arg, scope, Position.READ, true);
                    }
                    else
                    {
                        if (arg instanceof VariableExpression ve)
                        {
                            argumentOf.put(ve, new ArgumentOf(fe, i));
                        }
                        walk(arg, scope, Position.ARGUMENT, arg instanceof VariableExpression ve && callee.releases(i, parameter, multiplicityOf(ve, scope)));
                    }
                }
            }
            calls.put(fe, classified);
        }

        private Multiplicity multiplicityOf(VariableExpression ve, Scope scope)
        {
            Binding b = scope.lookup(ve._name());
            return b != null ? b.multiplicity : ve._multiplicity();
        }

        private void walkLambda(LambdaFunction lambda, Scope outer, LambdaKind kind, Position resultPosition)
        {
            LambdaScope lambdaScope = new LambdaScope(outer.lambda, kind, lambda);
            lambdas.put(lambda, lambdaScope);
            Scope scope = new Scope(outer, lambdaScope);
            bindParameters(lambda, lambdaScope, scope);
            walkSequence(lambda._expressionSequence(), scope, resultPosition);
        }

        // -- backward liveness -----------------------------------------------

        /**
         * The bindings live before {@code sequence}, given those live after it ({@code live}, updated in
         * place). A use is the last of its binding when the binding is not live right after it.
         */
        private void liveSequence(List<? extends ValueSpecification> sequence, Set<Binding> live)
        {
            for (int i = sequence.size() - 1; i >= 0; i--)
            {
                liveBefore(sequence.get(i), live);
            }
        }

        private void liveBefore(ValueSpecification vs, Set<Binding> live)
        {
            if (vs instanceof VariableExpression ve)
            {
                Use u = uses.get(ve);
                if (u != null)
                {
                    u.last = !live.contains(u.binding);
                    live.add(u.binding);
                }
            }
            else if (vs instanceof FunctionExpression fe)
            {
                Binding let = lets.get(fe);
                if (let != null)
                {
                    live.remove(let);
                    List<? extends ValueSpecification> args = fe._parametersValues();
                    if (args.size() > 1)
                    {
                        liveBefore(args.get(1), live);
                    }
                    return;
                }
                List<Argument> args = calls.get(fe);
                if (args == null)
                {
                    return;
                }
                // Lambdas the native runs do so when it is called, after every argument is evaluated.
                Set<Binding> after = new LinkedHashSet<>(live);
                for (Argument a : args)
                {
                    if (a.lambda() != null)
                    {
                        live.addAll(liveLambda(a.lambda(), a.kind(), after));
                    }
                }
                // A call runs once every argument is evaluated, so it reads a receiver held by a variable last: a later
                // argument only reading it ($xs->add($xs->size()), ^$acc(count = $acc.count + 1)) is done with it by
                // then. A variable passed as another argument of the call is read while it runs ($ys->concatenate($ys)):
                // it still holds the receiver. A later argument keeping it keeps it alive (othersHarmless). A receiver
                // computed by an expression is evaluated first, as any argument.
                ValueSpecification first = fe._parametersValues().isEmpty() ? null : fe._parametersValues().get(0);
                boolean receiverLast = first instanceof VariableExpression && !args.isEmpty() && args.get(0).lambda() == null && args.get(0).value() == first;
                if (receiverLast)
                {
                    Use u = uses.get((VariableExpression) first);
                    if (u != null)
                    {
                        Set<Binding> held = new LinkedHashSet<>(live);
                        for (int i = 1; i < args.size(); i++)
                        {
                            if (args.get(i).value() instanceof VariableExpression other && uses.get(other) != null)
                            {
                                held.add(uses.get(other).binding);
                            }
                        }
                        u.last = !held.contains(u.binding);
                        live.add(u.binding);
                    }
                }
                for (int i = args.size() - 1; i >= 0; i--)
                {
                    if (args.get(i).lambda() == null && !(i == 0 && receiverLast))
                    {
                        liveBefore(args.get(i).value(), live);
                    }
                }
            }
            else if (vs instanceof AtomicValue av && av._value() instanceof LambdaFunction lambda)
            {
                live.addAll(liveLambda(lambda, LambdaKind.ESCAPING, live));
            }
            else if (vs instanceof Collection col)
            {
                List<? extends ValueSpecification> values = col._values();
                for (int i = values.size() - 1; i >= 0; i--)
                {
                    liveBefore(values.get(i), live);
                }
            }
        }

        /**
         * The outer bindings live when a lambda starts, given those live after the call that runs it. A lambda
         * run once continues straight into what follows the call; one run repeatedly (or at any time) may run
         * again, so each binding it captures is still live at the end of its body.
         */
        private Set<Binding> liveLambda(LambdaFunction lambda, LambdaKind kind, Set<Binding> afterCall)
        {
            LambdaScope lambdaScope = lambdas.get(lambda);
            Set<Binding> live = new LinkedHashSet<>(afterCall);
            if (kind != LambdaKind.ONCE && lambdaScope != null)
            {
                live.addAll(lambdaScope.captures);
            }
            liveSequence(lambda._expressionSequence(), live);
            if (lambdaScope != null)
            {
                lambdaScope.parameters.forEach(live::remove);
            }
            return live;
        }

        // -- decision --------------------------------------------------------

        /** Bindings are decided in declaration order, so a move ({@code let y = $x}) sees whether x's use was updatable in place. */
        private void decide(Binding b)
        {
            b.updatability = b.poisoned || !annotatable(b.genericType, b.multiplicity) ? Updatability.NEVER
                    : b.parameter ? (b.home.kind == LambdaKind.ROOT && !rootParametersUpdatableInPlace ? Updatability.NEVER : Updatability.ifParameter(b))
                    : updatability(b.value);
            if (b.updatability == Updatability.NEVER)
            {
                return;
            }
            for (Use u : b.uses)
            {
                if ((u.position == Position.ARGUMENT || u.position == Position.MOVE) && u.last && othersHarmless(b, u))
                {
                    if (b.updatability.conditional())
                    {
                        u.updatableInPlaceIf = b.updatability.parameter();
                        u.expression._updatableInPlaceIfParameter(u.updatableInPlaceIf.name);
                    }
                    else
                    {
                        u.updatableInPlace = true;
                        u.expression._updatableInPlace(true);
                    }
                }
            }
        }

        /**
         * No other use of {@code b} can see a mutation made at {@code u}: each is released (not retained by
         * its callee) or is itself a last use — and two last uses are on exclusive branches.
         */
        private static boolean othersHarmless(Binding b, Use u)
        {
            for (Use other : b.uses)
            {
                if (other != u && !other.last && !other.released)
                {
                    return false;
                }
            }
            return true;
        }

        // -- fields ------------------------------------------------------------

        /**
         * A field's collection, read from an object a copy consumes, in the key replacing that field
         * ({@code ^$acc(items = $acc.items->add($e))}): the read holds the only reference to the collection when the
         * copy's source does (marked), the read is the one use of the object reading that property, every other use
         * reads another property, and the read is in that key's value outside any lambda. A platform keeps a field's
         * collection as its own only when it was stored updatable in place and never read otherwise (IN_PLACE_UPDATE_ANALYSIS.md).
         * Then each key value that is a call is marked as updatable in place as it is.
         */
        private void markFields()
        {
            for (FunctionExpression copy : copies)
            {
                List<? extends ValueSpecification> args = copy._parametersValues();
                if (args.size() < 2 || !(args.get(0) instanceof VariableExpression source) || uses.get(source) == null)
                {
                    continue;
                }
                Use consumed = uses.get(source);
                if (!consumed.updatableInPlace && consumed.updatableInPlaceIf == null)
                {
                    continue;
                }
                Updatability updatableInPlace = consumed.updatableInPlace ? Updatability.ALWAYS : Updatability.ifParameter(consumed.updatableInPlaceIf);
                Map<String, List<FunctionExpression>> readsOf = new HashMap<>();
                boolean onlyReads = true;
                for (Use u : consumed.binding.uses)
                {
                    if (u == consumed)
                    {
                        continue;
                    }
                    FunctionExpression read = reads.get(u.expression);
                    if (u.position != Position.READ || read == null || read._functionName() == null)
                    {
                        onlyReads = false;
                        break;
                    }
                    readsOf.computeIfAbsent(read._functionName(), k -> new ArrayList<>()).add(read);
                }
                if (!onlyReads)
                {
                    continue;
                }
                for (Map.Entry<String, ValueSpecification> key : plainKeys(args.get(1)).entrySet())
                {
                    List<FunctionExpression> fieldReads = readsOf.get(key.getKey());
                    if (fieldReads != null && fieldReads.size() == 1 && toMany(fieldReads.get(0)._multiplicity()) && within(key.getValue(), fieldReads.get(0)))
                    {
                        FunctionExpression read = fieldReads.get(0);
                        updatableReads.put(read, updatableInPlace);
                        mark(read, updatableInPlace);
                    }
                }
            }
            for (FunctionExpression value : keyValues)
            {
                mark(value, updatability(value));
            }
        }

        private static void mark(FunctionExpression fe, Updatability o)
        {
            if (o == Updatability.ALWAYS)
            {
                fe._updatableInPlace(true);
            }
            else if (o.conditional())
            {
                fe._updatableInPlaceIfParameter(o.parameter().name);
            }
        }

        /** A copy's keys setting a property (not appending to it, not a property of a property): name to value. */
        private static Map<String, ValueSpecification> plainKeys(ValueSpecification keys)
        {
            Map<String, ValueSpecification> plain = new java.util.LinkedHashMap<>();
            List<? extends ValueSpecification> each = keys instanceof Collection col ? col._values() : List.of(keys);
            for (ValueSpecification k : each)
            {
                if (k instanceof FunctionExpression kfe && kfe._func() instanceof PackageableFunction pf && KEY.equals(_PackageableElement.path(pf))
                        && kfe._parametersValues().size() == 2
                        && kfe._parametersValues().get(0) instanceof AtomicValue av && av._value() instanceof String name && !name.contains("."))
                {
                    plain.put(name, kfe._parametersValues().get(1));
                }
            }
            return plain;
        }

        /** Whether {@code target} is {@code vs} or one of its subexpressions, not looking into lambdas. */
        private static boolean within(ValueSpecification vs, FunctionExpression target)
        {
            if (vs == target)
            {
                return true;
            }
            if (vs instanceof FunctionExpression fe)
            {
                return fe._parametersValues().anySatisfy(v -> within(v, target));
            }
            if (vs instanceof Collection col)
            {
                return col._values().anySatisfy(v -> within(v, target));
            }
            return false;
        }

        /** Who solely holds the value of {@code vs} once computed? */
        private Updatability updatability(ValueSpecification vs)
        {
            if (vs instanceof VariableExpression ve)
            {
                Use u = uses.get(ve);
                if (u == null)
                {
                    return Updatability.NEVER;
                }
                if (u.updatableInPlace)
                {
                    return Updatability.ALWAYS;
                }
                if (u.updatableInPlaceIf != null)
                {
                    return Updatability.ifParameter(u.updatableInPlaceIf);
                }
                // A value handed on unmarked (a body's result: the summary says it instead).
                return u.last && othersHarmless(u.binding, u) ? u.binding.updatability : Updatability.NEVER;
            }
            if (vs instanceof FunctionExpression fe && updatableReads.containsKey(fe))
            {
                return updatableReads.get(fe);
            }
            if (vs instanceof FunctionExpression fe && !isLet(fe))
            {
                Callee callee = natives.of(fe._func());
                // NewInstanceResult vouches for a singular result's instance (new, copy), and NewCollectionResult for a to-many result's
                // collection — which says nothing when this call yields a single value: map over one element
                // returns the lambda's value, which may be the element itself.
                if (callee.freshResult && (callee.singularResult || toMany(fe._multiplicity())))
                {
                    return Updatability.ALWAYS;
                }
                // A user function's summary: its result is updatable in place, or is as much as one of its arguments. A summary
                // on a result of multiplicity [m] speaks for its collection, so only for a call yielding several.
                if (callee.userResultUpdatableInPlace && (!callee.userResultOfMultiplicityParameter || toMany(fe._multiplicity())))
                {
                    return Updatability.ALWAYS;
                }
                if (callee.userResultParameter >= 0 && callee.userResultParameter < fe._parametersValues().size())
                {
                    return updatability(fe._parametersValues().get(callee.userResultParameter));
                }
                List<Argument> args = calls.get(fe);
                if (args == null)
                {
                    return Updatability.NEVER;
                }
                // if / match: updatable in place when every branch ends in a value updatable in place.
                Updatability result = null;
                for (Argument a : args)
                {
                    if (a.lambda() != null)
                    {
                        if (a.kind() != LambdaKind.ONCE)
                        {
                            return Updatability.NEVER;
                        }
                        ValueSpecification branch = last(a.lambda());
                        Updatability o = branch == null ? Updatability.NEVER : updatability(branch);
                        result = result == null ? o : result.either(o);
                    }
                }
                return result == null ? Updatability.NEVER : result;
            }
            // A literal of no value, or of several, is a new collection at each evaluation (is.pure); [e] is e.
            if (vs instanceof Collection col)
            {
                List<? extends ValueSpecification> values = col._values();
                return values.size() != 1 && (values.isEmpty() || toMany(col._multiplicity())) ? Updatability.ALWAYS : Updatability.NEVER;
            }
            return Updatability.NEVER;
        }

        /**
         * A result of multiplicity [m] that is fresh whenever a caller's m makes it several values: a NewCollectionResult
         * native, or a user function of the same kind. {@code sort(col)}, which calls {@code sort(col, [], [])}.
         */
        private boolean freshWhenToMany(ValueSpecification result)
        {
            if (!(result instanceof FunctionExpression fe) || isLet(fe) || fe._multiplicity() == null
                    || fe._multiplicity() instanceof ConcreteMultiplicity)
            {
                return false;
            }
            Callee callee = natives.of(fe._func());
            return (callee.freshResult && !callee.singularResult)
                    || (callee.userResultUpdatableInPlace && callee.userResultOfMultiplicityParameter);
        }

        // -- elements ----------------------------------------------------------

        /**
         * Which lists hold elements nothing else references (IN_PLACE_UPDATE_ANALYSIS.md, Elements), once the lambda summaries
         * are known. Bindings in declaration order, so a list built from an earlier one sees it. A use that lets an
         * element out before the list's last use loses this for good.
         */
        private void decideElements(FunctionDefinition root)
        {
            for (Binding b : bindings)
            {
                b.elementsUpdatableInPlace = !b.parameter && !b.poisoned && b.value != null && toMany(b.multiplicity) && instances(b.genericType)
                        && b.uses.stream().noneMatch(u -> !u.last && letsElementOut(u))
                        && elementsUpdatableInPlace(b.value);
            }
            for (Map.Entry<FunctionExpression, List<Argument>> call : calls.entrySet())
            {
                Callee callee = natives.of(call.getKey()._func());
                if (call.getValue().stream().noneMatch(a -> a.lambda() != null))
                {
                    continue;
                }
                for (int i = 0; i < call.getValue().size(); i++)
                {
                    ValueSpecification value = call.getValue().get(i).value();
                    if (callee.givesUpElements(callee.parameter(i)) && elementsUpdatableInPlace(value))
                    {
                        if (value instanceof VariableExpression ve)
                        {
                            ve._elementsUpdatableInPlace(true);
                        }
                        else if (value instanceof FunctionExpression fe)
                        {
                            fe._elementsUpdatableInPlace(true);
                        }
                    }
                }
            }
            ValueSpecification result = last(root);
            if (root instanceof UserDefinedFunction && result != null && elementsUpdatableInPlace(result))
            {
                root._resultElementsUpdatableInPlace(true);
            }
        }

        /** A use of a list after which one of its elements may be held elsewhere. Assumed, unless proved otherwise. */
        private boolean letsElementOut(Use u)
        {
            if (u.position == Position.READ)
            {
                return false;
            }
            ArgumentOf at = argumentOf.get(u.expression);
            if (at == null)
            {
                return true;
            }
            Callee callee = natives.of(at.call()._func());
            VariableExpression parameter = callee.parameter(at.index());
            if (parameter == null)
            {
                return true;
            }
            if (callee.onlyReads.contains(parameter._name()))
            {
                return false;
            }
            return !callee.givesUpElements(parameter) || !lambdaOnlyReadsElement(at.call());
        }

        /** The inline lambda of a call only reads its first parameter: nothing lets that element out. */
        private boolean lambdaOnlyReadsElement(FunctionExpression call)
        {
            LambdaFunction lambda = inlineLambda(call);
            LambdaScope scope = lambda == null ? null : lambdas.get(lambda);
            if (scope == null || scope.parameters.isEmpty() || scope.parameters.get(0).poisoned)
            {
                return false;
            }
            return scope.parameters.get(0).uses.stream().noneMatch(this::letsElementOut);
        }

        private LambdaFunction inlineLambda(FunctionExpression call)
        {
            List<Argument> args = calls.get(call);
            return args == null ? null : args.stream().filter(a -> a.lambda() != null).map(Argument::lambda).findFirst().orElse(null);
        }

        /** The value of {@code vs} is a list each of whose elements is referenced only by it, once. */
        private boolean elementsUpdatableInPlace(ValueSpecification vs)
        {
            if (vs instanceof VariableExpression ve)
            {
                Use u = uses.get(ve);
                return u != null && u.last && u.binding.elementsUpdatableInPlace;
            }
            if (vs instanceof Collection col)
            {
                List<? extends ValueSpecification> values = col._values();
                return values.isEmpty() || (toMany(col._multiplicity()) && values.stream().allMatch(v -> updatability(v) == Updatability.ALWAYS));
            }
            if (!(vs instanceof FunctionExpression fe) || isLet(fe) || !toMany(fe._multiplicity()) || !instances(fe._genericType()))
            {
                return false;
            }
            Callee callee = natives.of(fe._func());
            if (callee.userResultElementsUpdatableInPlace)
            {
                return true;
            }
            List<Argument> args = calls.get(fe);
            if (args == null)
            {
                return false;
            }
            if (!callee.freshResult)
            {
                // if / match: every branch ends in such a list.
                List<Argument> branches = args.stream().filter(a -> a.lambda() != null).toList();
                return !branches.isEmpty() && branches.stream().allMatch(a -> a.kind() == LambdaKind.ONCE && last(a.lambda()) != null && elementsUpdatableInPlace(last(a.lambda())));
            }
            LambdaFunction lambda = inlineLambda(fe);
            boolean source = false;
            for (int i = 0; i < args.size(); i++)
            {
                Argument a = args.get(i);
                VariableExpression parameter = callee.parameter(i);
                if (a.lambda() != null)
                {
                    continue;
                }
                if (parameter != null && callee.resultMayHoldElementsOf.contains(parameter._name()))
                {
                    if (!elementsUpdatableInPlace(a.value()))
                    {
                        return false;
                    }
                    source = true;
                }
                else if (callee.givesUpElements(parameter))
                {
                    // map: the elements are what the lambda returns, each call.
                    if (lambda == null)
                    {
                        return false;
                    }
                    boolean returnsItsElement = lambda._resultUpdatableInPlaceIfParameter() != null && lambda._parameters().notEmpty()
                            && lambda._resultUpdatableInPlaceIfParameter().equals(lambda._parameters().get(0)._name());
                    if (!Boolean.TRUE.equals(lambda._resultUpdatableInPlace()) && !(returnsItsElement && elementsUpdatableInPlace(a.value())))
                    {
                        return false;
                    }
                    source = true;
                }
                else if (annotatable(a.value()._genericType(), a.value()._multiplicity()) && updatability(a.value()) != Updatability.ALWAYS)
                {
                    // add's value: it joins the list.
                    return false;
                }
            }
            return source;
        }

        /** The function or lambda's result summary. */
        private void summarize(FunctionDefinition function)
        {
            ValueSpecification result = last(function);
            if (result == null)
            {
                return;
            }
            Updatability o = updatability(result);
            if (o == Updatability.ALWAYS || (function instanceof UserDefinedFunction && freshWhenToMany(result)))
            {
                function._resultUpdatableInPlace(true);
            }
            // UpdatableInPlace when the caller passed this parameter updatable in place (fold threads its accumulator through this).
            else if (o.conditional() && o.parameter().home.function == function)
            {
                function._resultUpdatableInPlaceIfParameter(o.parameter().name);
            }
        }
    }

    private static boolean marked(VariableExpression v)
    {
        return Boolean.TRUE.equals(v._updatableInPlace()) || v._updatableInPlaceIfParameter() != null || Boolean.TRUE.equals(v._elementsUpdatableInPlace());
    }

    /** A type whose values are class instances, which could be changed in place. */
    private static boolean instances(GenericType genericType)
    {
        return annotatable(genericType, null);
    }

    private static ValueSpecification last(FunctionDefinition function)
    {
        List<? extends ValueSpecification> seq = function._expressionSequence();
        return seq == null || seq.isEmpty() ? null : seq.get(seq.size() - 1);
    }

    private static final String COPY = "platform::pure::functions::lang::copy_T_1__KeyExpression_MANY__T_1_";
    private static final String KEY = "platform::pure::functions::lang::keyExpression_String_1__Any_MANY__KeyExpression_1_";

    private static boolean isLet(FunctionExpression fe)
    {
        return "letFunction".equals(fe._functionName());
    }

    private static String letName(FunctionExpression fe)
    {
        List<? extends ValueSpecification> args = fe._parametersValues();
        return !args.isEmpty() && args.get(0) instanceof AtomicValue av && av._value() instanceof String s ? s : null;
    }

    /** Can a value of this type and multiplicity be mutated in place: a collection, or a class instance? */
    static boolean annotatable(GenericType genericType, Multiplicity multiplicity)
    {
        if (toMany(multiplicity))
        {
            return true;
        }
        Type type = genericType == null ? null : _GenericType.type(genericType);
        return type instanceof Class && !(type instanceof PrimitiveType) && !(type instanceof Enumeration)
                && !isFunctionType(type);
    }

    private static boolean isFunctionType(Type type)
    {
        String path = type instanceof PackageableElement pe ? _PackageableElement.path(pe) : null;
        return path != null && path.startsWith("platform::pure::metamodel::function::");
    }

    /** A concrete multiplicity whose upper bound is above one; a multiplicity parameter is not known to be. */
    static boolean toMany(Multiplicity m)
    {
        if (!(m instanceof ConcreteMultiplicity))
        {
            return false;
        }
        Long upper = _Multiplicity.upperBound(m);
        return upper == null || upper > 1;
    }

    static boolean singular(Multiplicity m)
    {
        return m instanceof ConcreteMultiplicity && _Multiplicity.isSingular(m);
    }

    // ------------------------------------------------------------------------
    // What the analysis knows about a callee
    // ------------------------------------------------------------------------

    static final class Callee
    {
        static final Callee UNKNOWN = new Callee(List.of(), false, false, Set.of(), Set.of(), Set.of(), Set.of(), false);

        /** A user function's own summary: its result is updatable in place, or is when the argument at this index is. */
        boolean userResultUpdatableInPlace;
        int userResultParameter = -1;
        boolean userResultElementsUpdatableInPlace;
        Set<String> resultMayHoldElementsOf = Set.of();
        Set<String> callsLambdaWithElementsOf = Set.of();
        /** The user function's result has multiplicity [m]: what m is depends on the call. */
        boolean userResultOfMultiplicityParameter;

        final List<VariableExpression> parameters;
        final boolean freshResult;
        final boolean singularResult;
        final Set<String> onlyReads;
        final Set<String> keepsOnlyContentsOf;
        final Set<String> invokesOnce;
        final Set<String> invokesRepeatedly;
        final boolean propertyRead;

        Callee(List<VariableExpression> parameters, boolean freshResult, boolean singularResult, Set<String> onlyReads,
               Set<String> keepsOnlyContentsOf, Set<String> invokesOnce, Set<String> invokesRepeatedly, boolean propertyRead)
        {
            this.parameters = parameters;
            this.freshResult = freshResult;
            this.singularResult = singularResult;
            this.onlyReads = onlyReads;
            this.keepsOnlyContentsOf = keepsOnlyContentsOf;
            this.invokesOnce = invokesOnce;
            this.invokesRepeatedly = invokesRepeatedly;
            this.propertyRead = propertyRead;
        }

        /** The native hands each element of this argument to a lambda once and keeps none: map, fold. */
        boolean givesUpElements(VariableExpression parameter)
        {
            return parameter != null && callsLambdaWithElementsOf.contains(parameter._name()) && !resultMayHoldElementsOf.contains(parameter._name());
        }

        VariableExpression parameter(int index)
        {
            return index < parameters.size() ? parameters.get(index) : null;
        }

        LambdaKind lambdaKind(VariableExpression parameter)
        {
            String name = parameter == null ? null : parameter._name();
            if (name != null && invokesOnce.contains(name))
            {
                return LambdaKind.ONCE;
            }
            if (name != null && invokesRepeatedly.contains(name))
            {
                return LambdaKind.REPEATED;
            }
            return LambdaKind.ESCAPING;
        }

        /**
         * Does passing a variable of {@code variableMultiplicity} as argument {@code index} leave it unaliased?
         * A parameter claiming only its contents leave (resultMayHoldElementsOf, resultMayHoldFieldsOf, callsLambdaWithElementsOf) releases the argument itself, not its elements: a single value lifted
         * into a to-many parameter is an element, which the native may hand back.
         */
        boolean releases(int index, VariableExpression parameter, Multiplicity variableMultiplicity)
        {
            if (propertyRead)
            {
                return index == 0;
            }
            String name = parameter == null ? null : parameter._name();
            if (name == null)
            {
                return false;
            }
            return onlyReads.contains(name) || (keepsOnlyContentsOf.contains(name)
                    && (singular(parameter._multiplicity()) || toMany(variableMultiplicity)));
        }
    }

    /** The annotations of the natives called, read once per function path. */
    static final class Natives
    {
        private final java.util.function.Function<String, PackageableElement> resolveElement;
        private final Map<String, Callee> byPath = new HashMap<>();
        /** The recursive group being analysed: its members' summaries are not known yet. */
        Set<String> recursive = Set.of();

        Natives(java.util.function.Function<String, PackageableElement> resolveElement)
        {
            this.resolveElement = resolveElement;
        }

        Callee of(Function func)
        {
            if (func instanceof Property)
            {
                return new Callee(List.of(), false, false, Set.of(), Set.of(), Set.of(), Set.of(), true);
            }
            if (!(func instanceof PackageableFunction pf) || func instanceof QualifiedProperty)
            {
                return Callee.UNKNOWN;
            }
            String path = _PackageableElement.path(pf);
            if (path == null)
            {
                return Callee.UNKNOWN;
            }
            if (pf instanceof UserDefinedFunction || pf instanceof FunctionIndexEntry && !(resolveElement.apply(path) instanceof NativeFunction))
            {
                return user(pf, path);
            }
            return byPath.computeIfAbsent(path, p -> read(pf, p));
        }

        /**
         * A user function's body is analysed, never taken on faith: its arguments leak, and its result is what its
         * summary says — once known: a member of the recursive group being analysed has none yet.
         */
        private Callee user(PackageableFunction func, String path)
        {
            PackageableElement element = func instanceof FunctionIndexEntry ? resolveElement.apply(path) : func;
            if (recursive.contains(path) || !(element instanceof UserDefinedFunction fn) || element instanceof FunctionIndexEntry)
            {
                return Callee.UNKNOWN;
            }
            Callee callee = new Callee(List.copyOf(fn._parameters()), false, false, Set.of(), Set.of(), Set.of(), Set.of(), false);
            callee.userResultUpdatableInPlace = Boolean.TRUE.equals(fn._resultUpdatableInPlace());
            callee.userResultOfMultiplicityParameter = !(fn._returnMultiplicity() instanceof ConcreteMultiplicity);
            callee.userResultElementsUpdatableInPlace = Boolean.TRUE.equals(fn._resultElementsUpdatableInPlace());
            String p = fn._resultUpdatableInPlaceIfParameter();
            if (p != null)
            {
                for (int i = 0; i < callee.parameters.size(); i++)
                {
                    if (p.equals(callee.parameters.get(i)._name()))
                    {
                        callee.userResultParameter = i;
                    }
                }
            }
            return callee;
        }

        private Callee read(PackageableFunction func, String path)
        {
            // Only natives are trusted: a user function's body is analysed, never taken on faith. A call names its
            // function through the function index, whose lazy entries must not be loaded during compilation
            // (FunctionIndexEntry.setFailOnResolve): the element is read through the resolver instead, and if
            // that too only has the lazy entry, the callee is treated as unannotated.
            PackageableElement element = func instanceof FunctionIndexEntry ? resolveElement.apply(path) : func;
            if (!(element instanceof NativeFunction nf) || element instanceof FunctionIndexEntry)
            {
                return Callee.UNKNOWN;
            }
            Set<String> stereotypes = inPlaceUpdateStereotypes(nf);
            Map<String, Set<String>> tags = inPlaceUpdateTags(nf);
            Callee callee = new Callee(List.copyOf(nf._parameters()),
                    stereotypes.contains("NewInstanceResult") || stereotypes.contains("NewCollectionResult"),
                    singular(nf._returnMultiplicity()),
                    tags.getOrDefault("onlyReads", Set.of()),
                    keepsOnlyContentsOf(tags),
                    tags.getOrDefault("callsLambdaAtMostOnce", Set.of()),
                    tags.getOrDefault("callsLambdaRepeatedly", Set.of()),
                    false);
            callee.resultMayHoldElementsOf = tags.getOrDefault("resultMayHoldElementsOf", Set.of());
            callee.callsLambdaWithElementsOf = tags.getOrDefault("callsLambdaWithElementsOf", Set.of());
            return callee;
        }
    }

    /** The claims that the argument itself is not kept, though its contents may come out of the call. */
    static final Set<String> CONTENTS_TAGS = Set.of("resultMayHoldElementsOf", "resultMayHoldFieldsOf", "callsLambdaWithElementsOf");

    private static Set<String> keepsOnlyContentsOf(Map<String, Set<String>> tags)
    {
        Set<String> names = new LinkedHashSet<>();
        for (String tag : CONTENTS_TAGS)
        {
            names.addAll(tags.getOrDefault(tag, Set.of()));
        }
        return names;
    }

    /** The values of {@code element}'s updatability stereotypes. */
    static Set<String> inPlaceUpdateStereotypes(Object element)
    {
        Set<String> result = new LinkedHashSet<>();
        if (element instanceof ElementWithStereotypes ews && ews._stereotypes() != null)
        {
            for (Stereotype s : ews._stereotypes())
            {
                if (s instanceof StereotypePointer sp)
                {
                    if (PROFILE.equals(sp._path()))
                    {
                        result.add(sp._element());
                    }
                }
                else if (s != null && s._profile() != null && PROFILE.equals(_PackageableElement.path(s._profile())))
                {
                    result.add(s._value());
                }
            }
        }
        return result;
    }

    /** {@code element}'s updatability tags: tag name to the parameter names its value lists. */
    static Map<String, Set<String>> inPlaceUpdateTags(Object element)
    {
        Map<String, Set<String>> result = new HashMap<>();
        if (element instanceof ElementWithTaggedValues ewt && ewt._taggedValues() != null)
        {
            for (TaggedValue tv : ewt._taggedValues())
            {
                String tagName = inPlaceUpdateTagName(tv._tag());
                if (tagName != null && tv._value() != null)
                {
                    Set<String> names = result.computeIfAbsent(tagName, k -> new LinkedHashSet<>());
                    for (String n : tv._value().split(","))
                    {
                        if (!n.isBlank())
                        {
                            names.add(n.strip());
                        }
                    }
                }
            }
        }
        return result;
    }

    private static String inPlaceUpdateTagName(Tag tag)
    {
        if (tag instanceof TagPointer tp)
        {
            return PROFILE.equals(tp._path()) ? tp._element() : null;
        }
        return tag != null && tag._profile() != null && PROFILE.equals(_PackageableElement.path(tag._profile())) ? tag._value() : null;
    }
}
