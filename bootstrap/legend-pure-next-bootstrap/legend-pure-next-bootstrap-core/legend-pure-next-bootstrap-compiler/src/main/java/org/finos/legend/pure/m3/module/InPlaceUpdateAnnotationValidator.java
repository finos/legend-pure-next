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

import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement;
import platform.pure.metamodel.PackageableElement;
import platform.pure.metamodel.function.NativeFunction;
import platform.pure.metamodel.multiplicity.Multiplicity;
import platform.pure.metamodel.type.Type;
import platform.pure.metamodel.type.generics.GenericType;
import platform.pure.metamodel.type.generics.TypeParameter;
import platform.pure.metamodel.valuespecification.VariableExpression;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The updatability annotations are claims the compiler cannot check, so it checks what it can
 * (pure/specification/IN_PLACE_UPDATE_ANALYSIS.md): they are trusted on native functions only, a tag may only name
 * parameters of its function — and the lambda tags only function-typed ones — and {@code NewInstanceResult} and
 * {@code NewCollectionResult} only fit a result of that kind. Mirrors compiler-pure's {@code validateInPlaceUpdateAnnotations}.
 */
public final class InPlaceUpdateAnnotationValidator
{
    private InPlaceUpdateAnnotationValidator()
    {
    }

    public static List<CompilationError> validate(List<? extends PackageableElement> elements)
    {
        List<CompilationError> errors = new ArrayList<>();
        for (PackageableElement element : elements)
        {
            Set<String> stereotypes = InPlaceUpdateAnalyzer.inPlaceUpdateStereotypes(element);
            Map<String, Set<String>> tags = new TreeMap<>(InPlaceUpdateAnalyzer.inPlaceUpdateTags(element));
            if (stereotypes.isEmpty() && tags.isEmpty())
            {
                continue;
            }
            String path = _PackageableElement.path(element);
            if (!(element instanceof NativeFunction nf))
            {
                errors.add(new CompilationError("Updatability annotations are only trusted on native functions: '" + path + "' is not one", element._sourceInformation()));
                continue;
            }
            if (stereotypes.contains("NewCollectionResult") && InPlaceUpdateAnalyzer.singular(nf._returnMultiplicity()))
            {
                errors.add(new CompilationError("NewCollectionResult on '" + path + "': its result is a single value", nf._sourceInformation()));
            }
            if (stereotypes.contains("NewInstanceResult") && !(InPlaceUpdateAnalyzer.singular(nf._returnMultiplicity()) && canBeMutable(nf._returnGenericType(), nf._returnMultiplicity())))
            {
                errors.add(new CompilationError("NewInstanceResult on '" + path + "': its result is not a single class instance", nf._sourceInformation()));
            }
            // Only read contradicts any claim that something of the argument comes out.
            for (String name : tags.getOrDefault("onlyReads", Set.of()))
            {
                for (Map.Entry<String, Set<String>> other : tags.entrySet())
                {
                    if (InPlaceUpdateAnalyzer.CONTENTS_TAGS.contains(other.getKey()) && other.getValue().contains(name))
                    {
                        errors.add(new CompilationError("Updatability tag 'onlyReads' on '" + path + "' names '" + name + "', which '" + other.getKey() + "' says comes out of the call", nf._sourceInformation()));
                    }
                }
            }
            for (Map.Entry<String, Set<String>> tag : tags.entrySet())
            {
                boolean lambdas = "callsLambdaRepeatedly".equals(tag.getKey()) || "callsLambdaAtMostOnce".equals(tag.getKey());
                for (String name : tag.getValue())
                {
                    VariableExpression parameter = nf._parameters().detect(p -> name.equals(p._name()));
                    if (parameter == null)
                    {
                        errors.add(new CompilationError("Updatability tag '" + tag.getKey() + "' on '" + path + "' names '" + name + "', which is not one of its parameters", nf._sourceInformation()));
                    }
                    else if (lambdas && !isFunctionTyped(parameter))
                    {
                        errors.add(new CompilationError("Updatability tag '" + tag.getKey() + "' on '" + path + "' names '" + name + "', which is not a function parameter", nf._sourceInformation()));
                    }
                }
            }
        }
        return errors;
    }

    /** A collection, a class instance, or a type parameter that may stand for one (new, copy). */
    private static boolean canBeMutable(GenericType genericType, Multiplicity multiplicity)
    {
        return InPlaceUpdateAnalyzer.annotatable(genericType, multiplicity)
                || (genericType != null && _GenericType.type(genericType) instanceof TypeParameter);
    }

    private static boolean isFunctionTyped(VariableExpression parameter)
    {
        Type type = parameter._genericType() == null ? null : _GenericType.type(parameter._genericType());
        String typePath = type instanceof PackageableElement pe ? _PackageableElement.path(pe) : null;
        return typePath != null && typePath.startsWith("platform::pure::metamodel::function::");
    }
}
