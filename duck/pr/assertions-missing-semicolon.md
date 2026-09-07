# One missing semicolon makes an assertion unrunnable

**Repo:** IHTSDO/snomed-release-validation-assertions · **patch:** `assertions-missing-semicolon.patch` · one character

`scripts/release-type/release-type-snapshot-delta-validation-expression-association-refset.sql`
ends its INSERT without a terminating `;`, and then has `commit;` on its own
line. Every statement splitter that splits on `;` therefore produces ONE
statement:

    insert into qa_result ... where ... (select max(...) ...)
    commit

which is not valid SQL in any dialect. The assertion cannot execute.

## How the corpus does it everywhere else

    release-type-delta-validation-...-refset.sql      ... or b.contentOriginId is null;
    component-centric-delta-inactive-...-FSN.sql      ... );
                                                      commit;

Either form is fine - a terminating semicolon, with or without a following
standalone `commit;`. This is the only one of the 453 scripts missing it, and
the only statement in a precompiled store of all 360 manifest-declared
assertions that contains the token `commit`.

## How it was found, and why it stayed hidden

A per-assertion harness that runs the whole corpus against RVF's own committed
regression fixture (`SnomedCT_RegressionTest_20130731` over `_20130131`) and
records what each assertion finds. On DuckDB the statement fails to parse:

    Parser Error: syntax error at or near "commit"

It stays invisible in production because `expressionassociationrefset_d` is
empty in the releases being validated, so the engine evaluates nothing, reports
zero findings, and the report reads as a clean pass. An assertion that CANNOT
run looks exactly like one that ran and found nothing.

The MySQL engine splits on `;` the same way, so this is very likely broken
there too - but a release with no expression-association delta rows cannot
demonstrate it either way, which is precisely why it has survived.

## What we did downstream

The DuckDB publisher now strips a trailing transaction-control token from a
statement and warns, so the assertion runs rather than erroring - but that is a
workaround for a malformed script, and the script is upstream's.
