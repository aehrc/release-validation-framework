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
import pathlib

ROOT = pathlib.Path('/data/Projects/rvf-catchup/src/test/resources')
PREV, CURR = '20130131', '20130731'

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
}

AUTHORED_PREFIXES = (CONCEPT_P, DESC_P, REL_P, LANG_P, '00000000-0000')


def is_authored(line: str) -> bool:
    first = line.split('\t')[0]
    return first.startswith(AUTHORED_PREFIXES)


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
    print(f"  authored {members} class-refset members, each non-compliant in the ways "
          f"the corpus checks, plus one dangling attribute target")

    buckets = {'concept': r.concept, 'desc': r.desc, 'rel': r.rel, 'lang': r.lang,
               'simple': r.simple, 'concrete': r.concrete}
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
                if not path.exists():
                    continue
                kept, added = merge(path, header, emit)
                total += added
                if added:
                    print(f"    {release} {kind:<9} {name[:46]:<46} {kept} kept + {added} authored")
    print(f"  {total} rows written")


if __name__ == '__main__':
    main()
