
/********************************************************************************
	component-centric-snapshot-concept-missing-axiom

	Assertion:
	Active concepts have at least one active axiom in the same module as the concepts themselves.

	AU PATCH (assertions-au, ruling R1): the axiom may also be in a module linked
	to the concept's module by an active MDRS row, in either direction. An
	extension that republishes an international concept's axiom in its own
	module is doing what an extension is for. An active concept with no active
	axiom at all, or only in an unrelated module, still fails.

********************************************************************************/
	insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select
		<RUNID>,
		'<ASSERTIONUUID>',
		a.id,
		concat('CONCEPT: id=',a.id, ': Active concept has no active axiom in the same module as the concept.'),
		a.id,
        'curr_concept_s'
	from curr_concept_s a
	where a.active = '1'
	and a.id != 138875005
	and NOT EXISTS (
		select 1 from curr_owlexpressionrefset_s o
		where o.referencedcomponentid = a.id
			and o.active = '1'
			and (o.moduleid = a.moduleid
				or exists (
					select 1 from curr_moduledependencyrefset_s m
					where m.active = '1'
						and ((m.moduleid = a.moduleid and m.referencedcomponentid = o.moduleid)
							or (m.moduleid = o.moduleid and m.referencedcomponentid = a.moduleid)))));
