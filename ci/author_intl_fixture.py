#!/usr/bin/env python3
"""Authors the international content the fixture is missing, after the MRCM and
AMT generators have run.

87 of the 360 international assertions execute and find nothing. This closes the
clusters that share a mechanism, each read out of the assertion rather than
guessed:

* 79 of the 87 read `moduledependencyrefset_s`, and the reason the whole
  `mdrs-violation-*` family is dead is one column: the fixture's three MDRS rows
  are stamped 20140731 while the release is 20130731. Those assertions bind
  `m.effectivetime = '<version>'`, no row matches, the join is empty, and
  nothing can fire whatever the components look like.

* seven `file-centric-snapshot-language-unique-fsn-<cc>` assertions each key off
  a national extension module and want an active concept in it with no FSN in
  the expected language refset.

* the association family wants a row pointing at itself and a duplicate key.

Idempotence is keyed on an id marker rather than a module, because these rows
deliberately belong to OTHER modules - that is the whole point of the national
and MDRS cases.
"""
import pathlib

ROOT = pathlib.Path('/data/Projects/rvf-catchup/src/test/resources')
PREV, CURR = '20130131', '20130731'

CORE_MODULE = '900000000000207008'
MODEL_MODULE = '900000000000012004'
MDRS_REFSET = '900000000000534007'
FSN = '900000000000003001'
SYNONYM = '900000000000013009'
# 900000000000074008 is PRIMITIVE. A previous value of 900000000000900001 is
# not a definition status at all, and 87 authored concepts carried it - so any
# assertion checking the definition status was firing on an invalid VALUE rather
# than on the case it was authored for, and one assertion that wanted a
# primitive concept with an equivalence axiom could not match at all.
PRIMITIVE = '900000000000074008'
CASE_INSENSITIVE = '900000000000448009'
HISTORICAL_SAME_AS = '900000000000527005'

# The national extension modules the unique-FSN assertions key off, read from
# their WHERE clauses. Each wants an active concept in that module which has no
# FSN in the language refset that module's assertion expects.
NATIONAL_MODULES = [
    ('11000220105', 'ie'), ('2011000195101', 'ch'), ('554471000005108', 'dk'),
    ('51000202101', 'no'), ('45991000052106', 'se'), ('11000172109', 'be'),
    ('11000181102', 'ee'),
]

CONCEPT_P = '9975'
DESC_P = '9976'
UUID_MARK = 'c0000001-'


def verhoeff(digits: str) -> str:
    d = [[0, 1, 2, 3, 4, 5, 6, 7, 8, 9], [1, 2, 3, 4, 0, 6, 7, 8, 9, 5],
         [2, 3, 4, 0, 1, 7, 8, 9, 5, 6], [3, 4, 0, 1, 2, 8, 9, 5, 6, 7],
         [4, 0, 1, 2, 3, 9, 5, 6, 7, 8], [5, 9, 8, 7, 6, 0, 4, 3, 2, 1],
         [6, 5, 9, 8, 7, 1, 0, 4, 3, 2], [7, 6, 5, 9, 8, 2, 1, 0, 4, 3],
         [8, 7, 6, 5, 9, 3, 2, 1, 0, 4], [9, 8, 7, 6, 5, 4, 3, 2, 1, 0]]
    p = [[0, 1, 2, 3, 4, 5, 6, 7, 8, 9], [1, 5, 7, 6, 2, 8, 3, 0, 9, 4],
         [5, 8, 0, 3, 7, 9, 6, 1, 4, 2], [8, 9, 1, 6, 0, 4, 3, 5, 2, 7],
         [9, 4, 5, 3, 1, 2, 6, 8, 7, 0], [4, 2, 8, 6, 5, 7, 3, 9, 0, 1],
         [2, 7, 9, 3, 8, 0, 6, 4, 1, 5], [7, 0, 4, 6, 9, 1, 3, 2, 5, 8]]
    inv = [0, 4, 3, 2, 1, 5, 6, 7, 8, 9]
    c = 0
    for i, ch in enumerate(reversed(digits)):
        c = d[c][p[(i + 1) % 8][int(ch)]]
    return str(inv[c])


def sctid(prefix: str, seq: int, partition: str) -> str:
    body = f'{prefix}{seq:05d}{partition}'
    return body + verhoeff(body)


class Rows:
    def __init__(self):
        self.concept, self.desc, self.mdrs, self.assoc = [], [], [], []
        self.descriptor, self.extmap, self.owl, self.complexmap, self.rel = [], [], [], [], []
        self.desctype, self.simplemap = [], []
        self.textdef, self.attrvalue, self.attrvalue_prev = [], [], []
        self.lang = []
        self.assoc_prev = []
        self.ccsrefset = []
        self._n = 0

    def uuid(self):
        self._n += 1
        h = f'{self._n:023x}'
        return UUID_MARK + f'{h[:4]}-{h[4:8]}-{h[8:12]}-{h[12:24]}'

    def next_id(self, prefix, partition):
        self._n += 1
        return sctid(prefix, self._n, partition)


def author_mdrs_for_this_release(r: Rows):
    """Module dependency rows stamped with THIS release's version.

    The fixture's three rows are stamped 20140731, a year after the release they
    ship in. The `mdrs-violation-*` family binds `m.effectivetime` to the
    release version and `m.moduleid` to its module, so with no row at 20130731
    the join is empty and 79 assertions cannot fire - not because the content is
    clean but because the check has nothing to stand on.

    Two rows: the core module depending on the model module, which is what a
    real international release declares, and the same for the other module the
    fixture uses. The AMT module the fixture now carries is deliberately NOT
    declared, which is exactly the violation the family looks for - a component
    in a module this release never said it depended on.
    """
    for module in (CORE_MODULE, '449080006'):
        r.mdrs.append((r.uuid(), CURR, '1', module, MDRS_REFSET, MODEL_MODULE, CURR, CURR))

    # With the family alive, its content checks have something to stand on, and
    # each wants one specific inconsistency. One row each, so a finding names a
    # cause rather than a row failing six assertions at once.
    other = '449080006'
    third = '32570231000036109'

    # sourceEffectiveTime must equal the row's own effectiveTime
    r.mdrs.append((r.uuid(), CURR, '1', CORE_MODULE, MDRS_REFSET, third, PREV, PREV))
    # a dependency cannot be on a version LATER than the source
    r.mdrs.append((r.uuid(), CURR, '1', other, MDRS_REFSET, third, PREV, CURR))
    # the module dependency refset is 900000000000534007 and nothing else
    r.mdrs.append((r.uuid(), CURR, '1', CORE_MODULE, '900000000000497000', MODEL_MODULE, CURR, CURR))
    # a dependency on a module whose concept is inactive in this release
    r.mdrs.append((r.uuid(), CURR, '1', CORE_MODULE, MDRS_REFSET, '703649004', CURR, CURR))
    # A depends on B and B on C, with no A -> C row: the set is not
    # transitively closed, which is what makes a dependency chain unresolvable.
    r.mdrs.append((r.uuid(), CURR, '1', third, MDRS_REFSET, other, CURR, CURR))
    # two rows for one target at different versions - version skew
    r.mdrs.append((r.uuid(), CURR, '1', other, MDRS_REFSET, MODEL_MODULE, CURR, PREV))
    return len(r.mdrs)


def author_national_module_concepts(r: Rows):
    """An active concept in each national extension module with a synonym but no
    FSN, which is what `language-unique-fsn-<cc>` checks for."""
    for module, code in NATIONAL_MODULES:
        cid = r.next_id(CONCEPT_P, '00')
        r.concept.append((cid, CURR, '1', module, PRIMITIVE))
        did = r.next_id(DESC_P, '01')
        # A synonym only. No FSN at all, in any language refset.
        r.desc.append((did, CURR, '1', module, cid, 'en', SYNONYM,
                       f'National extension concept with no FSN ({code})', CASE_INSENSITIVE))
    return len(NATIONAL_MODULES)


def author_association_defects(r: Rows):
    """A historical association pointing at its own referenced component, and a
    duplicate association key - one row per assertion in that family."""
    target = r.next_id(CONCEPT_P, '00')
    r.concept.append((target, CURR, '1', CORE_MODULE, PRIMITIVE))
    did = r.next_id(DESC_P, '01')
    r.desc.append((did, CURR, '1', CORE_MODULE, target, 'en', FSN,
                   'Concept associated with itself (finding)', CASE_INSENSITIVE))

    # referencedComponentId = targetComponentId: a concept replaced by itself.
    r.assoc.append((r.uuid(), CURR, '1', CORE_MODULE, HISTORICAL_SAME_AS, target, target))
    # the same association twice under different member ids
    other = '703860006'
    for _ in range(2):
        r.assoc.append((r.uuid(), CURR, '1', CORE_MODULE, HISTORICAL_SAME_AS, target, other))
    return 3


# Concepts the fixture already holds, used as referenced components so a finding
# is about the row's own defect rather than a dangling reference.
KNOWN_CONCEPT = '703860006'
INACTIVE = '703649004'
ABSENT = '999999999999'
LATER = '20140131'          # after this release, which is what mdrs-violation wants
NEVER_GROUPED_ATTRIBUTE = '703155005'   # ACTIVE_B, which ci/author_mrcm_fixture.py declares grouped=0


def author_map_and_axiom_defects(r: Rows):
    """One row per remaining finding-capable assertion, each into the file that
    assertion's table is loaded from - which is not always the obvious one:
    complexmaprefset_s comes from der2_iissscRefset_*ComplexMap and
    extendedmaprefset_s from der2_iisssccRefset_*ExtendedMap, six s-and-c
    characters apart.
    """
    n = 0

    # mdrs-violation-<refsetdescriptor|extendedmaprefset>: a row whose module is
    # one this release declares a dependency ON, stamped LATER than the version
    # that dependency pins. That is the violation: content from a module version
    # the release never said it depended on.
    r.descriptor.append((r.uuid(), LATER, '1', MODEL_MODULE, '900000000000456007',
                         KNOWN_CONCEPT, 'Referenced component', '900000000000461009', '1'))
    n += 1
    r.extmap.append((r.uuid(), LATER, '1', MODEL_MODULE, '447562003', KNOWN_CONCEPT,
                     '1', '1', 'TRUE', 'ALWAYS A00', 'A00', '447561005', '447637006'))
    n += 1

    # referencedComponentID-in-extended-map: an active member whose referenced
    # component is not an active concept, in a refset that is not one of the two
    # the assertion excludes.
    r.extmap.append((r.uuid(), CURR, '1', CORE_MODULE, '447563008', ABSENT,
                     '1', '1', 'TRUE', 'ALWAYS A01', 'A01', '447561005', '447637006'))
    n += 1

    # owl-expression-unique-active-axiom-for-same-concept: the same axiom twice,
    # active, for one concept.
    axiom = f'SubClassOf(:{KNOWN_CONCEPT} :138875005)'
    for _ in range(2):
        r.owl.append((r.uuid(), CURR, '1', CORE_MODULE, '733073007', KNOWN_CONCEPT, axiom))
    n += 1

    # complexmap-blank-target: a member in map group 2 with no sibling row in
    # that group carrying a target, which leaves the group unmappable.
    r.complexmap.append((r.uuid(), CURR, '1', CORE_MODULE, '447562003', KNOWN_CONCEPT,
                         '2', '1', 'TRUE', 'ALWAYS A02', '', '447561005'))
    n += 1

    # mrcm-never-grouped-relationship-grouped: a relationship whose type is
    # declared ungrouped by the MRCM attribute domain refset, used in a group.
    # The declaration comes from ci/author_mrcm_fixture.py, which is why the
    # generators run in order.
    rid = sctid('9977', 1, '02')
    r.rel.append((rid, CURR, '1', CORE_MODULE, KNOWN_CONCEPT, INACTIVE, '1',
                  NEVER_GROUPED_ATTRIBUTE, '900000000000011006', '900000000000451002'))
    n += 1

    # description-valid-characters: an FSN carrying a character the corpus bans.
    # A tab is one of them and is deliberately NOT used - a tab inside a term
    # splits the row and produces a ragged file, which is a different defect and
    # one this fixture already had to be repaired for.
    cid = r.next_id(CONCEPT_P, '00')
    r.concept.append((cid, CURR, '1', CORE_MODULE, PRIMITIVE))
    did = r.next_id(DESC_P, '01')
    r.desc.append((did, CURR, '1', CORE_MODULE, cid, 'en', FSN,
                   'Concept with an @ in its fully specified name (finding)', CASE_INSENSITIVE))
    n += 1
    return n


def author_duplicate_keys_and_modules(r: Rows):
    """Duplicated member ids, and a member on the wrong module.

    A refset file may not use one member id twice, and the -unique-id assertions
    exist for that. They had nothing to find because every file in the fixture
    was internally tidy. One duplicate per file is enough, and it is a real
    failure mode: the same id emitted twice is what a bad merge produces.
    """
    n = 0
    # refsetdescriptor: one id twice, and separately one (refset, attributeOrder)
    # twice, which is the other key that has to be unique
    dup_descriptor = r.uuid()
    for order in ('2', '3'):
        r.descriptor.append((dup_descriptor, CURR, '1', CORE_MODULE, '900000000000456007',
                             KNOWN_CONCEPT, 'Referenced component', '900000000000461009', order))
    n += 1
    for _ in range(2):
        r.descriptor.append((r.uuid(), CURR, '1', CORE_MODULE, '900000000000456007',
                             KNOWN_CONCEPT, 'Attribute value', '900000000000461009', '4'))
    n += 1

    # module dependency: one id twice
    dup_mdrs = r.uuid()
    for target in (MODEL_MODULE, CORE_MODULE):
        r.mdrs.append((dup_mdrs, CURR, '1', CORE_MODULE, MDRS_REFSET, target, CURR, CURR))
    n += 1

    # description type: one id twice
    dup_type = r.uuid()
    for length in ('255', '4096'):
        r.desctype.append((dup_type, CURR, '1', CORE_MODULE, '900000000000538005',
                           '900000000000003001', '900000000000540000', length))
    n += 1

    # simple map: a member on a module that is not the core one, which the
    # assertion only checks when no included-modules filter is set - the case
    # this fixture runs in
    r.simplemap.append((r.uuid(), CURR, '1', MODEL_MODULE, '900000000000497000',
                        KNOWN_CONCEPT, 'A00'))
    n += 1
    return n


BE_MODULE = '11000172109'
BE_DUTCH_REFSET = '21000172104'
BE_FRENCH_REFSET = '31000172101'
TEXT_DEFINITION = '900000000000550004'
OWL_REFSET = '733073007'
DEFINED = '900000000000073002'
ATTRIBUTE_VALUE_REFSETS = ('900000000000490003', '900000000000489007')


def author_state_and_language_defects(r: Rows):
    """The last cluster: components whose STATE contradicts something else.

    Each is a pair of rows that disagree - an inactive text definition with a
    live language row, a primitive concept carrying an equivalence axiom, a
    Belgian description in one language where two are required, two FSNs
    differing only in case, a component inactivated while changing module. All
    of them were silent because the fixture was internally consistent, which is
    the recurring reason: consistency is exactly what these assertions exist to
    disprove.
    """
    n = 0

    # An INACTIVE text definition with an ACTIVE language refset row: retiring
    # the definition and leaving its acceptability behind.
    cid = r.next_id(CONCEPT_P, '00')
    r.concept.append((cid, CURR, '1', CORE_MODULE, PRIMITIVE))
    r.desc.append((r.next_id(DESC_P, '01'), CURR, '1', CORE_MODULE, cid, 'en', FSN,
                   'Concept with a retired text definition (finding)', CASE_INSENSITIVE))
    dead_def = r.next_id(DESC_P, '01')
    r.textdef.append((dead_def, CURR, '0', CORE_MODULE, cid, 'en', TEXT_DEFINITION,
                      'A text definition that is inactive while its language row is not.',
                      CASE_INSENSITIVE))
    r.lang.append((r.uuid(), CURR, '1', CORE_MODULE, '900000000000509007', dead_def,
                   '900000000000548007'))
    n += 1

    # A PRIMITIVE concept carrying an EquivalentClasses axiom. An equivalence is
    # a full definition, so the status and the axiom contradict each other.
    prim = r.next_id(CONCEPT_P, '00')
    r.concept.append((prim, CURR, '1', CORE_MODULE, PRIMITIVE))
    r.desc.append((r.next_id(DESC_P, '01'), CURR, '1', CORE_MODULE, prim, 'en', FSN,
                   'Primitive concept with an equivalence axiom (finding)', CASE_INSENSITIVE))
    r.owl.append((r.uuid(), CURR, '1', CORE_MODULE, OWL_REFSET, prim,
                  f'EquivalentClasses(:{prim} :138875005)'))
    n += 1

    # A Belgian synonym in Dutch with a preferred Dutch language row and no
    # French counterpart, where the edition requires both.
    be = r.next_id(CONCEPT_P, '00')
    r.concept.append((be, CURR, '1', BE_MODULE, PRIMITIVE))
    nl = r.next_id(DESC_P, '01')
    r.desc.append((nl, CURR, '1', BE_MODULE, be, 'nl', SYNONYM,
                   'Belgisch begrip preferent in twee dialecten', CASE_INSENSITIVE))
    # TWO preferred rows for ONE description, in both BE dialect refsets. The
    # assertion is `GROUP BY a.id, a.languagecode, a.conceptid HAVING COUNT(a.id)
    # > 1` - it groups by the DESCRIPTION id, so what it detects is one
    # description marked preferred in both the Dutch and the French dialect, not
    # a concept missing a translation. The name reads the other way round.
    for refset in (BE_DUTCH_REFSET, BE_FRENCH_REFSET):
        r.lang.append((r.uuid(), CURR, '1', BE_MODULE, refset, nl, '900000000000548007'))
    n += 1

    # Two FSNs for one concept differing only in case - unique to a
    # case-sensitive comparison and a duplicate to a case-insensitive one, which
    # is what the assertion checks.
    # Two DIFFERENT concepts, one term modulo case. The assertion joins the
    # delta to a grouped snapshot and requires the match to be against another
    # ACTIVE CONCEPT - two FSNs on one concept is a different defect and this
    # check does not see it.
    dup = None
    for term in ('Case insensitive duplicate name (finding)',
                 'CASE INSENSITIVE DUPLICATE NAME (finding)'):
        cid = r.next_id(CONCEPT_P, '00')
        dup = dup or cid
        r.concept.append((cid, CURR, '1', CORE_MODULE, PRIMITIVE))
        r.desc.append((r.next_id(DESC_P, '01'), CURR, '1', CORE_MODULE, cid, 'en', FSN,
                       term, CASE_INSENSITIVE))
    n += 1

    # An attribute-value member inactive in BOTH releases, in the two refsets
    # the illegal-change assertion names - an inactivation reason edited after
    # the fact.
    for refset in ATTRIBUTE_VALUE_REFSETS:
        # ONE id across both releases: the assertion joins prospective to
        # previous on a.id = b.id and wants both inactive with the value
        # changed, so two different ids would join to nothing.
        member = r.uuid()
        r.attrvalue.append((member, CURR, '0', CORE_MODULE, refset, dup,
                            '900000000000492006'))
        r.attrvalue_prev.append((member, PREV, '0', CORE_MODULE, refset, dup,
                                 '900000000000487009'))
    n += 1

    # An association member inactivated while changing module, at an
    # effectiveTime no earlier than the row it replaces.
    moved = r.uuid()
    r.assoc.append((moved, CURR, '0', MODEL_MODULE, HISTORICAL_SAME_AS, dup, KNOWN_CONCEPT))
    r.assoc_prev.append((moved, PREV, '1', CORE_MODULE, HISTORICAL_SAME_AS, dup, KNOWN_CONCEPT))
    n += 1
    return n


def author_ccs_refset(r: Rows):
    """The ccsRefset file the fixture does not ship, which is the ONLY thing
    keeping the AMT arm off 264 of 264.

    `Full ccsRefset validation - 01` reads `ccsrefset_f`. MySQL loads only the
    files a release contains, so with none matching `der2_ccsRefset_` the table
    is ABSENT rather than empty and the statement dies on it - reported as
    failureCount -1, an incomplete, which reads as a failure against the
    assertion rather than against the release. DuckDB's store declares the table
    and creates it empty, so the statement runs and reports 0.

    That is the whole divergence: not a disagreement about content, but about
    what a missing file MEANS. Shipping the file removes the disagreement at its
    cause rather than tolerating it in a baseline - both engines then read a
    table that exists and agree on what is in it.

    The columns are the nine the store declares: the six every refset has, plus
    two component ids and a string.
    """
    for i, (c1, c2, value) in enumerate((
            ('703860006', '138875005', 'first'),
            ('703649004', '138875005', 'second'))):
        r.ccsrefset.append((r.uuid(), CURR, '1', CORE_MODULE, '900000000000497000',
                            KNOWN_CONCEPT, c1, c2, value))
    return len(r.ccsrefset)


FILES = {
    'sct2_Concept': (['id', 'effectiveTime', 'active', 'moduleId', 'definitionStatusId'],
                     'concept', '', '_'),
    'sct2_Description': (['id', 'effectiveTime', 'active', 'moduleId', 'conceptId',
                          'languageCode', 'typeId', 'term', 'caseSignificanceId'],
                         'desc', '-en', '_'),
    'der2_ssRefset_ModuleDependency': (
        ['id', 'effectiveTime', 'active', 'moduleId', 'refsetId', 'referencedComponentId',
         'sourceEffectiveTime', 'targetEffectiveTime'], 'mdrs', '', ''),
    'der2_cRefset_Association': (
        ['id', 'effectiveTime', 'active', 'moduleId', 'refsetId', 'referencedComponentId',
         'targetComponentId'], 'assoc', '', ''),
    'der2_cciRefset_RefsetDescriptor': (
        ['id', 'effectiveTime', 'active', 'moduleId', 'refsetId', 'referencedComponentId',
         'attributeDescription', 'attributeType', 'attributeOrder'], 'descriptor', '', ''),
    'der2_iisssccRefset_ExtendedMap': (
        ['id', 'effectiveTime', 'active', 'moduleId', 'refSetId', 'referencedComponentId',
         'mapGroup', 'mapPriority', 'mapRule', 'mapAdvice', 'mapTarget', 'correlationId',
         'mapCategoryId'], 'extmap', '', ''),
    'der2_iissscRefset_ComplexMap': (
        ['id', 'effectiveTime', 'active', 'moduleId', 'refsetId', 'referencedComponentId',
         'mapGroup', 'mapPriority', 'mapRule', 'mapAdvice', 'mapTarget', 'correlationId'],
        'complexmap', '', ''),
    'sct2_sRefset_OWLExpression': (
        ['id', 'effectiveTime', 'active', 'moduleId', 'refsetId', 'referencedComponentId',
         'owlExpression'], 'owl', '', ''),
    'der2_ciRefset_DescriptionType': (
        ['id', 'effectiveTime', 'active', 'moduleId', 'refsetId', 'referencedComponentId',
         'descriptionFormat', 'descriptionLength'], 'desctype', '', ''),
    'der2_sRefset_SimpleMap': (
        ['id', 'effectiveTime', 'active', 'moduleId', 'refsetId', 'referencedComponentId',
         'mapTarget'], 'simplemap', '', ''),
    # Appended to, not owned: ci/author_amt_fixture.py writes ADRS rows into
    # this same file, and each generator strips only its own ids - which is why
    # the id prefixes have to stay distinct between the two.
    'der2_cRefset_Language': (
        ['id', 'effectiveTime', 'active', 'moduleId', 'refsetId', 'referencedComponentId',
         'acceptabilityId'], 'lang', '-en', ''),
    # Created, not appended: no der2_ccsRefset_ file exists in this fixture, and
    # its absence is what MySQL errors on.
    'der2_ccsRefset_Example': (
        ['id', 'effectiveTime', 'active', 'moduleId', 'refsetId', 'referencedComponentId',
         'componentId1', 'componentId2', 'value'], 'ccsrefset', '', ''),
    'sct2_TextDefinition': (
        ['id', 'effectiveTime', 'active', 'moduleId', 'conceptId', 'languageCode',
         'typeId', 'term', 'caseSignificanceId'], 'textdef', '-en', '_'),
    'der2_cRefset_AttributeValue': (
        ['id', 'effectiveTime', 'active', 'moduleId', 'refsetId', 'referencedComponentId',
         'valueId'], 'attrvalue', '', ''),
    'sct2_Relationship': (
        ['id', 'effectiveTime', 'active', 'moduleId', 'sourceId', 'destinationId',
         'relationshipGroup', 'typeId', 'characteristicTypeId', 'modifierId'], 'rel', '', '_'),
}


def is_authored(line: str) -> bool:
    """Keyed on the id, not the module: these rows deliberately belong to other
    modules, which is the point of the national and MDRS cases."""
    first = line.split('\t')[0]
    return first.startswith((CONCEPT_P, DESC_P, '9977', UUID_MARK))


def merge(path: pathlib.Path, header, rows):
    kept = []
    if path.exists():
        lines = path.read_bytes().decode('utf-8').replace('\r\n', '\n').rstrip('\n').split('\n')
        kept = [l for l in lines[1:] if l.strip() and not is_authored(l)]
    body = '\r\n'.join(['\t'.join(header)] + kept + ['\t'.join(r) for r in rows]) + '\r\n'
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(body.encode('utf-8'))
    return len(kept), len(rows)


def main():
    r = Rows()
    print(f"  authored {author_mdrs_for_this_release(r)} MDRS rows stamped {CURR}, "
          f"which is what the mdrs-violation family binds against")
    print(f"  authored {author_national_module_concepts(r)} national-module concepts with no FSN")
    print(f"  authored {author_association_defects(r)} association defects")
    print(f"  authored {author_map_and_axiom_defects(r)} map, axiom and character defects")
    print(f"  authored {author_duplicate_keys_and_modules(r)} duplicate-key and wrong-module defects")
    print(f"  authored {author_state_and_language_defects(r)} state and language contradictions")
    print(f"  authored {author_ccs_refset(r)} ccsRefset rows - the file the release did not ship")

    buckets = {'concept': r.concept, 'desc': r.desc, 'mdrs': r.mdrs, 'assoc': r.assoc,
               'descriptor': r.descriptor, 'extmap': r.extmap, 'owl': r.owl,
               'complexmap': r.complexmap, 'rel': r.rel,
               'desctype': r.desctype, 'simplemap': r.simplemap,
               'textdef': r.textdef, 'attrvalue': r.attrvalue, 'lang': r.lang,
               'ccsrefset': r.ccsrefset}
    # Rows that belong to the PREVIOUS release, because the assertion compares
    # the two. Without these the release-comparison checks have one side only
    # and read as clean.
    previous = {'attrvalue': r.attrvalue_prev, 'assoc': r.assoc_prev}
    total = 0
    for stem, (header, bucket, lang_suffix, sep) in FILES.items():
        rows = buckets[bucket]
        for release in (PREV, CURR):
            base = ROOT / f'SnomedCT_RegressionTest_{release}' / 'RF2Release'
            for kind in ('Snapshot', 'Full', 'Delta'):
                emit = rows if release == CURR else previous.get(bucket, [])
                path = base / kind / f'{stem}{sep}{kind}{lang_suffix}_INT_{release}.txt'
                if not path.exists() and not (stem == 'der2_ccsRefset_Example' and emit):
                    continue
                kept, added = merge(path, header, emit)
                total += added
                if added:
                    print(f"    {release} {kind:<9} {path.name[:46]:<46} {kept} kept + {added} authored")
    print(f"  {total} rows written")


if __name__ == '__main__':
    main()
