/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result > 1)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'01 Some 700000111000036105 [Strength reference set] entries exist',
	null,
	null
from <PROSPECTIVE>.ccsrefset_active where refsetId = 700000111000036105;
*/

/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result = 1)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'02 700000111000036105 [Strength reference set] is a child of 700001351000036100|Measurement type float (foundation metadata concept)',
	null,
	null
select isChildOf_cr(700000111000036105,700001351000036100);
*/

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'03 All active referencedComponents are active 30364011000036101 [has Australian BoSS] relationships',
	null,
	null
from <PROSPECTIVE>.ccsrefset_active            where refsetId = 700000111000036105           and referencedComponentId not in (select id from <PROSPECTIVE>.relationship_active                      where typeId = 30364011000036101);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'03b All active 30364011000036101 [has Australian BoSS] relationships are actively referenced',
	null,
	null
from <PROSPECTIVE>.relationship_active where typeId = 30364011000036101           and id not in (select referencedComponentId from <PROSPECTIVE>.ccsrefset_active where refsetId = 700000111000036105);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'04 All UnitIds are Kinds of 700000041000036105 [composite unit of measure]',
	null,
	null
from <PROSPECTIVE>.ccsrefset_active            where refsetId = 700000111000036105           and NOT isKindOf_cr(value1, 700000041000036105);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'05 All OperatorIds are Kinds of 700001311000036104 [Operator id value]',
	null,
	null
from <PROSPECTIVE>.ccsrefset_active            where refsetId = 700000111000036105           and NOT isKindOf_cr(value2, 700001311000036104);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'06 All OperatorIds are 700000051000036108 [Equal to]',
	null,
	null
from <PROSPECTIVE>.ccsrefset_active            where refsetId = 700000111000036105           and value2 != 700000051000036108;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'07 All values are numerics',
	null,
	null
from <PROSPECTIVE>.ccsrefset_active           where refsetId = 700000111000036105           and NOT value3 REGEXP '[0-9]+(\\.[0-9])?[0-9]*';

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'08 All values are strength values are greater than or equal to zero',
	null,
	null
from <PROSPECTIVE>.ccsrefset_active           where refsetId = 700000111000036105           and value3 < 0;

