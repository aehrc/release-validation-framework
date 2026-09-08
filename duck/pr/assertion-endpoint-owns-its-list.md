# GET /assertions writes to the corpus it is listing

**Repo:** IHTSDO/release-validation-framework · **files:** `AssertionController`, and the same shape in `MysqlFailuresExtractor`

Reported by Attila Edelenyi against our fork; the code is upstream's.

## The shape

`AssertionController.getAssertionsAndJoinGroups()` returns whatever
`assertionService.findAll()` returned, and `getAssertions` then appends to it:

```java
List<Assertion> assertions = getAssertionsAndJoinGroups();   // not ours
if (includeDroolsRules)          assertions.addAll(...);      // written as if it were
if (includeTraceabilityAssertions) assertions.addAll(...);
if (includeSEPAssertions)        assertions.addAll(...);
```

It also calls `addGroup` on the assertion OBJECTS in that list.

## Why upstream has never seen it

`AssertionServiceImpl.findAll()` delegates to a Spring Data repository, so every
call returns a fresh list of fresh entities. The endpoint does own its copy, and
both the append and the `addGroup` are harmless.

## Why it is still a bug

The endpoint's ownership is an accident of one implementation. We have a second
`AssertionService` - a precompiled assertion corpus, loaded once and shared -
and against it:

* the append throws `UnsupportedOperationException`, because the corpus is held
  as `List.copyOf`, so `GET /assertions?includeDroolsRules=true` answers **HTTP
  500** with a stack three frames from anything that mentions a list;
* `addGroup` writes to objects shared between requests. Idempotent, because
  group membership is already resolved when the corpus loads - but "idempotent"
  is not "thread-safe", and two concurrent requests mutating one
  `LinkedHashSet` is a data race for no gain.

And the version that does NOT throw is worse. Had the corpus handed out a
mutable internal list, the Drools rules would have been appended to the live
corpus; every subsequent validation would have enumerated them; and the engine,
having no SQL for a Drools rule, would have reported each one as "store and
assertion corpus are out of step". A page view would have degraded every later
run, and nothing would have pointed at the page.

## The fix

```java
List<Assertion> assertions = new ArrayList<>(assertionService.findAll());
```

plus adding a group only when it is absent, which makes the join a pure read
wherever membership is already correct. One allocation of a 400-element list per
request, against a class of failure that only appears in someone else's
implementation.

`MysqlFailuresExtractor.getAssertionsAndJoinGroups` has the same shape and does
not append, so it is safe today; the same one-line copy makes it safe by
construction rather than by inspection.

## Evidence

Our tests: `AssertionControllerOwnershipTest` (the endpoint, all four flag
combinations - two of them threw before the fix) and
`AssertionCorpusOwnershipTest` (the corpus refuses to be edited, and still
carries its groups without a caller telling it).
