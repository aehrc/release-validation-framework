/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result > 1)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'01 Some 700000141000036106 [Unit of use size reference set] entries exist',
	null,
	null
from <PROSPECTIVE>.ccsrefset_active where refsetId = 700000141000036106;
*/

/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result = 1)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'02 700000141000036106 [Unit of use size reference set] is a child of 700001351000036100|Measurement type float (foundation metadata concept)',
	null,
	null
select isChildOf_cr(700000141000036106,700001351000036100);
*/

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'03 All active referencedComponents are active 30548011000036101 [has unit of use]',
	null,
	null
from <PROSPECTIVE>.ccsrefset_active            where refsetId = 700000141000036106           and referencedComponentId not in (select id from <PROSPECTIVE>.relationship_active                      where typeId = 30548011000036101);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'03b All active 30548011000036101 [has unit of use] relationships are actively referenced (except where sourceId IS MPUU or TPUU)',
	null,
	null
from <PROSPECTIVE>.relationship_active where typeId = 30548011000036101           and id not in (select referencedComponentId from <PROSPECTIVE>.ccsrefset_active where refsetId = 700000141000036106)           and sourceId not in (30450011000036109,30425011000036101);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'04 All UnitIds are Kinds of 30428011000036107 [unit of measure]',
	null,
	null
from <PROSPECTIVE>.ccsrefset_active            where refsetId = 700000141000036106           and NOT isKindOf_cr(value1, 30428011000036107);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'05 All OperatorIds are Kinds of 700001311000036104 [Operator id value]',
	null,
	null
from <PROSPECTIVE>.ccsrefset_active            where refsetId = 700000141000036106           and NOT isKindOf_cr(value2, 700001311000036104);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'06 All OperatorIds are 700000051000036108 [Equal to]',
	null,
	null
from <PROSPECTIVE>.ccsrefset_active            where refsetId = 700000141000036106           and value2 != 700000051000036108;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'07 All values are numerics',
	null,
	null
from <PROSPECTIVE>.ccsrefset_active           where refsetId = 700000141000036106           and NOT value3 REGEXP '[0-9]+(\\.[0-9])?[0-9]*';

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'08 All UoU.Size values are greater than zero',
	null,
	null
from <PROSPECTIVE>.ccsrefset_active           where refsetId = 700000141000036106           and (value3 < 0 or value3 = 0);

