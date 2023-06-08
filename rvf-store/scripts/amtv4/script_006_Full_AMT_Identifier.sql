/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result = 0)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'01 rf2_cr_identifiers_full IS empty',
	null,
	null
from TableIsPopulated('<PROSPECTIVE>.identifiers_<FULL>');
*/

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'02 identifierSchemeId a descendant of 900000000000453004|Identifier scheme| within the metadata hierarchy',
	null,
	null
FROM <PROSPECTIVE>.identifiers_<FULL> WHERE NOT isDescendentOf_cr(identifierSchemeId, 900000000000453004);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'03 alternateIdentifier is a string of some sort',
	null,
	null
FROM <PROSPECTIVE>.identifiers_<FULL> WHERE CHAR_LENGTH(alternateidentifier) = 0;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'04 effectiveTime is valid Timeformat',
	null,
	null
FROM <PROSPECTIVE>.identifiers_<FULL> WHERE NOT isValidTimeFormat(effectiveTime);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'05 active is boolean value',
	null,
	null
FROM <PROSPECTIVE>.identifiers_<FULL> WHERE active != 1 AND active != 0;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'06 moduleId is a descendant of 900000000000443000|Module| within the metadata hierarchy',
	null,
	null
FROM <PROSPECTIVE>.identifiers_<FULL> WHERE NOT isValidModuleId(moduleId);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'07 referencedComponentId references an valid componentId',
	null,
	null
from <PROSPECTIVE>.identifiers_<FULL> AS Identifiers           where NOT EXISTS (select 1 from <PROSPECTIVE>.concept_<FULL> AS T where T.id = referencedComponentId)            AND  NOT EXISTS (select 1 from <PROSPECTIVE>.description_<FULL> AS T where T.id = referencedComponentId)            AND  NOT EXISTS (select 1 from <PROSPECTIVE>.relationship_<FULL> AS T where T.id = referencedComponentId);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'08 identifierSchemeId is a ConceptId (referential integrity)',
	null,
	null
FROM <PROSPECTIVE>.identifiers_<FULL> AS concepts WHERE identifierSchemeId NOT IN (SELECT id FROM <PROSPECTIVE>.concept_<FULL>);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'09 moduleId is a ConceptId (referential integrity)',
	null,
	null
FROM <PROSPECTIVE>.identifiers_<FULL> AS concepts WHERE moduleId NOT IN (SELECT id FROM <PROSPECTIVE>.concept_<FULL>);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'09 All entries in the Identifiers file are on the AMT module(900062011000036108)',
	null,
	null
FROM <PROSPECTIVE>.identifiers_<FULL> WHERE moduleId != 900062011000036108;

