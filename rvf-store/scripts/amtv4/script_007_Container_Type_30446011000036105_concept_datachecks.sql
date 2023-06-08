/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result > 1)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'01b DNF Some Container Types exist',
	null,
	null
from <PROSPECTIVE>.concept_active where id in (select sourceid from <PROSPECTIVE>.transitiveclosuretable where destinationId = 30446011000036105 or sourceid = 30446011000036105);
*/

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'02 All Container Types are 900000000000074008|primitive',
	null,
	null
FROM <PROSPECTIVE>.concept_active           WHERE     isKindOf_cr(id, 30446011000036105)           AND definitionstatusid != 900000000000074008;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'03 All Container Type FSNs have a semantic tag (AU qualifier)',
	null,
	null
FROM <PROSPECTIVE>.description_active           WHERE isActiveConcept_cr(conceptId) AND isKindOf_cr(conceptid, 30446011000036105)           AND typeid = 900000000000003001           AND term NOT REGEXP BINARY ' \\(AU qualifier\\)$';

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'04 Concept 30446011000036105|container type has FSN = container type (AU qualifier)',
	null,
	null
FROM <PROSPECTIVE>.description_active           WHERE     typeId = 900000000000003001           AND conceptId = 30446011000036105           AND term != BINARY 'container type (AU qualifier)';

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'05 Concept 30446011000036105|container type has SYN = container type',
	null,
	null
FROM <PROSPECTIVE>.description_active           WHERE     typeId = 900000000000013009           AND conceptId = 30446011000036105           AND term != BINARY 'container type';

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'06b DNF All descendents of Container Types only have destinationIds that are types of 30446011000036105|container type',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE isDescendentOf_cr(sourceid, 30446011000036105)           AND NOT isKindOf_cr(destinationid, 30446011000036105);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'07b DNF container type(30446011000036105) is a child of 30515011000036103|Australian qualifier',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE sourceId = 30446011000036105           AND typeId = 116680003           AND destinationId = 30515011000036103;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'08b DNF container type(30446011000036105) is only a child of 30515011000036103|Australian qualifier',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE sourceId = 30446011000036105           AND typeId = 116680003           AND destinationId != 30515011000036103;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'09b DNF Container Type concepts only have 116680003|Is a relationships',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE isKindOf_cr(sourceid, 30446011000036105)           AND typeid != 116680003;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'10b DNF Container Type descendents only have destinationIds that are types of 30446011000036105|container type',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE isDescendentOf_cr(sourceid, 30446011000036105)           AND NOT isKindOf_cr(destinationid, 30446011000036105);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'11b DNF All Container Type relationships are ungrouped (group = 0)',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE isKindOf_cr(sourceid, 30446011000036105)           AND relationshipgroup != 0;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'12b DNF All children of Container Type are the destination of at least one [30465011000036106|has container type] relationship',
	null,
	null
FROM <PROSPECTIVE>.concept_active            WHERE isKindOf_cr(Id, 30446011000036105)            AND id NOT IN (SELECT destinationid FROM <PROSPECTIVE>.relationship_active WHERE typeId = 30465011000036106)           and id NOT IN (SELECT destinationid FROM <PROSPECTIVE>.relationship_active WHERE typeId = 116680003);

