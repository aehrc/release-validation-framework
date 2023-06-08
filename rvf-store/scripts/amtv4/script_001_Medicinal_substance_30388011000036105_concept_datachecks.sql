/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result > 1)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'01b Some DNF Medicinal substances exist',
	null,
	null
from <PROSPECTIVE>.concept_active a join <PROSPECTIVE>.transitiveclosuretable t on t.sourceid = a.id where t.destinationId = 30388011000036105 or (a.id = 30388011000036105 and t.destinationid = 30344011000036106);
*/

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'02 All types of Medicinal substance are 900000000000074008|primitive',
	null,
	null
from <PROSPECTIVE>.concept_active           where definitionStatusId != 900000000000074008           and isKindOf_cr(id,30388011000036105);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'03 All concepts with semantic tag (AU substance) are types of 30344011000036106|Australian substance (AU substance)',
	null,
	null
from <PROSPECTIVE>.description_active           where isActiveConcept_cr(conceptId) AND typeId = 900000000000003001           and SUBSTRING(term,-14,14) = '(AU substance)'           and NOT isKindOf_cr(conceptId,30344011000036106);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'04 All concepts that are types of 30388011000036105|medicinal substance have semantic tag (AU substance)',
	null,
	null
from <PROSPECTIVE>.description_active           where isKindOf_cr(conceptId,30388011000036105)           and typeId = 900000000000003001           and SUBSTRING(term,-14,14) != '(AU substance)';

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'05a Stated Medicinal substance(30388011000036105) is the only child of 30344011000036106|Australian substance',
	null,
	null
from <PROSPECTIVE>.concept_active           where isChildOf_cr(id,30344011000036106)           and id != 30388011000036105;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'05b DNF Medicinal substance(30388011000036105) is the only child of 30344011000036106|Australian substance',
	null,
	null
from <PROSPECTIVE>.concept_active           where isChildOf_cr(id,30344011000036106)           and id != 30388011000036105;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'06b DNF is modification of relationships(30394011000036104) relationships only have sourceId 30388011000036105|medicinal substance',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 30394011000036104           and NOT isKindOf_cr(sourceId,30388011000036105);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'07b DNF is modification of relationships(30394011000036104) only have destinationId 30388011000036105|medicinal substance',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 30394011000036104           and NOT isKindOf_cr(destinationId,30388011000036105);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'08b DNF Medicinal substances only have [30394011000036104|is modification of] OR [116680003|Is a] relationships',
	null,
	null
from <PROSPECTIVE>.relationship_active           where isKindOf_cr(sourceId,30388011000036105)           and typeId not in (30394011000036104,116680003);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'09b DNF is modification of(30394011000036104) relationships have a maximum cardinality of 1',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 30394011000036104           group by sourceId           having count(destinationId) > 1;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'10b DNF All Medicinal substance relationships are ungrouped (group = 0)',
	null,
	null
from <PROSPECTIVE>.relationship_active           where isKindOf_cr(sourceId,30388011000036105)           and relationshipgroup != 0;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'14b DNF All Medicinal substances are disjoint (Medicinal substances arent subtypes of other Medicinal substances)',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 116680003           and isDescendentOf_cr(sourceId,30388011000036105)           and isDescendentOf_cr(destinationid,30388011000036105);

