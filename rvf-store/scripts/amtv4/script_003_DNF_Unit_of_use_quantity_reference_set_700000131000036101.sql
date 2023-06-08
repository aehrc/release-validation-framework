/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result > 1)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'01 Some 700000131000036101 [Unit of use quantity reference set] entries exist',
	null,
	null
from <PROSPECTIVE>.ccsrefset_active where refsetId = 700000131000036101;
*/

/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result = 1)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'02 700000131000036101 [Unit of use quantity reference set] is a child of 700001351000036100|Measurement type float (foundation metadata concept)',
	null,
	null
select isChildOf_cr(700000131000036101,700001351000036100);
*/

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'03a All active referencedComponents are active 30348011000036104 [has MPUU] or 30409011000036107 [has TPUU] relationships',
	null,
	null
from <PROSPECTIVE>.ccsrefset_active            where refsetId = 700000131000036101           and referencedComponentId not in (select id from <PROSPECTIVE>.relationship_active                      where typeId in (30348011000036104,30409011000036107));

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'03b All active 30348011000036104 [has MPUU] or 30409011000036107 [has TPUU] relationships are actively referenced (except where sourceId IS MPP,TPP,CTPP)',
	null,
	null
from <PROSPECTIVE>.relationship_active where typeId in (30348011000036104,30409011000036107)           and id not in (select referencedComponentId from <PROSPECTIVE>.ccsrefset_active where refsetId = 700000131000036101)           and sourceId not in (30513011000036104,30404011000036106,30537011000036101);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'04 All UnitIds are Kinds of 30428011000036107 [unit of measure]',
	null,
	null
from <PROSPECTIVE>.ccsrefset_active            where refsetId = 700000131000036101           and NOT isKindOf_cr(value1, 30428011000036107);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'05 All OperatorIds are Kinds of 700001311000036104 [Operator id value]',
	null,
	null
from <PROSPECTIVE>.ccsrefset_active            where refsetId = 700000131000036101           and NOT isKindOf_cr(value2, 700001311000036104);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'06 All OperatorIds are 700000051000036108 [Equal to]',
	null,
	null
from <PROSPECTIVE>.ccsrefset_active            where refsetId = 700000131000036101           and value2 != 700000051000036108;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'07 All values are numerics',
	null,
	null
from <PROSPECTIVE>.ccsrefset_active           where refsetId = 700000131000036101           and NOT value3 REGEXP '[0-9]+(\\.[0-9])?[0-9]*';

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'08 All UoU.Quantities are greater than zero',
	null,
	null
from <PROSPECTIVE>.ccsrefset_active           where refsetId = 700000131000036101           and (value3 < 0 or value3 = 0);

