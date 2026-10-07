
/******************************************************************************** 
	component-centric-snapshot-description-valid-characters

	Assertion:
	Active Terms of active concept consist of valid characters.

	AU PATCH (assertions-au, ruling R3): the special-character rule does not
	apply to AMT FSNs, which reproduce ARTG-registered names ("... #20 ..."). The
	FSN statement exempts descendants-or-self of 373873005 |Pharmaceutical /
	biologic product|, 774167006 |Product name| and 260787004 |Physical object|.
	The synonym statement is unchanged.

	The ancestry is a recursive CTE over the inferred IS A relationships, not
	the isKindOf_cr macros the amtv4 pack uses: those come from the AMT
	pre-requisites, which an international-corpus run on the MySQL engine does
	not load, and the assertion then failed to execute at all. The recursion
	starts only from concepts whose FSN contains a flagged character, so it
	walks a handful of ancestries rather than the whole hierarchy.

********************************************************************************/
	
/* 	view of current snapshot made by finding all the active term for active concepts containing invalid character*/

/* www.snomed.org/tig?t=terms_SpecialCharacters */
	
	/* 	inserting exceptions in the result table for FSN*/
	insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
		<RUNID>,
		'<ASSERTIONUUID>',
		a.conceptid,
		concat('DESCRIPTION ID=',a.id, ': FSN=',a.term, ' contains invalid character.'),
		a.id,
        'curr_description_d'
	from  curr_description_d a , curr_concept_s b 
	where a.active = 1
	and b.active = 1
	and a.conceptid = b.id
	and a.typeid ='900000000000003001'
	and term REGEXP '[\\\t\r\n\Z\@$#]'
	and cast(a.effectivetime as datetime) = (select max(cast(z.effectivetime as datetime)) from curr_description_d z where z.id = a.id)
	and a.conceptid not in ('373873005', '774167006', '260787004')
	and a.conceptid not in (
		with recursive ancestor (conceptid, ancestorid) as (
			select r.sourceid, r.destinationid
			from curr_relationship_s r
			where r.active = 1
			and r.typeid = '116680003'
			and r.sourceid in (
				select f.conceptid from curr_description_d f
				where f.active = 1
				and f.typeid = '900000000000003001'
				and f.term REGEXP '[\\\t\r\n\Z\@$#]')
			union
			select an.conceptid, r.destinationid
			from ancestor an
			join curr_relationship_s r on r.sourceid = an.ancestorid
			where r.active = 1
			and r.typeid = '116680003'
		)
		select conceptid from ancestor
		where ancestorid in ('373873005', '774167006', '260787004'));
	
	
	/* 	inserting exceptions in the result table for Synonym */
	insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
		<RUNID>,
		'<ASSERTIONUUID>',
		a.conceptid,
		concat('DESCRIPTION ID=',a.id, ': Synonym=',a.term, ' contains invalid character.'),
		a.id,
        'curr_description_d'
	from  curr_description_d a , curr_concept_s b 
	where a.active = 1
	and b.active = 1
	and a.conceptid = b.id
	and a.typeid ='900000000000013009'
	and term REGEXP '[\\\t\r\n\Z@$]'
	and cast(a.effectivetime as datetime) = (select max(cast(z.effectivetime as datetime)) from curr_description_d z where z.id = a.id);
	