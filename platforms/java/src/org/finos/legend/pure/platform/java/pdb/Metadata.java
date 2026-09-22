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

package org.finos.legend.pure.platform.java.pdb;

import java.util.List;

/**
 * The metadata translated code reads: a Pure element named in the source
 * (`NativeFunction`, `PureOne`, `meta::pure::functions::collection::List`) is
 * the metamodel object of the PDBs the host opened, not its name. The host
 * installs the runtime once, before running translated code.
 */
public final class Metadata
{
    private static final String CANONICAL_GENERIC_TYPES = "meta::pure::metamodel::type::generics::optimization::";
    private static final String POINTER_PACKAGE = "meta::pure::metamodel::pointer::";
    private static final String HOLDER = "meta::pure::metamodel::valuespecification::GenericTypeAndMultiplicityHolder";
    private static final String VALUE_SPECIFICATION = "meta::pure::metamodel::valuespecification::";
    private static final String TRANSLATE_PATH =
            "meta::external::language::java::translation::translateFunctionProgram_String_1__String_1_";
    private static final String TRANSLATE_VALUE =
            "meta::external::language::java::translation::translateFunctionValueProgram_UserDefinedFunction_1__String_1_";
    /** The Java of each dynamically compiled function, by identity. */
    private static final java.util.Map<Object, String> TRANSLATED = java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

    private static volatile PdbRuntime runtime;

    private Metadata()
    {
    }

    public static void install(PdbRuntime pdbRuntime)
    {
        runtime = pdbRuntime;
    }

    public static PdbRuntime runtime()
    {
        PdbRuntime installed = runtime;
        if (installed == null)
        {
            throw new IllegalStateException("No metadata installed: call Metadata.install(PdbRuntime) before running translated code");
        }
        return installed;
    }

    /** The installed metadata, or null when a program runs without any. */
    static PdbRuntime runtimeOrNull()
    {
        return runtime;
    }

    /** `pathToElement(path)`: the element, or an error naming the path. */
    public static Object element(String path)
    {
        // An empty path is the root package, which the metadata spells "::".
        Object element = runtime().element(path.isEmpty() ? "::" : path);
        if (element == null)
        {
            throw new RuntimeException("Element not found: " + path);
        }
        return element;
    }

    /**
     * The classifier of an instance being constructed: a GenericTypeValue over
     * the class at `path`. Null when there is no metadata yet — the platform
     * builds objects while opening the PDBs, before any is installed.
     */
    public static Object classifier(String path)
    {
        PdbRuntime installed = runtime;
        // No classifier for the objects built while decoding an element: asking
        // for one would decode again, without end.
        if (installed == null || PdbRuntime.DECODING.get())
        {
            return null;
        }
        // The metadata holds a canonical GenericType element per class; a value's
        // classifier is that element, not a fresh one (the anchor tests check it).
        Object canonical = installed.element(CANONICAL_GENERIC_TYPES + "GenericType_" + path.replace("::", "_"));
        if (canonical != null)
        {
            return canonical;
        }
        Object element = installed.element(path);
        return element == null
                ? null
                : new org.finos.legend.pure.m3.meta.pure.metamodel.type.generics.GenericTypeValueImpl()._type(element);
    }

    /**
     * The classifier of an instance built as `^X<A, B>(...)`: a GenericTypeValue
     * over the class, carrying one over each type argument.
     */
    public static Object classifier(String path, List<Object> typeArguments)
    {
        if (typeArguments.isEmpty())
        {
            return classifier(path);
        }
        PdbRuntime installed = runtime;
        Object element = installed == null || PdbRuntime.DECODING.get() ? null : installed.element(path);
        if (element == null)
        {
            return null;
        }
        List<Object> arguments = new java.util.ArrayList<>(typeArguments.size());
        for (Object typeArgument : typeArguments)
        {
            Object argument = installed.element((String) typeArgument);
            arguments.add(new org.finos.legend.pure.m3.meta.pure.metamodel.type.generics.GenericTypeValueImpl()._type(argument));
        }
        return new org.finos.legend.pure.m3.meta.pure.metamodel.type.generics.GenericTypeValueImpl()
                ._type(element)
                ._typeArguments(arguments);
    }

    /**
     * `genericTypeHolder(value)` — the value's type and multiplicity as a value
     * of their own: a holder whose classifier carries the one as a type
     * argument and the other as a multiplicity argument.
     */
    public static Object genericTypeHolder(Object value)
    {
        boolean many = value instanceof List;
        Object classifier = new org.finos.legend.pure.m3.meta.pure.metamodel.type.generics.GenericTypeValueImpl()
                ._type(element(HOLDER))
                ._typeArguments(List.of(genericType(value)))
                ._multiplicityArguments(List.of(element("meta::pure::metamodel::multiplicity::"
                        + (many ? "ZeroMany" : "PureOne"))));
        return new org.finos.legend.pure.m3.meta.pure.metamodel.valuespecification.GenericTypeAndMultiplicityHolderImpl()
                ._classifierGenericType(classifier);
    }

    /** `@Type` written in the source: the same holder, over a type named there. */
    public static Object genericTypeHolderOf(String typePath)
    {
        return new org.finos.legend.pure.m3.meta.pure.metamodel.valuespecification.GenericTypeAndMultiplicityHolderImpl()
                ._classifierGenericType(classifier(HOLDER, List.of(typePath)));
    }

    /**
     * The classifier of `^X(v)(...)`: a class may declare type VARIABLES
     * (`Class X(x:Integer[1])`), and the values written for them belong to the
     * instance's classifier, where its qualified properties and constraints
     * read them.
     */
    public static Object classifier(String path, List<Object> typeArguments, List<Object> typeVariableValues)
    {
        Object classifier = typeArguments.isEmpty() ? classifier(path) : classifier(path, typeArguments);
        if (classifier instanceof LazyObject && !typeVariableValues.isEmpty())
        {
            // A canonical classifier is shared: give the values their own.
            LazyObject own = new org.finos.legend.pure.m3.meta.pure.metamodel.type.generics.GenericTypeValueImpl()
                    ._type(typeOf(classifier))
                    ._typeArguments(values(classifier, "typeArguments"))
                    ._typeVariableValues(typeVariableValues);
            return own;
        }
        return classifier;
    }

    /**
     * `$x` inside a qualified property or a constraint of a class that declares
     * type variables: the value the instance was built with, found by the
     * variable's position among the ones its class declares.
     */
    public static Object typeVariable(Object instance, String name)
    {
        Object classifier = first(values(instance, "classifierGenericType"));
        List<Object> declared = values(typeOf(classifier), "typeVariables");
        List<Object> bound = values(classifier, "typeVariableValues");
        for (int i = 0; i < declared.size() && i < bound.size(); i++)
        {
            if (name.equals(first(values(declared.get(i), "name"))))
            {
                return valueOf(bound.get(i));
            }
        }
        return null;
    }

    /** `lenientPathToElement(path)`: the element, or null. */
    public static Object lenientElement(String path)
    {
        return runtime().element(path.isEmpty() ? "::" : path);
    }

    /** `pathToElement(path, separator)`: the path is written with `separator`. */
    public static Object element(String path, String separator)
    {
        return element(normalize(path, separator));
    }

    /** `lenientPathToElement(path, separator)`: the element, or null. */
    public static Object lenientElement(String path, String separator)
    {
        return lenientElement(normalize(path, separator));
    }

    /** A path written with `separator`, as the metadata spells it — the root package is "::". */
    private static String normalize(String path, String separator)
    {
        String normalized = "::".equals(separator) ? path : path.replace(separator, "::");
        return normalized.isEmpty() ? "::" : normalized;
    }

    /**
     * `elementToPath(element, separator)`: the names from the top-level package
     * down, the root package excluded. A property or column is its name.
     */
    public static String path(Object element, String separator)
    {
        LazyObject object = (LazyObject) element;
        // A compiler pointer carries its canonical path itself: its name and
        // package are unset, so walking the package chain would read "". The
        // compiler relies on `elementToPath` working on a pointer (a pointer
        // stands in for the element it names until the graph is resolved).
        if (object._purePath().startsWith(POINTER_PACKAGE))
        {
            List<Object> pointerPath = object.__values("path");
            if (!pointerPath.isEmpty())
            {
                String path = (String) pointerPath.get(0);
                return "::".equals(separator) ? path : path.replace("::", separator);
            }
        }
        List<Object> names = object.__values("name");
        String name = names.isEmpty() ? "" : (String) names.get(0);
        List<Object> pkg = object.__values("package");
        if (pkg.isEmpty())
        {
            return name;
        }
        LazyObject owner = (LazyObject) pkg.get(0);
        // The root package has no package of its own; a POINTER to a package has
        // none either, but it does name one — a freshly compiled element sits in
        // a package that is still a pointer.
        if (!owner._purePath().startsWith(POINTER_PACKAGE) && owner.__values("package").isEmpty())
        {
            return name;
        }
        String ownerPath = path(owner, separator);
        return ownerPath.isEmpty() ? name : ownerPath + separator + name;
    }

    /**
     * `genericType(value)`: the value's classifier as a GenericType — the one a
     * metamodel object carries, or one over the type element of a Java value
     * (a String is a Pure String, a Long an Integer, ...).
     */
    public static Object genericType(Object value)
    {
        if (value instanceof List)
        {
            List<?> values = (List<?>) value;
            if (values.isEmpty())
            {
                throw new RuntimeException("Cannot get the generic type of an empty collection");
            }
            // A collection's type is what all its values are: their own type
            // when they agree, else the most specific type they all share
            // (`[$city, $country]` is GeographicEntity, not Any).
            Object first = genericType(values.get(0));
            for (Object value1 : values)
            {
                if (!java.util.Objects.equals(type(value1), type(values.get(0))))
                {
                    return new org.finos.legend.pure.m3.meta.pure.metamodel.type.generics.UserDefinedGenericTypeImpl()
                            ._type(commonType(values));
                }
            }
            return first;
        }
        // What the value says it is. A lambda answers from its own AST, where
        // the compiler wrote the whole `LambdaFunction<{->X[1]}>` structure —
        // its canonical anchors included; the fallback below has none of that.
        if (value instanceof LazyObject || PureLambda.isPureLambda(value))
        {
            List<Object> classifier = values(value, "classifierGenericType");
            if (!classifier.isEmpty())
            {
                return classifier.get(0);
            }
        }
        return new org.finos.legend.pure.m3.meta.pure.metamodel.type.generics.UserDefinedGenericTypeImpl()._type(type(value));
    }

    /** `type(value)`: the type element a Java value stands for. */
    public static Object type(Object value)
    {
        if (value instanceof LazyObject)
        {
            // What the value says it is, when it carries a classifier: an enum
            // value's type is its enumeration, not the Enum class its node is.
            for (Object classifier : ((LazyObject) value).__values("classifierGenericType"))
            {
                List<Object> raw = ((LazyObject) classifier).__values("type");
                List<Object> named = raw.isEmpty() ? ((LazyObject) classifier).__values("rawType") : raw;
                if (!named.isEmpty())
                {
                    return named.get(0);
                }
            }
            return element(((LazyObject) value)._purePath());
        }
        if (PureLambda.isPureLambda(value))
        {
            // A lambda value's classifier is the LambdaFunction class.
            return element("meta::pure::metamodel::function::LambdaFunction");
        }
        if (value instanceof PureEnumValue)
        {
            // An enum value's classifier is its enumeration.
            return element(((PureEnumValue) value)._purePath());
        }
        if (value instanceof org.finos.legend.pure.platform.java.runtime.PureDate)
        {
            // A date's type is its precision: %2015-03-14 is a StrictDate, and
            // only a year or year-month date is a plain Date. `instanceOf` walks
            // up from here, so the coarser types still answer true.
            return element("meta::pure::metamodel::type::primitives::"
                    + ((org.finos.legend.pure.platform.java.runtime.PureDate) value).kind);
        }
        String primitive = PRIMITIVES.get(value == null ? null : value.getClass());
        if (primitive == null)
        {
            throw new UnsupportedOperationException("No Pure type for " + (value == null ? "null" : value.getClass().getName()));
        }
        return element("meta::pure::metamodel::type::primitives::" + primitive);
    }

    /**
     * `enumValues(enumeration)`: the enumeration's values. A PDB keeps them as
     * its `values`, or on each property's default value, and a value may be
     * stored as the text "Enumeration.VALUE" rather than an object.
     */
    public static List<Object> enumValues(Object enumeration)
    {
        LazyObject object = (LazyObject) enumeration;
        List<Object> values = object.__values("values");
        if (values.isEmpty())
        {
            values = new java.util.ArrayList<>();
            for (Object property : object.__values("properties"))
            {
                for (Object defaultValue : ((LazyObject) property).__values("defaultValue"))
                {
                    for (Object expression : ((LazyObject) defaultValue).__values("expressionSequence"))
                    {
                        values.addAll(((LazyObject) expression).__values("value"));
                    }
                }
            }
        }
        List<Object> enums = new java.util.ArrayList<>(values.size());
        for (Object value : values)
        {
            Object enumValue = asEnum(value);
            // A PDB stores an enum value as a bare name inside its enumeration,
            // with no classifier of its own — and nothing links it back. This is
            // where the owner is known, so the value learns its type here
            // (`CITY->instanceOf(GeographicEntityType)`).
            if (enumValue instanceof LazyObject && ((LazyObject) enumValue).__values("classifierGenericType").isEmpty())
            {
                ((LazyObject) enumValue).set("classifierGenericType",
                        new org.finos.legend.pure.m3.meta.pure.metamodel.type.generics.GenericTypeValueImpl()._type(enumeration));
            }
            enums.add(enumValue);
        }
        return enums;
    }

    /**
     * Calls a function value: a Java lambda (a closure translated in place), or
     * a function of the metadata — a PDB element, whose translated class is
     * named after its path — which is how a discovered test is run.
     */
    public static Object invoke(Object function, List<Object> args)
    {
        if (function instanceof LazyObject && isProperty((LazyObject) function))
        {
            // Calling a property reads it: `$p.name` passed as a function value.
            List<Object> names = ((LazyObject) function).__values("name");
            String name = (String) names.get(0);
            // A QUALIFIED property is computed, not stored: the platform
            // generated a method for it (named without the signature its name
            // may carry, `inParameters()`).
            if (((LazyObject) function)._purePath().endsWith("::QualifiedProperty"))
            {
                int signature = name.indexOf('(');
                return callQualifiedProperty(signature < 0 ? name : name.substring(0, signature), args);
            }
            List<Object> read = ((LazyObject) args.get(0)).__values(name);
            return read.size() == 1 ? read.get(0) : read;
        }
        if (function instanceof LazyObject)
        {
            String path = path(function, "::");
            try
            {
                return org.finos.legend.pure.platform.java.Execute.invoke(path, args.toArray());
            }
            catch (ClassNotFoundException e)
            {
                // Not part of the platform (a test, say): the platform translates
                // it with its own transform, compiles it, and calls it. An element
                // the metadata doesn't hold — freshly compiled — translates from
                // the value, since there is nothing to look its path up in.
                return lenientElement(path) == function
                        ? translateAndInvoke(path, args)
                        : translateAndInvokeValue(function, path, args);
            }
            catch (RuntimeException e)
            {
                throw e;
            }
            catch (Exception e)
            {
                throw new RuntimeException("Cannot call " + path + ": " + e.getMessage(), e);
            }
        }
        if (args.isEmpty())
        {
            return ((java.util.function.Supplier<?>) function).get();
        }
        if (args.size() == 1)
        {
            return ((java.util.function.Function<Object, ?>) function).apply(args.get(0));
        }
        if (args.size() == 2)
        {
            return ((java.util.function.BiFunction<Object, Object, ?>) function).apply(args.get(0), args.get(1));
        }
        if (args.size() == 3)
        {
            return ((org.finos.legend.pure.platform.java.runtime.PureFunction3) function)
                    .apply(args.get(0), args.get(1), args.get(2));
        }
        throw new UnsupportedOperationException("Calling a function of " + args.size() + " arguments is not supported yet");
    }

    /**
     * `evaluate(f, [list(a), list(b)])`: one collection whose SIZE is the arity,
     * each element a Pure List holding one argument's values. An empty
     * collection calls `f` with nothing — which is why this cannot go through
     * {@link #invoke}, where the collection would read as a single argument.
     */
    @SuppressWarnings("unchecked")
    public static Object evaluate(Object function, Object arguments)
    {
        List<Object> wrappers = arguments == null ? List.of()
                : arguments instanceof List ? (List<Object>) arguments
                : List.of(arguments);
        // Only here: a List is `evaluate`'s envelope around one argument. It is
        // an ordinary value everywhere else, and unwrapping it there would take
        // a genuine List away from the function being called.
        return invoke(function, unwrapArguments(wrappers));
    }

    /**
     * `instanceOf(value, type)` where the type is known only at run time: the
     * value's own type, or one of its generalizations, is `type`.
     */
    public static boolean instanceOf(Object value, Object type)
    {
        return value != null && type != null && isOrSpecializes(type(value), type);
    }

    /**
     * The most specific type every value is an instance of: the first of one
     * value's ancestors (itself included, nearest first) that all the others
     * share. Any when they share nothing else.
     */
    private static Object commonType(List<?> values)
    {
        for (Object candidate : ancestors(type(values.get(0))))
        {
            boolean all = true;
            for (Object value : values)
            {
                if (!isOrSpecializes(type(value), candidate))
                {
                    all = false;
                    break;
                }
            }
            if (all)
            {
                return candidate;
            }
        }
        return element("meta::pure::metamodel::type::Any");
    }

    /** `type` and its generalizations, nearest first. */
    private static List<Object> ancestors(Object type)
    {
        List<Object> ordered = new java.util.ArrayList<>();
        java.util.Deque<Object> pending = new java.util.ArrayDeque<>();
        java.util.Set<Object> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        pending.add(type);
        while (!pending.isEmpty())
        {
            Object candidate = pending.removeFirst();
            if (!(candidate instanceof LazyObject) || !seen.add(candidate))
            {
                continue;
            }
            ordered.add(candidate);
            for (Object generalization : ((LazyObject) candidate).__values("generalizations"))
            {
                for (Object general : ((LazyObject) generalization).__values("general"))
                {
                    pending.addAll(((LazyObject) general).__values("type"));
                    pending.addAll(((LazyObject) general).__values("rawType"));
                }
            }
        }
        return ordered;
    }

    /**
     * The first parameter of a lambda VALUE, as `match` needs it: the type its
     * values must be instances of, and the multiplicity bounds they must fit
     * (`{a:String[*] | ...}` takes any number of Strings). Read from the
     * lambda's own metadata, which is how a match over arms held in a variable
     * dispatches — the translator cannot see those arms.
     */
    public static Object[] matchArm(Object lambda)
    {
        Object classifier = first(values(lambda, "classifierGenericType"));
        Object functionType = typeOf(first(values(classifier, "typeArguments")));
        Object parameter = first(values(functionType, "parameters"));
        Object type = typeOf(first(values(parameter, "genericType")));
        Object multiplicity = first(values(parameter, "multiplicity"));
        return new Object[]{type, bound(multiplicity, "lowerBound", 0L), bound(multiplicity, "upperBound", -1L)};
    }

    /** The type a GenericType names. */
    private static Object typeOf(Object genericType)
    {
        Object raw = first(values(genericType, "rawType"));
        return raw != null ? raw : first(values(genericType, "type"));
    }

    /** A multiplicity bound, or `absent` when it is unbounded (or a parameter). */
    private static long bound(Object multiplicity, String name, long absent)
    {
        Object value = first(values(multiplicity, name));
        Object number = value instanceof Number ? value : first(values(value, "value"));
        return number instanceof Number ? ((Number) number).longValue() : absent;
    }

    /**
     * `value.<property>` — read from the metadata of an object, or through the
     * interface a lambda value implements (which answers from its own AST).
     */
    @SuppressWarnings("unchecked")
    private static List<Object> values(Object value, String property)
    {
        if (value instanceof LazyObject)
        {
            return ((LazyObject) value).__values(property);
        }
        if (value == null)
        {
            return List.of();
        }
        try
        {
            Object read = value.getClass().getMethod(property).invoke(value);
            return read == null ? List.of() : read instanceof List ? (List<Object>) read : List.of(read);
        }
        catch (ReflectiveOperationException e)
        {
            return List.of();
        }
    }

    private static Object first(List<Object> values)
    {
        return values.isEmpty() ? null : values.get(0);
    }

    /**
     * `cast(@T)` where T is a type PARAMETER: the type comes from the argument
     * that witnesses it at the call site — its own type when it is one value,
     * the type its values share when it is a collection.
     */
    public static Object witnessType(Object witness)
    {
        // The check needs the metadata to name types: a host running translated
        // code without any (the Truffle harness) cannot make it, and the value
        // passes through as it did before there was a check at all.
        if (runtimeOrNull() == null)
        {
            return null;
        }
        if (witness instanceof List)
        {
            List<?> values = (List<?>) witness;
            return values.isEmpty() ? null : commonType(values);
        }
        return witness == null ? null : type(witness);
    }

    /** `value` when every one of its values is a `type`, or the error Pure gives. */
    public static Object castTo(Object value, Object type)
    {
        if (type == null)
        {
            return value;
        }
        for (Object one : value instanceof List ? (List<?>) value : value == null ? List.of() : List.of(value))
        {
            if (!instanceOf(one, type))
            {
                throw new RuntimeException("Cast exception: " + simpleName(type(one)) + " cannot be cast to " + simpleName(type));
            }
        }
        return value;
    }

    /**
     * The same, for `cast(@T|m)`: the witness binds the multiplicity too, so a
     * probe of a different size does not fit it.
     */
    public static Object castToWitness(Object value, Object witness)
    {
        if (runtimeOrNull() == null)
        {
            return value;
        }
        int expected = witness instanceof List ? ((List<?>) witness).size() : witness == null ? 0 : 1;
        int actual = value instanceof List ? ((List<?>) value).size() : value == null ? 0 : 1;
        if (expected != actual)
        {
            throw new RuntimeException("Cast exception: multiplicity mismatch — the witness binds "
                    + expected + " value" + (expected == 1 ? "" : "s") + ", and this is " + actual);
        }
        return castTo(value, witnessType(witness));
    }

    /** An element's own name, as Pure writes it in an error. */
    private static String simpleName(Object element)
    {
        Object name = element == null ? null : first(values(element, "name"));
        return name == null ? String.valueOf(element) : (String) name;
    }

    /** Is `type` the element `target`, or one of its specializations? */
    static boolean isOrSpecializes(Object type, Object target)
    {
        if (type == null || target == null)
        {
            return false;
        }
        java.util.Deque<Object> pending = new java.util.ArrayDeque<>();
        java.util.Set<Object> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        pending.add(type);
        while (!pending.isEmpty())
        {
            Object candidate = pending.removeFirst();
            if (candidate == target)
            {
                return true;
            }
            if (!(candidate instanceof LazyObject) || !seen.add(candidate))
            {
                continue;
            }
            for (Object generalization : ((LazyObject) candidate).__values("generalizations"))
            {
                for (Object general : ((LazyObject) generalization).__values("general"))
                {
                    pending.addAll(((LazyObject) general).__values("type"));
                    pending.addAll(((LazyObject) general).__values("rawType"));
                }
            }
        }
        // Everything is an Any — the TARGET being Any is what makes this true,
        // whatever the type walked from.
        return target == lenientElement("meta::pure::metamodel::type::Any");
    }

    /**
     * `new(genericType)` where the class is known only at run time: the
     * instance of the class the generic type names, carrying it as its
     * classifier.
     */
    public static Object newInstance(Object genericType)
    {
        LazyObject type = (LazyObject) asGenericType(genericType);
        List<Object> raw = type.__values("type").isEmpty() ? type.__values("rawType") : type.__values("type");
        if (raw.isEmpty())
        {
            throw new RuntimeException("Cannot create an instance: the generic type names no class");
        }
        String path = path(raw.get(0), "::");
        // `new(^UserDefinedGenericType(type = Class))` — a metaclass says nothing
        // about what it would be a class OF without its type argument.
        if ("meta::pure::metamodel::type::Class".equals(path) && type.__values("typeArguments").isEmpty())
        {
            throw new RuntimeException("Cannot instantiate Class<Class<T>> because the typeArgs are not set for the typeParam");
        }
        try
        {
            LazyObject instance = implementationOf(raw.get(0), path);
            // A generic type carrying nothing of its own anchors at the metadata's
            // canonical one, as `^X(...)` does — the identity the anchor tests pin.
            boolean bare = type.__values("typeArguments").isEmpty() && type.__values("multiplicityArguments").isEmpty();
            Object canonical = bare
                    ? lenientElement(CANONICAL_GENERIC_TYPES + "GenericType_" + path.replace("::", "_"))
                    : null;
            instance.set("classifierGenericType", canonical == null ? genericType : canonical);
            return instance;
        }
        catch (ReflectiveOperationException e)
        {
            throw new RuntimeException("Cannot create an instance of " + path + ": " + e.getMessage(), e);
        }
    }

    /**
     * An instance of the class at `path`, or a plain Any when the platform has
     * no class for it — a type created at run time (newEnumeration) has none.
     */
    private static LazyObject implementationOf(Object type, String path) throws ReflectiveOperationException
    {
        try
        {
            Class<?> implementation = Class.forName(org.finos.legend.pure.platform.java.Execute.className(path) + "Impl");
            return (LazyObject) implementation.getDeclaredConstructor().newInstance();
        }
        catch (ClassNotFoundException e)
        {
            // A type created at run time has no class of its own: build the
            // nearest one it generalizes to, so it behaves as that class.
            for (Object generalization : ((LazyObject) type).__values("generalizations"))
            {
                for (Object general : ((LazyObject) generalization).__values("general"))
                {
                    List<Object> raw = ((LazyObject) general).__values("type").isEmpty()
                            ? ((LazyObject) general).__values("rawType")
                            : ((LazyObject) general).__values("type");
                    if (!raw.isEmpty())
                    {
                        return implementationOf(raw.get(0), path(raw.get(0), "::"));
                    }
                }
            }
            return new org.finos.legend.pure.m3.meta.pure.metamodel.type.AnyImpl();
        }
    }

    /**
     * Is `classifier` an acceptable classifierGenericType for `instance`? Its
     * RAW TYPE identifies the metaclass and is system-managed: a user value may
     * only refine it (type arguments, multiplicity arguments, type variables),
     * or name a subtype — which is how `^Enum(...)` becomes a specific
     * enumeration. Anything else is a mistake, and says so.
     */
    static void checkClassifier(LazyObject instance, Object classifier)
    {
        if (runtime == null || PdbRuntime.DECODING.get() || !(classifier instanceof LazyObject))
        {
            return;
        }
        Object proposed = typeOf(classifier);
        // A pointer stands in for an element that is not resolved yet.
        if (proposed == null || ((LazyObject) proposed)._purePath().startsWith(POINTER_PACKAGE))
        {
            return;
        }
        Object expected = lenientElement(instance._purePath());
        if (expected == null || proposed == expected || isOrSpecializes(proposed, expected))
        {
            return;
        }
        throw new RuntimeException("Cannot change classifierGenericType.type from '" + instance._purePath()
                + "' to '" + path(proposed, "::") + "'. The classifier's raw type is system-managed (derived from the"
                + " instance's metaclass) — only typeArguments, multiplicityArguments and typeVariableValues are"
                + " user-customizable. Use meta::pure::functions::lang::new(GenericType[1]) to construct an instance"
                + " with a different metaclass.");
    }

    /**
     * The generic type `new` builds from. `new(@X|1, ...)` and
     * `new($x->genericTypeHolder(), ...)` name their type through a HOLDER: the
     * type it holds is the first type argument of the holder's own classifier.
     */
    private static Object asGenericType(Object target)
    {
        LazyObject object = (LazyObject) target;
        if (!object.__values("type").isEmpty() || !object.__values("rawType").isEmpty())
        {
            return target;
        }
        Object argument = first(values(first(object.__values("classifierGenericType")), "typeArguments"));
        return argument == null ? target : argument;
    }

    /**
     * `new(type, [^KeyExpression(name = ..., expression = ...)])` — the keys are
     * VALUES here, built by the program, so they are read when `new` runs rather
     * than compiled into setters.
     */
    public static Object setKeysFrom(Object instance, Object keyExpressions)
    {
        for (Object keyExpression : keyExpressions instanceof List ? (List<?>) keyExpressions
                : keyExpressions == null ? List.of() : List.of(keyExpressions))
        {
            Object name = first(values(keyExpression, "name"));
            if (name != null)
            {
                // The key's values ARE the expression: `expression = ['a','b','c']`
                // holds three of them, and only a single one can be a value
                // specification wrapping what it stands for.
                List<Object> expression = values(keyExpression, "expression");
                set(instance, (String) name, expression.size() == 1 ? valueOf(expression.get(0)) : expression);
            }
        }
        return instance;
    }

    /** What a key expression holds: a value specification's values, or the value. */
    private static Object valueOf(Object expression)
    {
        if (expression instanceof LazyObject && ((LazyObject) expression)._purePath().startsWith(VALUE_SPECIFICATION))
        {
            List<Object> values = ((LazyObject) expression).__values("values");
            return values.isEmpty() ? ((LazyObject) expression).__values("value") : values;
        }
        return expression;
    }

    /** Sets a property on an instance whose class is known only at run time. */
    public static Object set(Object instance, String property, Object value)
    {
        ((LazyObject) instance).set(property, value);
        return instance;
    }

    /** A property or qualified property, which is called by reading it. */
    /** `$owner.<name>(rest...)` — the method the platform generated for a qualified property. */
    private static Object callQualifiedProperty(String name, List<Object> args)
    {
        Object owner = args.get(0);
        List<Object> rest = args.subList(1, args.size());
        for (java.util.Iterator<java.lang.reflect.Method> methods =
                     java.util.Arrays.asList(owner.getClass().getMethods()).iterator(); methods.hasNext(); )
        {
            java.lang.reflect.Method method = methods.next();
            if (method.getName().equals(name) && method.getParameterCount() == rest.size())
            {
                try
                {
                    return method.invoke(owner, rest.toArray());
                }
                catch (IllegalAccessException e)
                {
                    throw new RuntimeException("Cannot read " + name + " of " + owner.getClass().getSimpleName(), e);
                }
                catch (java.lang.reflect.InvocationTargetException e)
                {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    throw cause instanceof RuntimeException ? (RuntimeException) cause : new RuntimeException(cause);
                }
            }
        }
        throw new UnsupportedOperationException(
                owner.getClass().getSimpleName() + " has no qualified property " + name + "/" + rest.size());
    }

    private static boolean isProperty(LazyObject function)
    {
        return function._purePath().startsWith("meta::pure::metamodel::function::property::");
    }

    private static List<Object> unwrapArguments(List<Object> args)
    {
        List<Object> unwrapped = new java.util.ArrayList<>(args.size());
        for (Object arg : args)
        {
            boolean wrapper = arg instanceof LazyObject && "meta::pure::functions::collection::List".equals(((LazyObject) arg)._purePath());
            List<Object> values = wrapper ? ((LazyObject) arg).__values("values") : null;
            unwrapped.add(!wrapper ? arg : values.size() == 1 ? values.get(0) : values);
        }
        return unwrapped;
    }

    /** Translates the function at `path` with the platform's own transform, compiles it, and calls it. */
    public static Object translateAndInvoke(String path, List<Object> args)
    {
        return invokeTranslated(translate(TRANSLATE_PATH, path, path), path, args);
    }

    /**
     * The same for a function the metadata does NOT hold — one `compileSource`
     * just built. It has a path, but nothing to look that path up in: the
     * translation runs from the function VALUE.
     */
    static Object translateAndInvokeValue(Object function, String path, List<Object> args)
    {
        String source = TRANSLATED.get(function);
        if (source == null)
        {
            // By identity, not by path: compiling the same source twice gives
            // two independent functions, and each must translate on its own.
            source = translate(TRANSLATE_VALUE, function, path);
            TRANSLATED.put(function, source);
        }
        return invokeTranslated(source, path, args);
    }

    private static String translate(String translator, Object argument, String path)
    {
        try
        {
            return (String) org.finos.legend.pure.platform.java.Execute.invoke(translator, argument);
        }
        catch (Exception e)
        {
            throw new RuntimeException("Cannot translate " + path + ": " + e.getMessage(), e);
        }
    }

    private static Object invokeTranslated(String source, String path, List<Object> args)
    {
        return org.finos.legend.pure.platform.java.JavaSource.compileAndExecute(
                source, org.finos.legend.pure.platform.java.Execute.className(path), "execute", args);
    }

    /** `openVariableValues(lambda)`: what a lambda value captured, by name. */
    public static java.util.Map<?, ?> openVariables(Object lambda)
    {
        return PureLambda.capturesOf(lambda);
    }

    /** The name of an enum value: a Java enum constant, a metamodel Enum, or "Enumeration.VALUE". */
    public static String enumName(Object value)
    {
        if (value instanceof Enum)
        {
            return ((Enum<?>) value).name();
        }
        if (value instanceof LazyObject)
        {
            List<Object> names = ((LazyObject) value).__values("name");
            return names.isEmpty() ? null : (String) names.get(0);
        }
        String text = String.valueOf(value);
        return text.substring(text.lastIndexOf('.') + 1);
    }

    private static Object asEnum(Object value)
    {
        if (!(value instanceof String))
        {
            return value;
        }
        String text = (String) value;
        return new org.finos.legend.pure.m3.meta.pure.metamodel.type.EnumImpl()._name(text.substring(text.lastIndexOf('.') + 1));
    }

    private static final java.util.Map<Class<?>, String> PRIMITIVES = java.util.Map.of(
            String.class, "String",
            Long.class, "Integer",
            Double.class, "Float",
            Boolean.class, "Boolean",
            java.math.BigDecimal.class, "Decimal",
            org.finos.legend.pure.platform.java.runtime.PureDate.class, "Date");
}
