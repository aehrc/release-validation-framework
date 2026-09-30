
/******************************************************************************** 
	component-centric-snapshot-description-valid-characters

	Assertion:
	Active Terms of active concept consist of valid characters.

	AU PATCH (assertions-au, ruling R3): the special-character rule does not
	apply to AMT FSNs, which reproduce ARTG-registered names ("... #20 ..."). The
	FSN statement exempts descendants-or-self of 373873005 |Pharmaceutical /
	biologic product|, 774167006 |Product name| and 260787004 |Physical object|,
	using the closure macros the amtv4 pack uses for the same hierarchies. The
	synonym statement is unchanged.

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
	and not isKindOf_cr(a.conceptid, 373873005)
	and not isKindOf_cr(a.conceptid, 774167006)
	and not isKindOf_cr(a.conceptid, 260787004);
	
	
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
	