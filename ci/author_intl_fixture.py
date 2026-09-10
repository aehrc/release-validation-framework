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
PRIMITIVE = '900000000000900001'
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
}


def is_authored(line: str) -> bool:
    """Keyed on the id, not the module: these rows deliberately belong to other
    modules, which is the point of the national and MDRS cases."""
    first = line.split('\t')[0]
    return first.startswith((CONCEPT_P, DESC_P, UUID_MARK))


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

    buckets = {'concept': r.concept, 'desc': r.desc, 'mdrs': r.mdrs, 'assoc': r.assoc}
    total = 0
    for stem, (header, bucket, lang_suffix, sep) in FILES.items():
        rows = buckets[bucket]
        for release in (PREV, CURR):
            base = ROOT / f'SnomedCT_RegressionTest_{release}' / 'RF2Release'
            for kind in ('Snapshot', 'Full', 'Delta'):
                emit = rows if release == CURR else []
                path = base / kind / f'{stem}{sep}{kind}{lang_suffix}_INT_{release}.txt'
                if not path.exists():
                    continue
                kept, added = merge(path, header, emit)
                total += added
                if added:
                    print(f"    {release} {kind:<9} {path.name[:46]:<46} {kept} kept + {added} authored")
    print(f"  {total} rows written")


if __name__ == '__main__':
    main()
