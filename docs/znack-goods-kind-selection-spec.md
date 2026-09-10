# Znack goods-kind selection

Approved in conversation: choose Вид товара [12] directly for each group; no WB name guessing.

## Scope and plan

1. Prepare drafts without attribute 12; carry its current API schema and category name.
   Group by WB subject, exact TN VED, Znack category and attribute schema. Test grouping,
   exact preset validation and preservation of other draft fields.
2. Single and bulk registration show the same scrollable, resizable dialog with each
   group's SKUs and an initially empty non-editable dropdown. No default/guessed value.
   Skip unselected groups; show final ready/invalid/skipped counts before bulk enqueue.
   Empty upstream options block that group, not valid groups. Cancel allocates no GTIN.
3. Review, Maven/FXML tests, Node contracts, production release 1.1.23 after CI packaging.

Java 25/JavaFX; reuse records, immutable maps and existing queue. Sources under
integration/znack/registration and ui/znackregistration; matching JUnit tests in src/test.
Example: `record KindGroup(long subjectId, String tnved, long categoryId, Attribute attribute) {}`.
Commands: `./mvnw -B clean verify`; `node --test tools/*.test.mjs`.
Always preserve per-shop context and validate API presets. Never edit supplied databases,
allocate GTIN during selection, persist guessed mappings, or include secrets in releases.
No schema migration/dependency change. Existing legal-document configuration remains unchanged.
