
/********************************************************************************
	component-centric-snapshot-language-unique-textdefinition

	Assertion:
	Language refset members have the wrong module id in	the snapshot file.

	AU PATCH (assertions-au, ruling R1/R9): one config-independent rule. A member
	may sit in a different module from its description when an active MDRS row
	links the two modules in either direction - that is what an extension
	language refset is. The former <INCLUDED_MODULES> branches (and the dependency
	anti-join only the extension branch used) are gone, so the answer no longer
	depends on how the run was configured.
********************************************************************************/


	insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select
		<RUNID>,
		'<ASSERTIONUUID>',
		a.referencedcomponentid,
		concat('Language refset member: id=',a.id, ': Member has the wrong module.'),
		a.id,
        'curr_langrefset_s'
	from curr_langrefset_s a 
	left join curr_description_s b on a.referencedcomponentid = b.id
	where a.active = '1'
		and b.active = '1'
		and a.moduleid <> b.moduleid
		and not (b.moduleid = '900000000000012004' and a.moduleid = '900000000000207008')
		and not exists (
			select 1 from curr_moduledependencyrefset_s m
			where m.active = '1'
				and ((m.moduleid = a.moduleid and m.referencedcomponentid = b.moduleid)
					or (m.moduleid = b.moduleid and m.referencedcomponentid = a.moduleid)));

    commit;
