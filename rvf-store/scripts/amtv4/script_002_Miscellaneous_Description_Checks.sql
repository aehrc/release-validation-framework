insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'04a International Units not used in PT except for bleomycin sulfate products',
	null,
	null
FROM <PROSPECTIVE>.description_active d        JOIN <PROSPECTIVE>.crefset_active r ON referencedComponentId = d.id  WHERE     refsetid = 32570271000036106        AND r.value1 = 900000000000548007        AND isActiveConcept_cr(conceptId)        AND typeId = 900000000000013009        AND isKindOf_cr(conceptId, 30506011000036107)        AND term RLIKE 'international units'        AND NOT isKindOf_cr(conceptId, 857781000168109)        AND conceptId NOT IN (SELECT sourceId                                FROM <PROSPECTIVE>.relationship_active                               WHERE     isKindOf_cr(typeId,                                                     30348011000036104)                                     AND isKindOf_cr(destinationId,                                                     857781000168109));

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'04b All International Unit exceptions are for bleomycin sulfate products (no maintenance unless different drugs)',
	null,
	null
FROM <PROSPECTIVE>.description_active d        JOIN <PROSPECTIVE>.crefset_active r ON referencedComponentId = d.id  WHERE     refsetid = 32570271000036106        AND r.value1 = 900000000000548007        AND isActiveConcept_cr(conceptId)        AND typeId = 900000000000013009        AND isKindOf_cr(conceptId, 30506011000036107)        AND term RLIKE 'international units'        AND conceptId NOT IN (SELECT id                                FROM <PROSPECTIVE>.concept_active                               WHERE isDescendentOf_cr(id, 857781000168109)                              UNION                              SELECT sourceId                                FROM <PROSPECTIVE>.relationship_active                               WHERE     destinationId IN (SELECT id                                                             FROM <PROSPECTIVE>.concept_active                                                            WHERE isDescendentOf_cr(                                                                     id,                                                                     857781000168109))                                     AND isKindOf_cr(typeId,                                                     30348011000036104));

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'05 LD50 not used in PT',
	null,
	null
from <PROSPECTIVE>.description_active           where isActiveConcept_cr(conceptId)           and typeId = 900000000000013009           and isKindOf_cr(conceptId, 30506011000036107)           and term RLIKE 'LD50';

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'06a Kyowa units not used in PT except for 17 exceptions (test needs maintenance if count changes)',
	null,
	null
from <PROSPECTIVE>.description_active           where isActiveConcept_cr(conceptId)           and typeId = 900000000000013009           and isKindOf_cr(conceptId, 30506011000036107)           and term RLIKE 'Kyowa units';

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'06b All Kyowa units exceptions are for colaspase products (no maintenance unless different drugs)',
	null,
	null
from <PROSPECTIVE>.description_active           where isActiveConcept_cr(conceptId)           and typeId = 900000000000013009           and isKindOf_cr(conceptId, 30506011000036107)           and term RLIKE 'Kyowa units'           and conceptId not in              (select id from <PROSPECTIVE>.concept_active             where isDescendentOf_cr(id, 37712011000036104)             UNION             select sourceId from <PROSPECTIVE>.relationship_active             where destinationId in (select id from <PROSPECTIVE>.concept_active                     where isDescendentOf_cr(id, 37712011000036104))             and isKindOf_cr(typeId, 30348011000036104)             );

