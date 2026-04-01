# M3 Metamodel — Taxonomies

## Main Taxonomies (`mainTaxonomy`)

The `mainTaxonomy` stereotype marks polymorphic root classes. All subtypes are serialized **inline** with a `_type` discriminator in both FBS (union) and JSON protocol (`@JsonTypeInfo`).

### GenericType

```
GenericType
├── GenericTypeOperation
├── UndefinedGenericType
└── GenericTypeValue
    ├── AdHocGenericTypeValue
    │   ├── InferredGenericType
    │   └── UserDefinedGenericType
    └── PackageableGenericType
        ├── InferredPackageableGenericType
        └── UserDefinedPackageableGenericType
```

### PackageableElement

`Enumeration` has two generalizations: `PackageableElement` and `SimplePropertyOwner`.

```
PackageableElement
├── Enumeration
├── Measure
├── Package
├── PackageableFunction
│   ├── NativeFunction
│   └── UserDefinedFunction
├── PackageableGenericType
│   ├── InferredPackageableGenericType
│   └── UserDefinedPackageableGenericType
├── PackageableMultiplicity
│   ├── InferredPackageableMultiplicity
│   └── UserDefinedPackageableMultiplicity
├── PrimitiveType
├── Profile
└── SimplePropertyOwner
    ├── Enumeration
    └── PropertyOwner
        ├── Association
        └── Class
```

### ValueSpecification

```
ValueSpecification
├── AtomicValue
├── Collection
├── FunctionExpression
│   ├── DotApplication
│   └── FunctionApplication
│       ├── ArrowInvocation
│       └── FunctionInvocation
├── GenericTypeAndMultiplicityHolder
│   ├── CompilerGenericTypeAndMultiplicityHolder
│   └── UserDefinedGenericTypeAndMultiplicityHolder
└── VariableExpression
```

## Property-Level Taxonomies (`nonPointerSubtypes`)

The `nonPointerSubtypes` tagged value marks properties whose type is normally a **pointer** but certain subtypes are serialized **inline**. In FBS, a union of `PointerRef` + inline `Def` tables is used.

### Type

```
Type
├── Class                              [pointer]
├── DataType
│   ├── Enumeration                    [pointer]
│   ├── Measure                        [pointer]
│   └── PrimitiveType                  [pointer]
├── FunctionType                       [inline]
├── RelationType                       [inline]
└── TypeParameter                      [inline]
```

### FunctionDefinition

```
FunctionDefinition
├── LambdaFunction                     [inline]
├── QualifiedProperty                  [pointer]
└── UserDefinedFunction                [pointer]
```

### Multiplicity

```
Multiplicity
├── ConcreteMultiplicity
│   ├── AdHocMultiplicity
│   │   ├── InferredAdHocMultiplicity           [inline]
│   │   └── UserDefinedAdHocMultiplicity        [inline]
│   └── PackageableMultiplicity
│       ├── InferredPackageableMultiplicity     [pointer]
│       └── UserDefinedPackageableMultiplicity  [pointer]
├── MultiplicityParameter
│   ├── InferredMultiplicityParameter           [inline]
│   └── UserDefinedMultiplicityParameter        [inline]
└── UndefinedMultiplicity
```

## Other Taxonomies

### PrimitiveType

```
PrimitiveType
├── Binary
├── Boolean
├── Byte
├── Date
│   ├── DateTime
│   ├── LatestDate
│   └── StrictDate
├── Number
│   ├── Decimal
│   ├── Float
│   └── Integer
├── StrictTime
└── String
```

