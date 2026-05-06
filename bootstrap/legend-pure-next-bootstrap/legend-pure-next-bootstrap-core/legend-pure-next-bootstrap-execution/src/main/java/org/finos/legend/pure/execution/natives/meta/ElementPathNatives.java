// Copyright 2024 Goldman Sachs
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

package org.finos.legend.pure.execution.natives.meta;

import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.execution.DynamicInstance;
import org.finos.legend.pure.execution.NativeRepository.LazyNativeImpl;
import org.finos.legend.pure.execution.NativeRepository.NativeImpl;
import org.finos.legend.pure.execution.ValueSpecificationEvaluator;
import org.finos.legend.pure.execution._E_ValueSpecification;
import org.finos.legend.pure.execution.natives.collection.CollectionNatives;
import org.finos.legend.pure.m3.module.MetadataAccess;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ElementPathNatives
{
    public static void register(Map<String, NativeImpl> natives,
                                Map<String, LazyNativeImpl> lazyNatives,
                                MetadataAccess resolver)
    {
        // pathToElement(String[1], String[1]) : PackageableElement[1]
        natives.put("pathToElement_String_1__String_1__PackageableElement_1_", (args, eval, genericType, multiplicity) ->
        {
            String path = (String) _E_ValueSpecification.unwrap(args.get(0));
            String separator = (String) _E_ValueSpecification.unwrap(args.get(1));
            if (!"::".equals(separator))
            {
                path = path.replace(separator, "::");
            }
            if (path.isEmpty() || "::".equals(path))
            {
                if (resolver != null)
                {
                    PackageableElement rootPkg = resolver.getElement("");
                    if (rootPkg == null)
                    {
                        rootPkg = resolver.getElement("::");
                    }
                    if (rootPkg != null)
                    {
                        return _E_ValueSpecification.wrap(rootPkg, genericType, multiplicity, resolver);
                    }
                }
                return _E_ValueSpecification.wrap("::", genericType, multiplicity, resolver);
            }
            if (resolver == null)
            {
                throw new RuntimeException("pathToElement not connected to model: " + path);
            }
            PackageableElement element = resolver.getElement(path);
            if (element == null)
            {
                throw new RuntimeException("Element not found: " + path);
            }
            return _E_ValueSpecification.wrap(element, genericType, multiplicity, resolver);
        });

        // lenientPathToElement(String[1], String[1]) : PackageableElement[0..1]
        natives.put("lenientPathToElement_String_1__String_1__PackageableElement_$0_1$_", (args, eval, genericType, multiplicity) ->
        {
            String path = (String) _E_ValueSpecification.unwrap(args.get(0));
            String separator = (String) _E_ValueSpecification.unwrap(args.get(1));
            if (!"::".equals(separator))
            {
                path = path.replace(separator, "::");
            }
            if (resolver == null)
            {
                return _E_ValueSpecification.wrap(null, genericType, multiplicity, resolver);
            }
            return _E_ValueSpecification.wrap(resolver.getElement(path), genericType, multiplicity, resolver);
        });

        // elementToPath(PackageableElement[1], String[1], Boolean[1]) : String[1]
        natives.put("elementToPath_PackageableElement_1__String_1__String_1_", (args, eval, genericType, multiplicity) ->
        {
            Object element = _E_ValueSpecification.unwrap(args.get(0));
            Object separatorObj = _E_ValueSpecification.unwrap(args.get(1));
            String separator = separatorObj != null ? separatorObj.toString() : "::";

            if (element instanceof PackageableElement pe)
            {
                return _E_ValueSpecification.wrap(elementToPathString(pe, separator, resolver), genericType, multiplicity, resolver);
            }
            if (element instanceof DynamicInstance di)
            {
                return _E_ValueSpecification.wrap(elementToPathDynamic(di, separator, eval), genericType, multiplicity, resolver);
            }
            if ("::".equals(String.valueOf(element)))
            {
                return _E_ValueSpecification.wrap("", genericType, multiplicity, resolver);
            }
            return _E_ValueSpecification.wrap(String.valueOf(element), genericType, multiplicity, resolver);
        });

        // elementPath(PackageableElement[1]) : PackageableElement[1..*]
        natives.put("elementPath_PackageableElement_1__PackageableElement_$1_MANY$_", (args, eval, genericType, multiplicity) ->
        {
            Object element = _E_ValueSpecification.unwrap(args.get(0));
            List<Object> path = new ArrayList<>();
            if (element instanceof DynamicInstance di)
            {
                buildDynamicElementPath(di, path, eval);
            }
            else if (element instanceof PackageableElement pe)
            {
                buildElementPath(pe, path);
            }
            List<ValueSpecification> pathVSList = new ArrayList<>(path.size());
            for (Object p : path)
            {
                pathVSList.add(_E_ValueSpecification.wrap(p, genericType, null, resolver));
            }
            return CollectionNatives.makeCollection(pathVSList, resolver);
        });

        // elementToPath(PackageableElement[1]) : String[1]
        NativeImpl elementToPath = (args, eval, genericType, multiplicity) ->
        {
            Object element = _E_ValueSpecification.unwrap(args.get(0));
            if (element instanceof PackageableElement pe)
            {
                return _E_ValueSpecification.wrap(elementToPathString(pe, "::", resolver), genericType, multiplicity, resolver);
            }
            if (element instanceof DynamicInstance di)
            {
                return _E_ValueSpecification.wrap(elementToPathDynamic(di, "::", eval), genericType, multiplicity, resolver);
            }
            if ("::".equals(String.valueOf(element)))
            {
                return _E_ValueSpecification.wrap("", genericType, multiplicity, resolver);
            }
            return _E_ValueSpecification.wrap(String.valueOf(element), genericType, multiplicity, resolver);
        };
        natives.put("elementToPath_PackageableElement_1__String_1_", elementToPath);
        natives.put("elementToPath", elementToPath);

        // elementToPath(Type[1], String[1]) : String[1]
        natives.put("elementToPath_Type_1__String_1__String_1_", (args, eval, genericType, multiplicity) ->
        {
            Object element = _E_ValueSpecification.unwrap(args.get(0));
            Object separatorObj = _E_ValueSpecification.unwrap(args.get(1));
            String separator = separatorObj != null ? separatorObj.toString() : "::";
            if (element instanceof PackageableElement pe)
            {
                return _E_ValueSpecification.wrap(elementToPathString(pe, separator, resolver), genericType, multiplicity, resolver);
            }
            return _E_ValueSpecification.wrap("", genericType, multiplicity, resolver);
        });
    }

    // =========================================================================
    // Helper methods
    // =========================================================================

    public static String elementToPathString(PackageableElement pe, String separator)
    {
        return elementToPathString(pe, separator, null);
    }

    /**
     * Cached overload — uses the resolver's elementPathCache via the
     * {@code path(pe, resolver)} helper. Self-host hits this once per
     * unique element rather than re-walking the parent chain on every
     * call.
     */
    public static String elementToPathString(PackageableElement pe, String separator,
            org.finos.legend.pure.m3.module.MetadataAccess resolver)
    {
        String path = org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(pe, resolver);
        if ("::".equals(path))
        {
            return "";
        }
        if ("::".equals(separator))
        {
            return path;
        }
        return path.replace("::", separator);
    }

    private static String elementToPathDynamic(DynamicInstance di, String separator, ValueSpecificationEvaluator eval)
    {
        List<String> segments = new ArrayList<>();
        buildDynamicPathSegments(di, segments, eval);
        return String.join(separator, segments);
    }

    private static void buildDynamicPathSegments(DynamicInstance di, List<String> segments, ValueSpecificationEvaluator eval)
    {
        Object pkg = di.get("package");
        if (pkg instanceof DynamicInstance parent)
        {
            buildDynamicPathSegments(parent, segments, eval);
        }
        else if (pkg instanceof PackageableElement parent)
        {
            buildAllNamedPackageSegments(parent, segments);
        }
        Object name = di.get("name");
        if (name instanceof String s && !s.isEmpty())
        {
            segments.add(s);
        }
    }

    private static void buildAllNamedPackageSegments(PackageableElement pe, List<String> segments)
    {
        if (pe._package() instanceof PackageableElement parent)
        {
            buildAllNamedPackageSegments(parent, segments);
        }
        String name = pe._name();
        if (name != null && !name.isEmpty())
        {
            segments.add(name);
        }
    }

    private static void buildElementPath(PackageableElement pe, List<Object> path)
    {
        if (pe._package() instanceof PackageableElement parent)
        {
            buildElementPath(parent, path);
        }
        String name = pe._name();
        if (name != null && !name.isEmpty())
        {
            String elPath = org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(pe);
            if (!"::".equals(elPath))
            {
                path.add(pe);
            }
        }
    }

    private static void buildDynamicElementPath(DynamicInstance di, List<Object> path, ValueSpecificationEvaluator eval)
    {
        Object pkg = di.get("package");
        if (pkg instanceof DynamicInstance parent)
        {
            buildDynamicElementPath(parent, path, eval);
        }
        else if (pkg instanceof PackageableElement parent)
        {
            buildElementPath(parent, path);
        }
        path.add(di);
    }
}
