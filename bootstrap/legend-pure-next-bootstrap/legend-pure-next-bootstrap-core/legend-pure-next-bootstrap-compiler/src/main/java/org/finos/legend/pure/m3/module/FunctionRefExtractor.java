// Copyright 2026 Goldman Sachs
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

import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.constraint.Constraint;
import meta.pure.metamodel.function.Function;
import meta.pure.metamodel.function.FunctionDefinition;
import meta.pure.metamodel.function.LambdaFunction;
import meta.pure.metamodel.function.UserDefinedFunction;
import meta.pure.metamodel.function.property.AbstractProperty;
import meta.pure.metamodel.function.property.Property;
import meta.pure.metamodel.function.property.QualifiedProperty;
import meta.pure.metamodel.relationship.Association;
import meta.pure.metamodel.type.Class;
import meta.pure.metamodel.type.Enumeration;
import meta.pure.metamodel.type.PrimitiveType;
import meta.pure.metamodel.valuespecification.AtomicValue;
import meta.pure.metamodel.valuespecification.Collection;
import meta.pure.metamodel.valuespecification.FunctionExpression;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * Derives the function-reference half of the reverse index by walking the
 * compiled AST. For every {@link FunctionExpression} whose {@code _func} slot
 * is set, emits a {@code (target, caller)} edge — where the target is the
 * resolved function's path (or {@code Owner.propName} for properties) and the
 * caller is the enclosing top-level or sub-element compilation scope.
 *
 * <p>This replaces the in-flight {@code recordFunctionReference} hooks at the
 * function-application and dot-application resolver success sites. Those
 * hooks were vulnerable to multi-candidate trial pollution: when an outer
 * function-application's candidate trial failed and rolled back, sub-resolutions
 * fired during the failed trial were either kept (Java's pre-fix behaviour,
 * phantom edges) or lost (post-snapshot/restore behaviour, missing edges from
 * the winning trial's body too). Deriving function refs from compiled
 * {@code _func} slots is structurally guaranteed to match exactly what the
 * PDB serialises — no phantom edges possible, no winning-trial recordings
 * lost.</p>
 *
 * <p>Other recording categories (type references via genericTypeCompiler,
 * stereotype/tag references via annotationCompiler, sub-element caller
 * scoping) are not affected by candidate-trial pollution and remain recorded
 * as in-flight side-effects.</p>
 */
public final class FunctionRefExtractor
{
    private FunctionRefExtractor()
    {
    }

    /**
     * Walk the compiled AST of every element and emit function-reference
     * edges to {@code sink}. The sink receives {@code (targetPath, callerPath)}
     * pairs; the caller hooks pipe them into the compilation context's
     * reverse index.
     */
    public static void extractInto(List<? extends PackageableElement> elements,
                                   BiConsumer<String, String> sink)
    {
        for (PackageableElement element : elements)
        {
            String callerPath = _PackageableElement.path(element);
            if (callerPath == null)
            {
                continue;
            }
            extractFromElement(element, callerPath, sink);
        }
    }

    private static void extractFromElement(PackageableElement element, String elementPath, BiConsumer<String, String> sink)
    {
        if (element instanceof UserDefinedFunction fn)
        {
            // Function body
            walkExprs(fn._expressionSequence(), elementPath, sink);
            // Pre/post constraints — body resolved with $this + params + (post) $return scope.
            // Caller stays at the function path: the function's named scope owns the
            // constraint references, mirroring how withCallerSubElement scopes
            // constraint resolution inside a Function in compileConstraints.
            walkConstraints(fn._preConstraints(), elementPath, sink);
            walkConstraints(fn._postConstraints(), elementPath, sink);
            return;
        }
        if (element instanceof Class cls)
        {
            // Qualified properties — each QP body's caller is {@code Class.qpName}
            cls._qualifiedProperties().forEach(qp ->
                    walkExprs(qp._expressionSequence(), elementPath + "." + qp._name(), sink));
            // Constraints — caller is {@code Class.constraintName}; unnamed
            // constraints fall back to the class path so we still capture refs.
            for (Constraint c : cls._constraints())
            {
                String cName = c._name();
                String cCaller = cName == null || cName.isEmpty() ? elementPath : elementPath + "." + cName;
                walkConstraint(c, cCaller, sink);
            }
            // Property defaultValue lambdas — caller is {@code Class.propName}
            cls._properties().forEach(p ->
            {
                LambdaFunction dv = p._defaultValue();
                if (dv != null)
                {
                    walkExprs(dv._expressionSequence(), elementPath + "." + p._name(), sink);
                }
            });
            return;
        }
        if (element instanceof Association assoc)
        {
            // Same shape as Class: walk QPs + property defaults (associations
            // don't have constraints).
            assoc._qualifiedProperties().forEach(qp ->
                    walkExprs(qp._expressionSequence(), elementPath + "." + qp._name(), sink));
            assoc._properties().forEach(p ->
            {
                LambdaFunction dv = p._defaultValue();
                if (dv != null)
                {
                    walkExprs(dv._expressionSequence(), elementPath + "." + p._name(), sink);
                }
            });
            return;
        }
        if (element instanceof PrimitiveType pt)
        {
            for (Constraint c : pt._constraints())
            {
                String cName = c._name();
                String cCaller = cName == null || cName.isEmpty() ? elementPath : elementPath + "." + cName;
                walkConstraint(c, cCaller, sink);
            }
            return;
        }
        if (element instanceof Enumeration en)
        {
            // Enum values are Properties whose defaultValue holds an AtomicValue of
            // the Enum instance — no function references inside. But for parity
            // with the existing recording shape, walk them anyway: a future
            // enum-value default could carry expressions.
            en._properties().forEach(p ->
            {
                LambdaFunction dv = p._defaultValue();
                if (dv != null)
                {
                    walkExprs(dv._expressionSequence(), elementPath + "." + p._name(), sink);
                }
            });
        }
    }

    private static void walkConstraints(Iterable<? extends Constraint> constraints, String caller, BiConsumer<String, String> sink)
    {
        for (Constraint c : constraints)
        {
            walkConstraint(c, caller, sink);
        }
    }

    private static void walkConstraint(Constraint c, String caller, BiConsumer<String, String> sink)
    {
        FunctionDefinition body = c._functionDefinition();
        if (body != null)
        {
            walkExprs(body._expressionSequence(), caller, sink);
        }
        FunctionDefinition msg = c._messageFunction();
        if (msg != null)
        {
            walkExprs(msg._expressionSequence(), caller, sink);
        }
    }

    private static void walkExprs(Iterable<? extends ValueSpecification> exprs, String caller, BiConsumer<String, String> sink)
    {
        if (exprs == null)
        {
            return;
        }
        for (ValueSpecification vs : exprs)
        {
            walk(vs, caller, sink);
        }
    }

    private static void walk(ValueSpecification vs, String caller, BiConsumer<String, String> sink)
    {
        if (vs == null)
        {
            return;
        }
        if (vs instanceof FunctionExpression fe)
        {
            Function target = fe._func();
            if (target != null)
            {
                String targetPath = pathOf(target);
                if (targetPath != null && !targetPath.startsWith("meta::pure::metamodel::") && !targetPath.equals(caller))
                {
                    sink.accept(targetPath, caller);
                }
            }
            // Recurse into argument values — these can contain nested function
            // expressions, inline lambdas, collections, etc.
            walkExprs(fe._parametersValues(), caller, sink);
            return;
        }
        if (vs instanceof AtomicValue av)
        {
            Object value = av._value();
            if (value instanceof LambdaFunction lambda)
            {
                // Inline lambda body inherits the surrounding caller — there's
                // no separate named scope to attribute its references to.
                walkExprs(lambda._expressionSequence(), caller, sink);
            }
            // Other AtomicValue kinds (String / Integer literal, Enum instance,
            // PackageableElement reference, etc.) carry no function references
            // that aren't already covered by the genericType / Package_Pointer
            // recording paths.
            return;
        }
        if (vs instanceof Collection col)
        {
            walkExprs(col._values(), caller, sink);
        }
        // VariableExpression, GenericTypeAndMultiplicityHolder, and other VS
        // kinds have no nested function references; leaf case.
    }

    /**
     * Resolve a function reference to its index target path:
     * <ul>
     *   <li>{@link AbstractProperty} → {@code OwnerPath.propName} (sub-element form)</li>
     *   <li>{@link PackageableElement} (UserDefinedFunction, NativeFunction, etc.) → its full path</li>
     *   <li>Other {@link Function} subtypes (e.g. inline lambdas) → {@code null}</li>
     * </ul>
     */
    private static String pathOf(Function fn)
    {
        if (fn instanceof AbstractProperty prop)
        {
            Object owner = prop._owner();
            if (!(owner instanceof PackageableElement ownerPE))
            {
                return null;
            }
            String ownerPath = _PackageableElement.path(ownerPE);
            String propName = prop._name();
            if (ownerPath == null || propName == null)
            {
                return null;
            }
            return ownerPath + "." + propName;
        }
        if (fn instanceof PackageableElement pe)
        {
            return _PackageableElement.path(pe);
        }
        return null;
    }
}
