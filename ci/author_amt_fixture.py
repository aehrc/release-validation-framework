#!/usr/bin/env python3
"""Authors the AMT content RVF's regression fixture lacks.

184 of the 200 amtv4 assertions execute on both engines and find nothing,
because the international fixture holds no medicine model: no AMT module, no
product-class refset members, no ADRS dialect rows, no concrete strengths. They
prove they can run; they do not prove they detect anything.

Unlike ci/author_mrcm_fixture.py this APPENDS. The MRCM refset files did not
exist, so that generator could write whole files; concept, description,
relationship, language and simple-refset files already carry the international
content the other 360 assertions read, and overwriting them would delete it.
Appends are keyed by an id prefix and removed before re-adding, so re-running
is idempotent rather than cumulative.

Every row here is derived from an assertion's own WHERE clause - the refset ids,
the attribute types, the semantic tags and the ADRS dialect refset are read out
of the published store, not guessed. Nothing is invented to make a number move.
"""
import json
import pathlib
import re

ROOT = pathlib.Path('/data/Projects/rvf-catchup/src/test/resources')
PREV, CURR = '20130131', '20130731'
STORE = pathlib.Path('/data/work/amt-build/store.json')

# --- what the assertions read ------------------------------------------------
AMT_MODULE = '32506021000036107'      # the AMT module, per the semantic-tag family
ADRS_REFSET = '32570271000036106'     # the dialect get_cr_ADRS_PT() joins through
PREFERRED = '900000000000548007'
SYNONYM = '900000000000013009'
FSN = '900000000000003001'
IS_A = '116680003'
DEFINED = '900000000000073002'
PRIMITIVE = '900000000000900001'
CASE_INSENSITIVE = '900000000000448009'
EN = 'en'

# The 19 hierarchies of "ADRS Preferred terms are unique within <X>", each of
# which needs two concepts under it sharing one ADRS preferred term.
ADRS_HIERARCHIES = [
    ('272379006', 'Event'), ('254291000', 'Staging and scales'),
    ('71388002', 'Procedure'), ('243796009', 'Situation with explicit context'),
    ('308916002', 'Environment or geographical location'), ('123038009', 'Specimen'),
    ('404684003', 'Clinical finding'), ('363787002', 'Observable entity'),
    ('78621006', 'Physical force'), ('49062001', 'Device'),
    ('105590001', 'Substance'), ('736542009', 'Pharmaceutical dose form'),
    ('410607006', 'Organism'), ('123037004', 'Body structure'),
    ('370115009', 'Special concept'), ('419891008', 'Record artefact'),
    ('900000000000441003', 'SNOMED CT Model Component'), ('48176007', 'Social context'),
    ('767524001', 'Unit of measure'),
]

# The semantic-tag family: an FSN in the AMT module carrying one of these tags
# must be a member of the paired refset. Each row here is a concept that is not.
SEMANTIC_TAGS = [
    ('929360081000036101', 'clinical drug package'),
    ('929360071000036103', 'clinical drug'),
    ('929360041000036105', 'branded clinical drug package'),
    ('929360051000036108', 'containerized branded clinical drug package'),
    ('929360031000036100', 'branded clinical drug'),
]

# id prefixes, so a re-run replaces its own rows and touches nothing else
CONCEPT_P = '9971'
DESC_P = '9972'
REL_P = '9973'
LANG_P = '9974'


def verhoeff(digits: str) -> str:
    """The SNOMED CT check digit. Ids that fail it are what several assertions
    look for, so the ones authored here must pass rather than accidentally
    become findings of a different test."""
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
    """<prefix><seq><partition><check>, which is the RF2 id shape: the two
    digits before the check digit are the partition identifier."""
    body = f'{prefix}{seq:05d}{partition}'
    return body + verhoeff(body)


class Rows:
    """Accumulates rows per file, so one pass over the assertions can emit
    concepts, descriptions, relationships and refset members together."""

    def __init__(self):
        self.concept, self.desc, self.rel, self.lang, self.simple = [], [], [], [], []
        self.concrete = []
        self.attrvaluemap, self.imaprefset = [], []
        self._n = 0

    def next_id(self, prefix, partition):
        self._n += 1
        return sctid(prefix, self._n, partition)

    def concept_row(self, cid, module=AMT_MODULE, status=PRIMITIVE, effective=CURR):
        self.concept.append((cid, effective, '1', module, status))

    def description_row(self, cid, term, typeid=SYNONYM, module=AMT_MODULE, effective=CURR):
        did = self.next_id(DESC_P, '01')
        self.desc.append((did, effective, '1', module, cid, EN, typeid, term, CASE_INSENSITIVE))
        return did

    def language_row(self, did, refset=ADRS_REFSET, accept=PREFERRED, effective=CURR):
        self.lang.append((self.next_uuid(), effective, '1', AMT_MODULE, refset, did, accept))

    def relationship_row(self, source, dest, typeid=IS_A, group='0', effective=CURR):
        rid = self.next_id(REL_P, '02')
        self.rel.append((rid, effective, '1', AMT_MODULE, source, dest, group, typeid,
                         '900000000000011006', '900000000000451002'))
        return rid

    def next_uuid(self):
        """A refset member id is a UUID. Derived from the row count so a re-run
        produces the same ids and the fixture's diff stays readable."""
        self._n += 1
        h = f'{self._n:032x}'
        return f'{h[:8]}-{h[8:12]}-{h[12:16]}-{h[16:20]}-{h[20:32]}'


def author_adrs_duplicate_preferred_terms(r: Rows):
    """Two concepts under each hierarchy sharing one ADRS preferred term.

    `GROUP BY GET_CR_ADRS_PT(id) HAVING COUNT(id) > 1` is the whole assertion,
    and the port it calls joins the language refset to descriptions through the
    ADRS dialect. So a pair needs: two active concepts, an IsA each to the
    hierarchy root (the closure the ports build from relationship_active is what
    ISKINDOF_CR reads), one synonym each with the SAME term, and a preferred
    language row in the ADRS refset for each of those synonyms.
    """
    n = 0
    for root, name in ADRS_HIERARCHIES:
        term = f'AMT duplicate ADRS preferred term for {name.lower()}'
        for _ in range(2):
            cid = r.next_id(CONCEPT_P, '00')
            r.concept_row(cid)
            r.description_row(cid, f'{term} ({name.lower()})', typeid=FSN)
            did = r.description_row(cid, term)
            r.language_row(did)
            r.relationship_row(cid, root)
            n += 1
    return n


def author_semantic_tag_gaps(r: Rows):
    """An AMT-module FSN carrying a product-class semantic tag, whose concept is
    NOT in the refset that tag implies - which is what the family checks."""
    for _, tag in SEMANTIC_TAGS:
        cid = r.next_id(CONCEPT_P, '00')
        r.concept_row(cid, status=DEFINED)
        r.description_row(cid, f'AMT unpublished product ({tag})', typeid=FSN)
    return len(SEMANTIC_TAGS)


# The AMT product-class refsets, and the semantic tag each one's members must
# carry. Read out of the store: these are the refsets the amtv4 assertions test
# membership against, ordered by how many of them do.
CLASS_REFSETS = [
    ('929360051000036108', 'CTPP', 'containerized branded clinical drug package'),
    ('929360031000036100', 'TPUU', 'branded clinical drug'),
    ('929360041000036105', 'TPP', 'branded clinical drug package'),
    ('929360081000036101', 'MPP', 'clinical drug package'),
    ('929360071000036103', 'MPUU', 'clinical drug'),
    ('929360021000036102', 'MP', 'product name'),
    ('929360061000036106', 'TP', 'product name'),
    ('1050951000168102', 'S8', 'branded clinical drug'),
    ('1514151000168100', 'refset-1514151', 'clinical drug'),
    ('1183941000168107', 'refset-1183941', 'clinical drug'),
]

# The relationship types the silent assertions test between class members, with
# how many of them read each. A member holding one of these pointed at a concept
# that is NOT in the paired refset is what the "all T targets are members"
# family looks for.
AMT_ATTRIBUTES = ['774158006', '774160008', '30465011000036106', '411116001',
                  '999000061000168105', '999000011000168107', '1142142004',
                  '127489000']

SINGLE_INGREDIENT_ROOT = '85990009'   # ISKINDOF_CR target of the S8 family
STRENGTH_TYPE = '1142140007'          # concrete: count of active ingredient


def author_product_model(r: Rows):
    """A member in every class refset, and a dangling target for every attribute.

    50 of the silent assertions turn on refset membership and 145 statements read
    relationship_active, so memberships are the key that unlocks them. Each
    member here is deliberately non-compliant in the ways the corpus checks for,
    and each defect is one an assertion names rather than one I invented:

    * PRIMITIVE, not DEFINED - the DNF family reads `definitionstatusid <>
      900000000000073002` over refset members.
    * an FSN carrying the WRONG class tag - the "<class> FSNs have semantic tag"
      family checks members' tags, the mirror of the family above.
    * every AMT attribute pointed at a concept in NO refset - the "all targets of
      T are members of R" family checks the destination's membership.

    The dangling target is a real active concept, so the assertions that fire are
    the membership ones rather than "destination is not a concept in this
    release", which is a different test with different evidence.
    """
    dangling = r.next_id(CONCEPT_P, '00')
    r.concept_row(dangling, status=DEFINED)
    r.description_row(dangling, 'AMT attribute target in no product refset (product)', typeid=FSN)

    members = 0
    for refset, label, tag in CLASS_REFSETS:
        cid = r.next_id(CONCEPT_P, '00')
        r.concept_row(cid, status=PRIMITIVE)
        # The wrong tag on purpose: 'observable entity' is in no class family, so
        # it violates whichever tag this refset's members must carry.
        r.description_row(cid, f'AMT {label} member with the wrong tag (observable entity)', typeid=FSN)
        did = r.description_row(cid, f'AMT {label} member with the wrong tag')
        r.language_row(did)
        r.simple.append((r.next_uuid(), CURR, '1', AMT_MODULE, refset, cid))
        for typeid in AMT_ATTRIBUTES:
            r.relationship_row(cid, dangling, typeid=typeid, group='1')
        members += 1

    # The S8 single-ingredient family: a TPUU under the single-ingredient root
    # with exactly one active ingredient, in the TPUU refset and NOT in S8.
    s8 = r.next_id(CONCEPT_P, '00')
    r.concept_row(s8, status=DEFINED)
    r.description_row(s8, 'AMT single ingredient codeine product not in S8 (branded clinical drug)', typeid=FSN)
    r.simple.append((r.next_uuid(), CURR, '1', AMT_MODULE, '929360031000036100', s8))
    r.relationship_row(s8, SINGLE_INGREDIENT_ROOT, typeid=IS_A)
    r.relationship_row(s8, dangling, typeid='127489000', group='1')
    r.concrete.append((r.next_id(REL_P, '02'), CURR, '1', AMT_MODULE, s8, '#1', '1',
                       STRENGTH_TYPE, '900000000000011006', '900000000000451002'))
    return members


def sample_matching(pattern: str) -> str | None:
    """A shortest string this pattern accepts, for the subset the ADRS assertions
    use: alternation, character classes, `.*`/`.+`, `?`, anchors, escapes.

    Generated rather than hand-picked because there are 22 of them and the
    patterns are the requirement - a term I chose myself would be a guess about
    what the assertion means. Every result is checked against the pattern before
    it is emitted, so a pattern this cannot handle produces None and its
    assertion stays honestly silent instead of getting content that misses.
    """
    out, i, depth_alt = [], 0, None
    while i < len(pattern):
        c = pattern[i]
        if c == '\\' and i + 1 < len(pattern):
            out.append(pattern[i + 1]); i += 2; continue
        if c in '^$':
            i += 1; continue
        if c == '.':
            # `.*` and `.?` can be empty; a bare `.` or `.+` needs one character
            nxt = pattern[i + 1] if i + 1 < len(pattern) else ''
            out.append('' if nxt in ('*', '?') else 'x')
            i += 2 if nxt in ('*', '?', '+') else 1
            continue
        if c == '[':
            j = pattern.index(']', i)
            body = pattern[i + 1:j]
            pick = body[0] if body and body[0] != '^' else 'x'
            nxt = pattern[j + 1] if j + 1 < len(pattern) else ''
            out.append('' if nxt in ('*', '?') else pick)
            i = j + (2 if nxt in ('*', '?', '+') else 1)
            continue
        if c == '(':
            # the first alternative of the group, recursively
            depth, j = 1, i + 1
            while j < len(pattern) and depth:
                if pattern[j] == '\\': j += 2; continue
                depth += (pattern[j] == '(') - (pattern[j] == ')')
                j += 1
            inner = pattern[i + 1:j - 1]
            if inner.startswith('?:'):
                inner = inner[2:]
            # split on top-level | only
            parts, d, last = [], 0, 0
            for k, ch in enumerate(inner):
                if ch == '(': d += 1
                elif ch == ')': d -= 1
                elif ch == '|' and d == 0:
                    parts.append(inner[last:k]); last = k + 1
            parts.append(inner[last:])
            nxt = pattern[j] if j < len(pattern) else ''
            piece = '' if nxt in ('*', '?') else (sample_matching(parts[0]) or '')
            out.append(piece)
            i = j + (1 if nxt in ('*', '?', '+') else 0)
            continue
        nxt = pattern[i + 1] if i + 1 < len(pattern) else ''
        out.append('' if nxt in ('*', '?') else c)
        i += 2 if nxt in ('*', '?', '+') else 1
    raw = ''.join(out)
    if not raw.strip():
        return None
    # Some patterns require a trailing space - `[Ff]o?et(us|al) ` does - and a
    # term with trailing whitespace is a DIFFERENT defect: it is what MySQL's
    # PAD SPACE collation hides and what one baseline entry is already about.
    # So the space is satisfied by a following word rather than left dangling.
    for candidate in (raw.strip(), raw.strip() + ' specimen'):
        try:
            if re.search(pattern, candidate):
                return candidate
        except re.error:
            return None
    return None


def author_adrs_pattern_gaps(r: Rows, requirements):
    """One concept per ADRS assertion: a term its trigger pattern matches, and
    where the assertion also reads GET_CR_ADRS_PT, a preferred ADRS synonym
    matching whatever it wants to find there.

    The shape of the family is "a term says X, so some other term must say Y":
    a concept carrying only the trigger is exactly the violation.
    """
    made = 0
    for name, trigger, adrs_pt in requirements:
        term = sample_matching(trigger)
        if not term:
            continue
        cid = r.next_id(CONCEPT_P, '00')
        r.concept_row(cid, status=PRIMITIVE)
        r.description_row(cid, f'{term} (observable entity)', typeid=FSN)
        r.description_row(cid, term)
        if adrs_pt:
            pt = sample_matching(adrs_pt)
            if pt:
                did = r.description_row(cid, pt)
                r.language_row(did)
        made += 1
    return made


def author_refset_concept_descriptions(r: Rows):
    """The class refsets' own concepts, with a synonym that is not one of the
    names the DNF family allows.

    Those assertions read `description_active WHERE conceptId = <the refset>`
    and compare the term against two or three permitted spellings. The fixture
    holds no description for any refset concept at all, so they could not fire:
    there was nothing to compare. A concept and a deliberately non-canonical
    synonym is the whole requirement, and an ADRS preferred term alongside it
    covers the `GET_CR_ADRS_PT(<refset>) = '<name>'` variants.
    """
    for refset, label, _ in CLASS_REFSETS:
        # Deliberately NO concept row. Nine assertions of the form
        # `NOT EXISTS(SELECT * FROM concept_active WHERE id = <refset>)` fire
        # only when the refset concept is ABSENT, and they are one per refset -
        # so authoring the concept would satisfy nine to fire two. Descriptions
        # for a concept that is not in the release is itself a real failure mode
        # (a partially published refset), and the name family reads
        # description_active only, so both sides fire on the same content.
        r.description_row(refset, f'{label} reference set (foundation metadata concept)', typeid=FSN)
        did = r.description_row(refset, f'{label} refset under a name the corpus does not allow')
        r.language_row(did)
    return len(CLASS_REFSETS)


def author_refset_disjointness_breach(r: Rows):
    """One concept in two of the seven class refsets.

    `GROUP BY referencedComponentId HAVING COUNT(refsetId) > 1` over the seven is
    the assertion, and it is a genuine modelling error: a product cannot be both
    a unit of use and a pack.
    """
    cid = r.next_id(CONCEPT_P, '00')
    r.concept_row(cid, status=DEFINED)
    r.description_row(cid, 'AMT product in two class refsets at once (clinical drug)', typeid=FSN)
    for refset in ('929360071000036103', '929360081000036101'):
        r.simple.append((r.next_uuid(), CURR, '1', AMT_MODULE, refset, cid))
    return 1


def author_same_refset_parentage(r: Rows):
    """Two members of one class refset with an IsA between them, the parent
    holding neither the attribute nor the concrete value the `DNF ... All <class>
    are ...` assertions require of a parent that is in the same refset."""
    parent = r.next_id(CONCEPT_P, '00')
    child = r.next_id(CONCEPT_P, '00')
    for cid, role in ((parent, 'parent'), (child, 'child')):
        r.concept_row(cid, status=DEFINED)
        r.description_row(cid, f'AMT TPUU {role} in the same refset (branded clinical drug)', typeid=FSN)
        r.simple.append((r.next_uuid(), CURR, '1', AMT_MODULE, '929360031000036100', cid))
    r.relationship_row(child, parent, typeid=IS_A)
    return 2

def author_map_refset_files(r: Rows):
    """The two map-refset files the fixture does not have at all.

    `attributevaluemap_*` is loaded from `der2_csRefset_.*AttributeValueMap` and
    `isimplemaprefset_*` from `der2_iRefset_.*SimpleMap` - neither of which is in
    this fixture, so ten assertions read empty tables and could not fire whatever
    content the rest of the release carried.

    Each row below is one assertion's defect, and only that one, so a finding
    names a cause:

    * an id that is no refset at all, for `refsetId is a valid refsetId`
    * a referencedComponentId that is in no component file, for `- 07`
    * one id appearing twice with a different referencedComponentId, and another
      with a different refsetId, for `- 09a` and `- 09b`, which is a Full-file
      defect: the same member changing what it points at
    * an empty value, for `StringValue is populated`
    * two rows agreeing on every business key, for the `All currently active
      references are unique` pair
    * a negative mapTarget, for `value1 is a 32-bit integer`
    """
    module, eff = AMT_MODULE, CURR
    bogus_refset = '99700001000036101'      # a plain concept, so not a valid refsetId
    absent_component = '999999999999'       # deliberately in no component file
    real_refset = '900000000000490003'      # a genuine attribute-value refset

    r.attrvaluemap = [
        # - 06: refsetId that is not a descendant of the refset root
        ('a0000001-0000-4000-8000-000000000001', eff, '1', module, bogus_refset, '703860006', '', 'first'),
        # - 07: referencedComponentId in no component file
        ('a0000001-0000-4000-8000-000000000002', eff, '1', module, real_refset, absent_component, '', 'second'),
        # - 09a: one id, two referencedComponentIds
        ('a0000001-0000-4000-8000-000000000003', '20130131', '1', module, real_refset, '703860006', '', 'third'),
        ('a0000001-0000-4000-8000-000000000003', eff, '1', module, real_refset, '703649004', '', 'third'),
        # - 09b: one id, two refsetIds
        ('a0000001-0000-4000-8000-000000000004', '20130131', '1', module, real_refset, '703860006', '', 'fourth'),
        ('a0000001-0000-4000-8000-000000000004', eff, '1', module, '900000000000489007', '703860006', '', 'fourth'),
        # - 13: no value at all
        ('a0000001-0000-4000-8000-000000000005', eff, '1', module, real_refset, '703860006', '', ''),
        # - 14: two active rows agreeing on refset, component, target and value
        ('a0000001-0000-4000-8000-000000000006', eff, '1', module, real_refset, '703860006', 'same', 'same'),
        ('a0000001-0000-4000-8000-000000000007', eff, '1', module, real_refset, '703860006', 'same', 'same'),
    ]
    r.imaprefset = [
        # - 12: a mapTarget that is not a positive integer, and is not zero
        ('b0000001-0000-4000-8000-000000000001', eff, '1', module, '900000000000497000', '703860006', '-5'),
        # - 13: two active rows agreeing on refset, component and target
        ('b0000001-0000-4000-8000-000000000002', eff, '1', module, '900000000000497000', '703860006', '77'),
        ('b0000001-0000-4000-8000-000000000003', eff, '1', module, '900000000000497000', '703860006', '77'),
    ]
    return len(r.attrvaluemap) + len(r.imaprefset)


def adrs_pattern_requirements():
    """Reads the ADRS assertions out of the published store: for each, the first
    positive `REGEXP_MATCHES(term, ...)` and, if it reads the ADRS preferred
    term, the pattern it wants to find there.

    Read rather than transcribed. There are 22 of them, the patterns are the
    requirement, and a pattern I retyped would drift the moment the corpus
    changed - which it does, since the AMT scripts live in another repository.
    """
    if not STORE.exists():
        return []
    store = json.loads(STORE.read_text())['assertions']
    out = []
    for a in store.values():
        if not a['file'].startswith('ADRS'):
            continue
        sql = ' '.join(a['statements'])
        # a positive term match: not preceded by NOT
        term = None
        for m in re.finditer(r"(NOT\s+)?REGEXP_MATCHES\(\s*term\s*,\s*'((?:[^']|'')+)'", sql):
            if not m.group(1):
                term = m.group(2).replace("''", "'")
                break
        if not term:
            continue
        pt = None
        m = re.search(r"(NOT\s+)?REGEXP_MATCHES\(\s*GET_CR_ADRS_PT\([^)]*\)\s*,\s*'((?:[^']|'')+)'", sql)
        if m and not m.group(1):
            pt = m.group(2).replace("''", "'")
        out.append((a['file'], term, pt))
    return out


FILES = {
    'sct2_Concept': (['id', 'effectiveTime', 'active', 'moduleId', 'definitionStatusId'],
                     'concept', ''),
    'sct2_Description': (['id', 'effectiveTime', 'active', 'moduleId', 'conceptId',
                          'languageCode', 'typeId', 'term', 'caseSignificanceId'],
                         'desc', '-en'),
    'sct2_Relationship': (['id', 'effectiveTime', 'active', 'moduleId', 'sourceId',
                           'destinationId', 'relationshipGroup', 'typeId',
                           'characteristicTypeId', 'modifierId'], 'rel', ''),
    'der2_cRefset_Language': (['id', 'effectiveTime', 'active', 'moduleId', 'refsetId',
                               'referencedComponentId', 'acceptabilityId'], 'lang', '-en'),
    'der2_Refset_Simple': (['id', 'effectiveTime', 'active', 'moduleId', 'refsetId',
                            'referencedComponentId'], 'simple', ''),
    'sct2_RelationshipConcreteValues': (
        ['id', 'effectiveTime', 'active', 'moduleId', 'sourceId', 'value',
         'relationshipGroup', 'typeId', 'characteristicTypeId', 'modifierId'],
        'concrete', ''),
    # Neither of these files is in the fixture, so they are created rather than
    # appended to - which the merge handles: nothing existing means nothing kept.
    'der2_csRefset_AttributeValueMap': (
        ['id', 'effectiveTime', 'active', 'moduleId', 'refsetId',
         'referencedComponentId', 'mapTarget', 'value'], 'attrvaluemap', ''),
    'der2_iRefset_SimpleMap': (
        ['id', 'effectiveTime', 'active', 'moduleId', 'refsetId',
         'referencedComponentId', 'mapTarget'], 'imaprefset', ''),
}

def is_authored(line: str) -> bool:
    """Every row this generator writes carries the AMT module, and no row in the
    international fixture does - so that is the idempotence key.

    It replaced keying on id prefixes, which had a hole: the class refsets' own
    rows are keyed by REAL SCTIDs, so they matched no prefix, were never removed,
    and survived a run that had stopped authoring them. The stale concept rows
    then satisfied the nine "refsetId is an active concept" assertions and the
    measurement moved by nothing at all. moduleId is column 4 of every RF2
    component and refset file, which is what makes one rule cover them all.
    """
    parts = line.split('\t')
    return len(parts) > 3 and parts[3] == AMT_MODULE


def merge(path: pathlib.Path, header, rows):
    """Existing rows, minus any this generator wrote before, plus these."""
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
    pairs = author_adrs_duplicate_preferred_terms(r)
    tags = author_semantic_tag_gaps(r)
    print(f"  authored {pairs} concepts in ADRS-duplicate pairs across "
          f"{len(ADRS_HIERARCHIES)} hierarchies")
    print(f"  authored {tags} AMT-module concepts carrying a product-class semantic tag")
    members = author_product_model(r)
    adrs = author_adrs_pattern_gaps(r, adrs_pattern_requirements())
    refsets = author_refset_concept_descriptions(r)
    author_refset_disjointness_breach(r)
    author_same_refset_parentage(r)
    maps = author_map_refset_files(r)
    print(f"  authored {members} class-refset members, each non-compliant in the ways "
          f"the corpus checks, plus one dangling attribute target")
    print(f"  authored {adrs} concepts from ADRS trigger patterns, "
          f"{refsets} refset concepts with non-canonical names, a disjointness "
          f"breach and a same-refset parent")
    print(f"  authored {maps} rows in the two map-refset files the fixture lacked")

    buckets = {'concept': r.concept, 'desc': r.desc, 'rel': r.rel, 'lang': r.lang,
               'simple': r.simple, 'concrete': r.concrete,
               'attrvaluemap': r.attrvaluemap, 'imaprefset': r.imaprefset}
    total = 0
    for stem, (header, bucket, lang_suffix) in FILES.items():
        rows = buckets[bucket]
        for release in (PREV, CURR):
            base = ROOT / f'SnomedCT_RegressionTest_{release}' / 'RF2Release'
            for kind in ('Snapshot', 'Full', 'Delta'):
                # Authored content belongs to the CURRENT release only: it is new
                # in this cycle, so it is in the current delta and absent from
                # the previous release entirely. That keeps
                # full = previous full + delta holding, which the release-type
                # assertions check and which a careless append would break.
                emit = rows if release == CURR else []
                # sct2_* files put an underscore before the kind
                # (sct2_Concept_Snapshot); der2_* refset files glue it to the
                # refset name (der2_Refset_SimpleSnapshot). Getting this wrong
                # silently writes nothing, since the append is skipped when the
                # file does not exist.
                sep = '_' if stem.startswith('sct2_') else ''
                name = f'{stem}{sep}{kind}{lang_suffix}_INT_{release}.txt'
                path = base / kind / name
                # The two map-refset files do not exist yet and must be created;
                # everything else is an append to a file that does, and a missing
                # one there means a name is wrong rather than a file is new.
                creates = stem in ('der2_csRefset_AttributeValueMap', 'der2_iRefset_SimpleMap')
                if not path.exists() and not (creates and emit):
                    continue
                kept, added = merge(path, header, emit)
                total += added
                if added:
                    print(f"    {release} {kind:<9} {name[:46]:<46} {kept} kept + {added} authored")
    print(f"  {total} rows written")


if __name__ == '__main__':
    main()
