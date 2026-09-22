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

import org.finos.legend.pure.m3.PdbTypes;
import org.finos.legend.pure.m3.meta.pure.metamodel.PackageableElement;
import org.finos.legend.pure.m3.meta.pure.metamodel.function.property.Property;
import org.finos.legend.pure.m3.meta.pure.metamodel.multiplicity.ConcreteMultiplicity;
import org.finos.legend.pure.m3.meta.pure.metamodel.multiplicity.Multiplicity;
import org.finos.legend.pure.m3.meta.pure.metamodel.type.Class;
import org.finos.legend.pure.m3.meta.pure.metamodel.type.generics.GenericType;
import org.finos.legend.pure.m3.meta.pure.metamodel.type.generics.GenericTypeValue;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * Java platform spike: open PDBs lazily and navigate the Pure metamodel through
 * the generated typed interfaces. {@code java MetamodelSpike <m3.fbs> <class path> <pdb>...}
 */
public final class MetamodelSpike
{
    private MetamodelSpike()
    {
    }

    public static void main(String[] args) throws Exception
    {
        long start = System.nanoTime();
        PdbRuntime runtime = PdbRuntime.open(Path.of(args[0]), PdbTypes::create,
                Arrays.stream(args, 2, args.length).map(Path::of).toArray(Path[]::new));
        long opened = System.nanoTime();

        Class cls = (Class) runtime.element(args[1]);
        System.out.println(cls._purePath() + " " + cls.name() + " in package " + cls.package_().name());
        for (Property property : cls.properties())
        {
            System.out.println("  " + property.name() + " : " + typeName(property.genericType()) + multiplicity(property.multiplicity())
                    + (property.owner() == cls ? "  (owner is the same object)" : "  (OWNER MISMATCH: " + property.owner() + ")"));
        }
        System.out.println("  generalizations: " + cls.generalizations().stream().map(g -> typeName(g.general())).toList());
        System.out.println("  identity: " + (runtime.element(args[1]) == cls));
        System.out.printf("open %d ms, navigate %d ms%n", (opened - start) / 1_000_000, (System.nanoTime() - opened) / 1_000_000);
    }

    /** `Name<Arg, ...>` of a generic type, read through the typed interfaces. */
    static String typeName(GenericType genericType)
    {
        if (!(genericType instanceof GenericTypeValue value))
        {
            return String.valueOf(genericType);
        }
        String name = value.type() instanceof PackageableElement element ? element.name() : String.valueOf(value.type());
        return value.typeArguments().isEmpty() ? name : name + value.typeArguments().stream().map(MetamodelSpike::typeName).toList();
    }

    static String multiplicity(Multiplicity multiplicity)
    {
        if (multiplicity instanceof ConcreteMultiplicity concrete)
        {
            Long upper = concrete.upperBound() == null ? null : concrete.upperBound().value();
            return "[" + concrete.lowerBound().value() + ".." + (upper == null ? "*" : upper) + "]";
        }
        return "[" + multiplicity + "]";
    }
}
