/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result > 1)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'01b Some DNF Form concepts actually exist',
	null,
	null
from <PROSPECTIVE>.concept_active where id in (select sourceid from <PROSPECTIVE>.transitiveclosuretable where destinationId = 30383011000036100 or sourceid = 30383011000036100);
*/

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'02 All types of Form are 900000000000074008|primitive',
	null,
	null
from <PROSPECTIVE>.concept_active           where definitionStatusId != 900000000000074008           and isKindOf_cr(id,30383011000036100);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'04 All concepts that are types of 30383011000036100|Form have semantic tag (AU qualifier)',
	null,
	null
from <PROSPECTIVE>.description_active           where isActiveConcept_cr(conceptId) AND isKindOf_cr(conceptId,30383011000036100)           and typeId = 900000000000003001           and SUBSTRING(term,-14,14) != '(AU qualifier)';

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'05b DNF Form(30383011000036100) is only a child of 30515011000036103|Australian qualifier',
	null,
	null
from <PROSPECTIVE>.relationship_<SNAPSHOT>           where sourceId = 30383011000036100 and typeId = 116680003 and destinationId != 30515011000036103;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'08b DNF Form concepts only have 116680003|Is a relationships',
	null,
	null
from <PROSPECTIVE>.relationship_active           where isKindOf_cr(sourceId,30383011000036100)           and typeId  != 116680003;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'07b DNF Form subtypes only have destinationId that are types of 30383011000036100|Form',
	null,
	null
from <PROSPECTIVE>.relationship_active           where isDescendentOf_cr(sourceId,30383011000036100)           and NOT isKindOf_cr(destinationId,30383011000036100);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'10b All DNF Form concept relationships are ungrouped (group = 0)',
	null,
	null
from <PROSPECTIVE>.relationship_active           where isKindOf_cr(sourceId,30383011000036100)           and relationshipgroup != 0;

