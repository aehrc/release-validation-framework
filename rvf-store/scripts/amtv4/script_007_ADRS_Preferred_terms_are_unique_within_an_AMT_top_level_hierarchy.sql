insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'1 ADRS Preferred Terms are unique within 929360051000036108 Containered trade product pack reference set',
	null,
	null
from <PROSPECTIVE>.simplerefset_active                      where refsetId = 929360051000036108           group by get_cr_ADRS_PT(referencedComponentId) having count(referencedComponentId) > 1;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'2 ADRS Preferred Terms are unique within 929360061000036106 Medicinal product reference set',
	null,
	null
from <PROSPECTIVE>.simplerefset_active                      where refsetId = 929360061000036106           group by get_cr_ADRS_PT(referencedComponentId) having count(referencedComponentId) > 1;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'3 ADRS Preferred Terms are unique within 929360031000036100 Trade product unit of use reference set',
	null,
	null
from <PROSPECTIVE>.simplerefset_active                      where refsetId = 929360031000036100           group by get_cr_ADRS_PT(referencedComponentId) having count(referencedComponentId) > 1;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'4 ADRS Preferred Terms are unique within 929360041000036105 Trade product pack reference set',
	null,
	null
from <PROSPECTIVE>.simplerefset_active                      where refsetId = 929360041000036105           group by get_cr_ADRS_PT(referencedComponentId) having count(referencedComponentId) > 1;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'5 ADRS Preferred Terms are unique within 929360021000036102 Trade product reference set',
	null,
	null
from <PROSPECTIVE>.simplerefset_active                      where refsetId = 929360021000036102           group by get_cr_ADRS_PT(referencedComponentId) having count(referencedComponentId) > 1;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'6 ADRS Preferred Terms are unique within 929360071000036103 Medicinal product unit of use reference set',
	null,
	null
from <PROSPECTIVE>.simplerefset_active                      where refsetId = 929360071000036103           group by get_cr_ADRS_PT(referencedComponentId) having count(referencedComponentId) > 1;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'7 ADRS Preferred Terms are unique within 929360081000036101 Medicinal product pack reference set',
	null,
	null
from <PROSPECTIVE>.simplerefset_active                      where refsetId = 929360081000036101           group by get_cr_ADRS_PT(referencedComponentId) having count(referencedComponentId) > 1;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'8 ADRS Terms are unique within 30446011000036105 container type',
	null,
	null
from <PROSPECTIVE>.description_active           where isKindOf_cr(conceptId, 30446011000036105)           group by term           having count(conceptId) > 1;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'9 ADRS Terms are unique within 30383011000036100 form',
	null,
	null
from <PROSPECTIVE>.description_active           where isKindOf_cr(conceptId, 30383011000036100)           group by term           having count(conceptId) > 1;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'10 ADRS Terms are unique within 30428011000036107 unit of measure',
	null,
	null
from <PROSPECTIVE>.description_active           where isKindOf_cr(conceptId, 30428011000036107)           group by term           having count(conceptId) > 1;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'11 ADRS Terms are unique within 700000011000036109 unit of use',
	null,
	null
from <PROSPECTIVE>.description_active           where isKindOf_cr(conceptId, 700000011000036109)           group by term           having count(conceptId) > 1;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'12 ADRS Terms are unique within 30344011000036106 Australian substance',
	null,
	null
from <PROSPECTIVE>.description_active           where isKindOf_cr(conceptId, 30344011000036106)           group by term           having count(conceptId) > 1;

