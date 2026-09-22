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
import org.finos.legend.pure.m3.meta.pure.metamodel.function.property.Property;
import org.finos.legend.pure.m3.meta.pure.metamodel.function.property.PropertyImpl;
import org.finos.legend.pure.m3.meta.pure.metamodel.type.Class;
import org.finos.legend.pure.m3.meta.pure.metamodel.type.ClassImpl;

import java.nio.file.Path;
import java.util.List;

/**
 * Java platform spike: one object model for PDB-read and run-time-built
 * instances. {@code java ModelSpike <m3.fbs> <core.pdb>}
 */
public final class ModelSpike
{
    private ModelSpike()
    {
    }

    public static void main(String[] args) throws Exception
    {
        PdbRuntime runtime = PdbRuntime.open(Path.of(args[0]), PdbTypes::create, Path.of(args[1]));
        Class pdbClass = (Class) runtime.element("meta::pure::metamodel::type::Class");
        Property pdbProperty = pdbClass.properties().get(1);

        // A class built at run time, whose property reuses a type and multiplicity read from the PDB.
        ClassImpl person = new ClassImpl()._name("Person");
        PropertyImpl firstName = new PropertyImpl()
                ._name("firstName")
                ._genericType(pdbProperty.genericType())
                ._multiplicity(pdbProperty.multiplicity())
                ._owner(person);
        person._properties(List.of(firstName))._properties_add(new PropertyImpl()._name("lastName")._owner(person));
        print("built", person);

        // A copy of a PDB element: overridden name, other properties still read lazily from the PDB.
        ClassImpl copy = ((ClassImpl) pdbClass)._copy()._name("ClassCopy");
        print("copy of PDB element", copy);
        System.out.println("original untouched: " + pdbClass.name());
    }

    private static void print(String label, Class cls)
    {
        System.out.println(label + ": " + cls.name() + " (" + cls._purePath() + ")");
        for (Property property : cls.properties())
        {
            String type = property.genericType() == null ? "?" : MetamodelSpike.typeName(property.genericType());
            String multiplicity = property.multiplicity() == null ? "" : MetamodelSpike.multiplicity(property.multiplicity());
            System.out.println("  " + property.name() + " : " + type + multiplicity + (property.owner() == cls ? "  (owner is this object)" : "  (owner: " + property.owner() + ")"));
        }
    }
}
