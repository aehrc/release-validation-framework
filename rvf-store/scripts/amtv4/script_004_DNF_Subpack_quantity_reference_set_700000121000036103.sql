/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result > 1)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'01 Some 700000121000036103 [Subpack quantity reference set ] entries exist',
	null,
	null
from <PROSPECTIVE>.ccirefset_active where refsetId = 700000121000036103;
*/

/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result = 1)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'02 700000121000036103 [Subpack quantity reference set ] is a child of 700001361000036102 [Measurement type integer] (foundation metadata concept)',
	null,
	null
select isChildOf_cr(700000121000036103,700001361000036102);
*/

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'03a All active referencedComponents are active 30454011000036104 [has subpack]relationships',
	null,
	null
from <PROSPECTIVE>.ccirefset_active            where refsetId = 700000121000036103           and referencedComponentId not in (select id from <PROSPECTIVE>.relationship_active                      where typeId = 30454011000036104);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'03b All active 30454011000036104 [has subpack]relationships are actively referenced',
	null,
	null
from <PROSPECTIVE>.relationship_active where typeId = 30454011000036104           and id not in (select referencedComponentId from <PROSPECTIVE>.ccirefset_active where refsetId = 700000121000036103);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'04 All UnitIds are Kinds of 30428011000036107 [unit of measure]',
	null,
	null
from <PROSPECTIVE>.ccirefset_active            where refsetId = 700000121000036103           and NOT isKindOf_cr(value1, 30428011000036107);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'05 All OperatorIds are Kinds of 700001311000036104 [Operator id value]',
	null,
	null
from <PROSPECTIVE>.ccirefset_active            where refsetId = 700000121000036103           and NOT isKindOf_cr(value2, 700001311000036104);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'06 All OperatorIds are 700000051000036108 [Equal to]',
	null,
	null
from <PROSPECTIVE>.ccirefset_active            where refsetId = 700000121000036103           and value2 != 700000051000036108;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'07 All values are numerics',
	null,
	null
from <PROSPECTIVE>.ccirefset_active            where refsetId = 700000121000036103            and NOT value3 REGEXP '[0-9]+(\\.[0-9])?[0-9]*';

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'08 All values are positive integers',
	null,
	null
from <PROSPECTIVE>.ccirefset_active            where refsetId = 700000121000036103            and NOT isPositiveInteger(value3);

