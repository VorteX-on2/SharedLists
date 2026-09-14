## Agent skills

### Issue tracker

Issues are tracked in this repository's GitHub Issues. See `docs/agents/issue-tracker.md`.

### Triage labels

Triage uses the default five canonical label names. See `docs/agents/triage-labels.md`.

### Domain docs

Domain documentation uses the single-context layout. See `docs/agents/domain.md`.

## Kotlin coding standards

These rules apply to every Kotlin source file:

- Do not use fully qualified names in code. Use imports instead, except when a fully qualified name is required to resolve a naming conflict.
- Imports must use fully qualified names and must never use wildcard imports.
- Order imports alphabetically. Declare static-style imports (top-level functions, properties, and object members) before type imports.
- Apply SOLID principles when defining files, objects, classes, and their collaborators. Prefer cohesive types with one responsibility and explicit seams for dependencies.
- Order declarations in each file, class, object, enum, companion, and inner class as follows: constants, fields, initializers, constructors, methods, enums, inner classes, then companions.
- Within each declaration group, order static members first, then by visibility, then alphabetically.
