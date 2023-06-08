/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result > 0)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'01 rf2_cr_irefset_full has been populated',
	null,
	null
from TableIsPopulated('<PROSPECTIVE>.irefset_<FULL>');
*/

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'02 id field is a valid 128 bit unsigned integer/UUID',
	null,
	null
from <PROSPECTIVE>.irefset_<FULL> where not isValidUUID(id);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'03 effectiveTime is valid Timeformat',
	null,
	null
from <PROSPECTIVE>.irefset_<FULL> where NOT isValidTimeFormat(effectiveTime);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'04 active is boolean value',
	null,
	null
from <PROSPECTIVE>.irefset_<FULL> where active != 1 AND active != 0;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'05 moduleId is a descendant of 900000000000443000|Module| within the metadata hierarchy',
	null,
	null
FROM <PROSPECTIVE>.irefset_<FULL> WHERE NOT isValidModuleId(moduleId);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'06 refsetId is a descendant of  900000000000455006 | Reference set | within the metadata hierarchy',
	null,
	null
FROM <PROSPECTIVE>.irefset_<FULL> WHERE NOT isValidRefsetId_cr(refsetId);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'07 referencedComponentId is a valid ComponentId',
	null,
	null
FROM <PROSPECTIVE>.irefset_<FULL>            WHERE NOT isValidComponentId_cr(referencedComponentId)           AND NOT Stated_isValidComponentId_cr(referencedComponentId);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'08 Only one reference set member record with the same id field will be current at any point in time',
	null,
	null
from <PROSPECTIVE>.irefset_<FULL> group by id, effectiveTime having count(*) > 1;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'09 refSetId and referencedComponentId fields will not change between two rows with the same id, in other words they are immutable',
	null,
	null
FROM <PROSPECTIVE>.irefset_<FULL>            GROUP BY id HAVING COUNT(distinct refSetId,referencedComponentId) > 1;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'10 moduleId is a ConceptId (referential integrity)',
	null,
	null
from <PROSPECTIVE>.irefset_<FULL> as refset where moduleId not in (select id from <PROSPECTIVE>.concept_<FULL>);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'11 refsetId is a ConceptId (referential integrity)',
	null,
	null
from <PROSPECTIVE>.irefset_<FULL> as refset where refsetId not in (select id from <PROSPECTIVE>.concept_<FULL>);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'12 value1 is a 32-bit signed integer',
	null,
	null
from <PROSPECTIVE>.irefset_<FULL> where NOT isPositiveInteger(value1) AND value1 != 0;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'13 All currently active references are unique within rf2_cr_irefset_active',
	null,
	null
from <PROSPECTIVE>.irefset_active           group by refsetId,referencedComponentId,value1           having count(1) > 1;

