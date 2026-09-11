"""Authors the MRCM and concrete-value files RVF's regression fixture lacks.

WHY. 55 of the corpus's 61 MRCM assertions never fire on this fixture and 11 of
11 concrete-value assertions never fire, because the files simply are not there:
of the four MRCM refsets only MRCMModuleScope exists, and there is no
sct2_RelationshipConcreteValues at all. An assertion that runs against an absent
file reports nothing and proves nothing.

DESIGN. The same philosophy the rest of the fixture already uses - "made up data
for testing purpose" that is structurally plausible and deliberately flawed, so
content assertions have something to find. Each release is INTERNALLY
CONSISTENT, so the derivative assertions (full = previous full + delta, snapshot
= latest of full) pass, and the flaws are in the content, where the
content-centric assertions look.

  previous release (20130131): the valid rows only
  current release (20130731): those unchanged, plus the flawed ones as a delta

Every flawed row says what it is for. Nothing here is random: a row that fires
nothing is a row that should not exist.
"""
import pathlib
import uuid

ROOT = pathlib.Path('/data/Projects/rvf-catchup/src/test/resources')
PREV, CURR = '20130131', '20130731'

# Concepts the fixture's own concept file holds, so a "valid" row is really valid.
# All of these are in this release's concept DELTA, which is what
# res_edited_active_concepts means and therefore what the component-centric MRCM
# assertions are scoped to. Rows about concepts outside the delta are invisible
# to them, which is how the first cut of this fixture fired only 23 of 61.
ROOT_CONCEPT = '703860006'
ACTIVE_A, ACTIVE_B, ACTIVE_C = '702769005', '703155005', '702386002'
ACTIVE_D, ACTIVE_E = '703672002', '700132008'
INACTIVE = '703649004'          # active=0 in the fixture's concept snapshot
ABSENT = '999999999999'          # deliberately not a concept in this release
CORE_MODULE = '900000000000207008'
MODEL_MODULE = '900000000000012004'
OTHER_MODULE = '449080006'

# MRCM metadata, real SNOMED identifiers.
DOMAIN_REFSET = '723560006'
ATTRIBUTE_DOMAIN_REFSET = '723561005'
ATTRIBUTE_RANGE_REFSET = '723562003'
MANDATORY = '723597001'
OPTIONAL = '723598006'
ALL_CONTENT = '723596005'
PRECOORDINATED = '723594008'
INFERRED = '900000000000011006'
NOT_REFINABLE = '900000000000451002'


def member_id(seed: str) -> str:
    """A stable UUID per logical row, so regenerating does not churn the files."""
    return str(uuid.uuid5(uuid.NAMESPACE_URL, 'rvf-fixture-mrcm/' + seed))


def rows_mrcm_domain():
    """id effectiveTime active moduleId refsetId referencedComponentId
    domainConstraint parentDomain proximalPrimitiveConstraint
    proximalPrimitiveRefinement domainTemplateForPrecoordination
    domainTemplateForPostcoordination guideURL"""
    valid = [
        (member_id('domain-root'), PREV, '1', CORE_MODULE, DOMAIN_REFSET, ROOT_CONCEPT,
         '<< ' + ROOT_CONCEPT, '', '<< ' + ROOT_CONCEPT, '', '[[+id(<< ' + ROOT_CONCEPT + ')]]', '', 'http://snomed.org/mrcm'),
        (member_id('domain-a'), PREV, '1', CORE_MODULE, DOMAIN_REFSET, ACTIVE_A,
         '<< ' + ACTIVE_A, ROOT_CONCEPT, '<< ' + ACTIVE_A, '', '[[+id(<< ' + ACTIVE_A + ')]]', '', 'http://snomed.org/mrcm'),
    ]
    flawed = [
        # referencedComponentId is not a concept in this release: fires the
        # "refers to a valid concept identifier" family.
        (member_id('domain-absent'), CURR, '1', CORE_MODULE, DOMAIN_REFSET, ABSENT,
         '<< ' + ABSENT, ROOT_CONCEPT, '<< ' + ABSENT, '', '[[+id(<< ' + ABSENT + ')]]', '', 'http://snomed.org/mrcm'),
        # An ACTIVE member of an INACTIVE concept: fires the active-member checks.
        (member_id('domain-inactive-target'), CURR, '1', CORE_MODULE, DOMAIN_REFSET, INACTIVE,
         '<< ' + INACTIVE, ROOT_CONCEPT, '<< ' + INACTIVE, '', '[[+id(<< ' + INACTIVE + ')]]', '', 'http://snomed.org/mrcm'),
        # A second active member for a component already in the refset: fires the
        # "one member per referenced component" family.
        (member_id('domain-duplicate'), CURR, '1', CORE_MODULE, DOMAIN_REFSET, ROOT_CONCEPT,
         '<< ' + ROOT_CONCEPT, '', '<< ' + ROOT_CONCEPT, '', '[[+id(<< ' + ROOT_CONCEPT + ')]]', '', 'http://snomed.org/mrcm'),
        # An empty domainConstraint, which the MRCM requires: fires the
        # mandatory-field checks.
        (member_id('domain-empty-constraint'), CURR, '1', MODEL_MODULE, DOMAIN_REFSET, ACTIVE_B,
         '', ROOT_CONCEPT, '', '', '', '', ''),
        # A REPEATED member id. Every one of these refsets has a -unique-id
        # assertion whose whole purpose is this, and none of them had anything
        # to find.
        (member_id('domain-root'), CURR, '1', CORE_MODULE, DOMAIN_REFSET, ACTIVE_D,
         '<< ' + ACTIVE_D, ROOT_CONCEPT, '<< ' + ACTIVE_D, '', '[[+id(<< ' + ACTIVE_D + ')]]', '', 'http://snomed.org/mrcm'),
    ]
    return valid, flawed


def rows_mrcm_attribute_domain():
    """... referencedComponentId domainId grouped attributeCardinality
    attributeInGroupCardinality ruleStrengthId contentTypeId"""
    valid = [
        (member_id('attrdom-a'), PREV, '1', CORE_MODULE, ATTRIBUTE_DOMAIN_REFSET, ACTIVE_A,
         ROOT_CONCEPT, '1', '0..*', '0..1', MANDATORY, ALL_CONTENT),
        (member_id('attrdom-b'), PREV, '1', CORE_MODULE, ATTRIBUTE_DOMAIN_REFSET, ACTIVE_B,
         ROOT_CONCEPT, '0', '0..1', '0..0', OPTIONAL, PRECOORDINATED),
    ]
    flawed = [
        # grouped must be 0 or 1.
        (member_id('attrdom-grouped-2'), CURR, '1', CORE_MODULE, ATTRIBUTE_DOMAIN_REFSET, ACTIVE_C,
         ROOT_CONCEPT, '2', '0..*', '0..1', MANDATORY, ALL_CONTENT),
        # A cardinality that is not RF2 cardinality syntax.
        (member_id('attrdom-bad-cardinality'), CURR, '1', CORE_MODULE, ATTRIBUTE_DOMAIN_REFSET, ACTIVE_A,
         ROOT_CONCEPT, '1', 'many', 'some', MANDATORY, ALL_CONTENT),
        # domainId that is not a domain in the MRCM domain refset.
        (member_id('attrdom-unknown-domain'), CURR, '1', CORE_MODULE, ATTRIBUTE_DOMAIN_REFSET, ACTIVE_B,
         ABSENT, '1', '0..*', '0..1', MANDATORY, ALL_CONTENT),
        # A repeated member id, for the -unique-id assertion.
        (member_id('attrdom-a'), CURR, '1', CORE_MODULE, ATTRIBUTE_DOMAIN_REFSET, ACTIVE_D,
         ROOT_CONCEPT, '1', '0..*', '0..1', MANDATORY, ALL_CONTENT),
        # A module the module dependency refset does not declare, which is what
        # the mdrs-violation assertions look for.
        (member_id('attrdom-undeclared-module'), CURR, '1', '733073007', ATTRIBUTE_DOMAIN_REFSET, ACTIVE_E,
         ROOT_CONCEPT, '1', '0..*', '0..1', MANDATORY, ALL_CONTENT),
    ]
    return valid, flawed



def mrcm_attribute_domain_extra():
    """Two content defects the MRCM attribute-domain refset assertions want.

    `domainid-exists-in-domain-refset` joins the attribute domain's domainId to
    the domain refset's members, and `valid-attributeingroupcardinality`
    requires an ungrouped attribute to have in-group cardinality exactly 0..0.
    Neither had anything to find while every authored row was internally
    consistent.
    """
    return [
        # NOT the same absent id the domain refset uses. ABSENT is already a
        # referencedComponentId there - deliberately, for the "refers to a valid
        # concept" family - so a domainId of ABSENT is found in the domain refset
        # and this assertion stays silent. One absent id cannot serve both.
        (member_id('attrdom-domain-absent'), CURR, '1', CORE_MODULE, ATTRIBUTE_DOMAIN_REFSET,
         ACTIVE_E, '999999999998', '1', '0..*', '0..1', MANDATORY, ALL_CONTENT),
        (member_id('attrdom-ungrouped-cardinality'), CURR, '1', CORE_MODULE,
         ATTRIBUTE_DOMAIN_REFSET, ACTIVE_D, ROOT_CONCEPT, '0', '0..1', '0..1',
         OPTIONAL, PRECOORDINATED),
    ]


def rows_mrcm_attribute_range():
    """... referencedComponentId rangeConstraint attributeRule ruleStrengthId
    contentTypeId"""
    valid = [
        (member_id('attrrange-a'), PREV, '1', CORE_MODULE, ATTRIBUTE_RANGE_REFSET, ACTIVE_A,
         '<< ' + ROOT_CONCEPT, '<< ' + ROOT_CONCEPT + ': [0..*] ' + ACTIVE_A + ' = << ' + ROOT_CONCEPT,
         MANDATORY, ALL_CONTENT),
        (member_id('attrrange-b'), PREV, '1', CORE_MODULE, ATTRIBUTE_RANGE_REFSET, ACTIVE_B,
         '<< ' + ACTIVE_B, '<< ' + ROOT_CONCEPT + ': [0..1] ' + ACTIVE_B + ' = << ' + ACTIVE_B,
         OPTIONAL, PRECOORDINATED),
    ]
    flawed = [
        # An empty rangeConstraint, which the MRCM requires.
        (member_id('attrrange-empty'), CURR, '1', CORE_MODULE, ATTRIBUTE_RANGE_REFSET, ACTIVE_C,
         '', '', MANDATORY, ALL_CONTENT),
        # A range over a concept this release does not contain.
        (member_id('attrrange-absent'), CURR, '1', OTHER_MODULE, ATTRIBUTE_RANGE_REFSET, ABSENT,
         '<< ' + ABSENT, '<< ' + ABSENT + ': [0..*] ' + ABSENT + ' = << ' + ABSENT,
         MANDATORY, ALL_CONTENT),
        # A repeated member id, for the -unique-id assertion.
        (member_id('attrrange-a'), CURR, '1', CORE_MODULE, ATTRIBUTE_RANGE_REFSET, ACTIVE_D,
         '<< ' + ACTIVE_D, '<< ' + ROOT_CONCEPT + ': [0..*] ' + ACTIVE_D + ' = << ' + ACTIVE_D,
         MANDATORY, ALL_CONTENT),
    ]
    return valid, flawed


MODULE_SCOPE_REFSET = '723563008'
UNDECLARED_MODULE = '32506021000036107'   # the AMT module, deliberately not in the MDRS


def rows_mrcm_module_scope():
    """id effectiveTime active moduleId refsetId referencedComponentId mrcmRuleRefsetId

    This file was never authored, so nineteen assertions read the fixture's own
    three rows and found them consistent. The flaws are the same shapes the
    other three MRCM refsets get, plus a member in a module this release does
    not declare a dependency on, which is what the mdrs-violation family wants.
    """
    valid = [
        (member_id('scope-core'), PREV, '1', CORE_MODULE, MODULE_SCOPE_REFSET,
         CORE_MODULE, ALL_CONTENT),
    ]
    flawed = [
        # referencedComponentId that is no concept in this release
        (member_id('scope-absent'), CURR, '1', CORE_MODULE, MODULE_SCOPE_REFSET,
         ABSENT, ALL_CONTENT),
        # an active member for an inactive concept
        (member_id('scope-inactive'), CURR, '1', CORE_MODULE, MODULE_SCOPE_REFSET,
         INACTIVE, ALL_CONTENT),
        # a REPEATED member id, for the -unique-id assertion
        (member_id('scope-core'), CURR, '1', CORE_MODULE, MODULE_SCOPE_REFSET,
         ACTIVE_A, ALL_CONTENT),
        # a member on a module this release never declared: the whole
        # mdrs-violation family exists for exactly this
        (member_id('scope-undeclared'), CURR, '1', UNDECLARED_MODULE, MODULE_SCOPE_REFSET,
         ACTIVE_B, ALL_CONTENT),
    ]
    return valid, flawed


def rows_concrete_values():
    """id effectiveTime active moduleId sourceId value relationshipGroup typeId
    characteristicTypeId modifierId"""
    valid = [
        ('3100000001021', PREV, '1', CORE_MODULE, ACTIVE_A, '#5', '0', ACTIVE_B, INFERRED, NOT_REFINABLE),
        ('3100000002022', PREV, '1', CORE_MODULE, ACTIVE_B, '"TABLET"', '1', ACTIVE_C, INFERRED, NOT_REFINABLE),
    ]
    flawed = [
        # A concrete value must be #number or "string": this is neither, and
        # fires the concrete-value literal checks.
        ('3100000003023', CURR, '1', CORE_MODULE, ACTIVE_C, '5', '0', ACTIVE_B, INFERRED, NOT_REFINABLE),
        # A source concept this release does not contain.
        ('3100000004024', CURR, '1', CORE_MODULE, ABSENT, '#10', '0', ACTIVE_B, INFERRED, NOT_REFINABLE),
        # A REPEATED id, for -unique-id.
        ('3100000001021', CURR, '1', CORE_MODULE, ACTIVE_D, '#7', '0', ACTIVE_B, INFERRED, NOT_REFINABLE),
        # A module the module dependency refset does not declare, for
        # -mdrs-violation.
        ('3100000005025', CURR, '1', '733073007', ACTIVE_E, '#3', '0', ACTIVE_B, INFERRED, NOT_REFINABLE),
        # A typeId that is not a concept in this release, for -valid-typeid:
        # that assertion left-joins concept_s on typeid and reports the misses.
        ('3100000007027', CURR, '1', CORE_MODULE, ACTIVE_A, '#4', '0', ABSENT, INFERRED, NOT_REFINABLE),
    ]
    return valid, flawed


def concrete_delta_only():
    """A delta row that is NOT in the full file.

    release-type-delta-validation joins the delta to the full on every column
    and reports what does not match, so a delta row absent from the full file is
    exactly what it exists to find.
    """
    return [('3100000008028', CURR, '1', CORE_MODULE, ACTIVE_B, '#8', '0', ACTIVE_C, INFERRED, NOT_REFINABLE)]


def concrete_inactive_both_releases():
    """Inactive in BOTH releases, with the effective time moved and nothing else.

    -successive-states looks for a component that is inactive in this snapshot
    AND in the previous one, identical in every other column, with a different
    effectiveTime - an inactivation restated for no reason. Returns the previous
    and current forms of the same row.
    """
    prev = ('3100000009029', PREV, '0', CORE_MODULE, ACTIVE_C, '#6', '0', ACTIVE_B, INFERRED, NOT_REFINABLE)
    curr = ('3100000009029', CURR, '0', CORE_MODULE, ACTIVE_C, '#6', '0', ACTIVE_B, INFERRED, NOT_REFINABLE)
    return prev, curr


def concrete_full_only():
    """A row that exists in FULL but in neither the delta nor the previous full.

    Deliberately breaks the derivative chain for ONE row, which is what the
    release-type full/delta/snapshot assertions exist to detect. Without it they
    run over a consistent release, find nothing, and prove only that they
    execute. The description files already carry inconsistencies of this kind,
    which is why the description release-type assertions fire and these did not.
    """
    return [('3100000006026', CURR, '1', CORE_MODULE, ACTIVE_A, '#99', '0', ACTIVE_B, INFERRED, NOT_REFINABLE)]


def undeclared_module_row(stem):
    """One member per MRCM refset on a module this release does not declare.

    file-centric-snapshot-mdrs-violation-<refset> joins the refset to the module
    dependency rows for THIS module and version, so it needs both: a module
    dependency row stamped for the release, which ci/author_intl_fixture.py adds,
    and a component on a module that row does not cover.
    """
    if stem == 'der2_sssssssRefset_MRCMDomain':
        return [(member_id('domain-undeclared'), CURR, '1', UNDECLARED_MODULE, DOMAIN_REFSET,
                 ACTIVE_C, '<< ' + ACTIVE_C, ROOT_CONCEPT, '<< ' + ACTIVE_C, '',
                 '[[+id(<< ' + ACTIVE_C + ')]]', '', 'http://snomed.org/mrcm')]
    if stem == 'der2_cissccRefset_MRCMAttributeDomain':
        return [(member_id('attrdom-undeclared'), CURR, '1', UNDECLARED_MODULE,
                 ATTRIBUTE_DOMAIN_REFSET, ACTIVE_C, ROOT_CONCEPT, '1', '0..*', '0..1',
                 MANDATORY, ALL_CONTENT)]
    if stem == 'der2_ssccRefset_MRCMAttributeRange':
        return [(member_id('attrrange-undeclared'), CURR, '1', UNDECLARED_MODULE,
                 ATTRIBUTE_RANGE_REFSET, ACTIVE_C, '<< ' + ROOT_CONCEPT,
                 '[[+id(<< ' + ROOT_CONCEPT + ')]]', MANDATORY, ALL_CONTENT)]
    return []


FILES = {
    'der2_sssssssRefset_MRCMDomain': (
        ['id', 'effectiveTime', 'active', 'moduleId', 'refsetId', 'referencedComponentId',
         'domainConstraint', 'parentDomain', 'proximalPrimitiveConstraint',
         'proximalPrimitiveRefinement', 'domainTemplateForPrecoordination',
         'domainTemplateForPostcoordination', 'guideURL'],
        rows_mrcm_domain),
    'der2_cissccRefset_MRCMAttributeDomain': (
        ['id', 'effectiveTime', 'active', 'moduleId', 'refsetId', 'referencedComponentId',
         'domainId', 'grouped', 'attributeCardinality', 'attributeInGroupCardinality',
         'ruleStrengthId', 'contentTypeId'],
        rows_mrcm_attribute_domain),
    'der2_ssccRefset_MRCMAttributeRange': (
        ['id', 'effectiveTime', 'active', 'moduleId', 'refsetId', 'referencedComponentId',
         'rangeConstraint', 'attributeRule', 'ruleStrengthId', 'contentTypeId'],
        rows_mrcm_attribute_range),
    'der2_cRefset_MRCMModuleScope': (
        ['id', 'effectiveTime', 'active', 'moduleId', 'refsetId', 'referencedComponentId',
         'mrcmRuleRefsetId'],
        rows_mrcm_module_scope),
    'sct2_RelationshipConcreteValues': (
        ['id', 'effectiveTime', 'active', 'moduleId', 'sourceId', 'value',
         'relationshipGroup', 'typeId', 'characteristicTypeId', 'modifierId'],
        rows_concrete_values),
}


def write(path: pathlib.Path, header, rows):
    # CRLF: RF2 says so and RVF's MySQL loader reads `lines terminated by '\r\n'`.
    body = '\r\n'.join(['\t'.join(header)] + ['\t'.join(r) for r in rows]) + '\r\n'
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(body.encode('utf-8'))
    return len(rows)


def main():
    total = 0
    for stem, (header, builder) in FILES.items():
        valid, flawed = builder()
        concrete = stem == 'sct2_RelationshipConcreteValues'
        full_only = concrete_full_only() if concrete else []
        delta_only = concrete_delta_only() if concrete else []
        prev_inactive, curr_inactive = (concrete_inactive_both_releases() if concrete else (None, None))
        prev_extra = [prev_inactive] if concrete else []
        curr_extra = [curr_inactive] if concrete else []
        # A row in FULL that is in neither the delta nor the previous full, and
        # one in DELTA that never reaches the full file. Every refset has
        # release-type full/delta/snapshot assertions whose whole subject is that
        # chain, and with a self-consistent release they had nothing to find -
        # which is why twelve of them stayed silent after the first MRCM pass.
        undeclared = undeclared_module_row(stem)
        flawed = flawed + undeclared
        if stem == 'der2_cissccRefset_MRCMAttributeDomain':
            flawed = flawed + mrcm_attribute_domain_extra()
        prev_full_only, inactive_both, prev_delta = [], [], []
        if not concrete and valid:
            # Distinct member ids, present in ONE file only. A first attempt
            # reused a flawed row, which appears in the snapshot and delta too -
            # so it broke no chain and moved nothing. "Full only" has to mean
            # only.
            def stamp(row, seed, effective=CURR, active=None):
                head = (member_id(stem + seed), effective)
                tail = tuple(row[2:])
                if active is not None:
                    tail = (active,) + tail[1:]
                return head + tail
            full_only = [stamp(valid[0], '-full-only')]
            delta_only = [stamp(valid[0], '-delta-only')]

            # release-type-full-validation-<refset> reads the PREVIOUS full left
            # joined to the prospective one, so it fires on a row the previous
            # release published and this one dropped. Only the previous full
            # carries it.
            prev_full_only = [stamp(valid[0], '-dropped-from-full', effective=PREV)]

            # ...-successive-states wants a row that is INACTIVE now and was
            # inactive in the previous snapshot too, at a different
            # effectiveTime: "inactive but no active state found in the previous
            # snapshot". Two states that never alternated.
            inactive_both = [stamp(valid[0], '-never-active', active='0')]

            # ...-delta-previous-snapshot-validation is `WHERE NOT b.id IS
            # NULL` - it fires when a delta row IS a verbatim repeat of a row
            # already in the previous snapshot, which is the opposite of what a
            # first reading assumed. A delta should carry new states only, so
            # restating an old one is the defect. An exact copy of valid[0],
            # which the previous snapshot holds unchanged, is therefore the
            # content: same id, same effectiveTime, same everything.
            prev_delta = [valid[0]]
        prev_inactive_row = [(r[0], PREV) + tuple(r[2:]) for r in inactive_both]
        for release, kinds in ((PREV, {'Snapshot': valid + prev_extra + prev_inactive_row,
                                       'Full': valid + prev_extra + prev_full_only,
                                       'Delta': valid + prev_extra}),
                               (CURR, {'Snapshot': valid + flawed + curr_extra + inactive_both,
                                       'Full': valid + flawed + full_only + curr_extra + inactive_both,
                                       'Delta': flawed + delta_only + curr_extra + prev_delta})):
            base = ROOT / f'SnomedCT_RegressionTest_{release}' / 'RF2Release'
            for kind, rows in kinds.items():
                # The current release's Snapshot and Full carry the previous rows
                # at their ORIGINAL effective time, which is what makes
                # "full = previous full + delta" hold.
                name = f'{stem}{kind}_INT_{release}.txt'
                total += write(base / kind / name, header, rows)
                print(f"  {release} {kind:<9} {name}  {len(rows)} rows")
    print(f"  {total} rows written")


if __name__ == '__main__':
    main()
