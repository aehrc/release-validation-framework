insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'01 All (active) members of AMT simple type refsets are active',
	null,
	null
from <PROSPECTIVE>.simplerefset_active                                         where refsetId in (                                                                 select id from <PROSPECTIVE>.concept_active                                                                 where isDescendentOf_cr(id, 446609009)                                                                 and moduleId = 900062011000036108                                                                 )                                         and referencedComponentId not in (select id from <PROSPECTIVE>.concept_active);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'09 All concepts with AMT descriptions are on AMT module (excluding IS A)',
	null,
	null
from <PROSPECTIVE>.concept_<SNAPSHOT>                                         where id in (select conceptId from <PROSPECTIVE>.description_<SNAPSHOT> where moduleId = 900062011000036108)                                         and moduleId != 900062011000036108                                         and id != 116680003;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'10 All descriptions for AMT concepts on AMT module',
	null,
	null
from <PROSPECTIVE>.description_<SNAPSHOT>                                         where conceptId in (select id from <PROSPECTIVE>.concept_<SNAPSHOT> where moduleId = 900062011000036108)                                         and moduleId != 900062011000036108;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'12 All inferred relationships for AMT concepts on AMT module',
	null,
	null
from <PROSPECTIVE>.relationship_<SNAPSHOT>                                         where sourceId in (select id from <PROSPECTIVE>.concept_<SNAPSHOT> where moduleId = 900062011000036108)                                         and moduleId != 900062011000036108;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'13 All subtypes of Australian concept on AMT module',
	null,
	null
from <PROSPECTIVE>.concept_active                                         where isKindOf_cr(id, 30561011000036101)                                         and moduleId != 900062011000036108;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'14 All relationship types for AMT are on AMT module (except IS A)',
	null,
	null
from <PROSPECTIVE>.concept_<SNAPSHOT>                                         where id in (select typeId from <PROSPECTIVE>.relationship_<SNAPSHOT> where moduleId = 900062011000036108)                                         and moduleId != 900062011000036108                                         and id != 116680003;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'15 All simple refset memberships of AMT concepts are on AMT module (except Tas+Vic refsets)',
	null,
	null
from <PROSPECTIVE>.simplerefset_active                                         where referencedComponentId in (select id from <PROSPECTIVE>.concept_<SNAPSHOT> where moduleId = 900062011000036108)                                         and moduleId != 900062011000036108                                         and refsetId not in (1050711000168101, 1141581000168103);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'16 All simple refsets containing AMT concepts are on AMT module (except Tas+Vic refsets)',
	null,
	null
from <PROSPECTIVE>.simplerefset_active                                         where referencedComponentId in (select id from <PROSPECTIVE>.concept_<SNAPSHOT> where moduleId = 900062011000036108)                                         and refsetId not in (select id from <PROSPECTIVE>.concept_<SNAPSHOT> where moduleId = 900062011000036108)                                         and refsetId not in (1050711000168101, 1141581000168103);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'18 All inferred concrete domains on AMT module',
	null,
	null
from <PROSPECTIVE>.ccsrefset_<SNAPSHOT>                                         where moduleId != 900062011000036108                                         union                                         select * from <PROSPECTIVE>.ccirefset_<SNAPSHOT>                                         where moduleId != 900062011000036108                                         and refsetId != 900000000000456007;

/* SKIPPED_TEST
	: Query references a non-simple refset. Likely need to refactor this query

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'02 All ADRS referenced components that are for subtypes of 30561011000036101|Australian concept (concept)| are on AMT Module',
	null,
	null
FROM <PROSPECTIVE>.cRefset_<SNAPSHOT>           WHERE refsetId = 32570271000036106           AND referencedComponentId in (select id from <PROSPECTIVE>.description_<SNAPSHOT> where isKindOf_cr(conceptId, 30561011000036101))           AND moduleId != 900062011000036108;
*/

/* SKIPPED_TEST
	: Query references a non-simple refset. Likely need to refactor this query

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'08 All Australian common model component are for AU/core metadata descriptions',
	null,
	null
from <PROSPECTIVE>.cRefset_<SNAPSHOT>                                          WHERE refsetId = 32570271000036106                                          AND moduleId = 161771000036108                                          and referencedComponentId not in (select id from <PROSPECTIVE>.description_<SNAPSHOT>                                                                          where moduleId in (161771000036108,900000000000012004))                                          and referencedComponentId not in (select id from <PROSPECTIVE>.textdefinitions_<SNAPSHOT>                                                                          where moduleId in (161771000036108,900000000000012004));
*/

/* SKIPPED_TEST
	: Query references a non-simple refset. Likely need to refactor this query

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'08 All referenced AU/core metadata descriptions descriptions are referenced on Australian common model component module',
	null,
	null
from <PROSPECTIVE>.description_<SNAPSHOT>                                          where moduleId in (161771000036108,900000000000012004) and typeId != 900000000000003001                                          and id not in (select referencedComponentId from <PROSPECTIVE>.cRefset_<SNAPSHOT>                                                              WHERE refsetId = 32570271000036106 AND moduleId = 161771000036108)                                          and id in (select referencedComponentId from <PROSPECTIVE>.cRefset_<SNAPSHOT>                                                              WHERE refsetId = 32570271000036106);
*/

/* SKIPPED_TEST
	: Query references a non-simple refset. Likely need to refactor this query

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'08 All AMT references are for AMT descriptions',
	null,
	null
from <PROSPECTIVE>.cRefset_<SNAPSHOT>                                          WHERE refsetId = 32570271000036106                                          AND moduleId = 900062011000036108                                          and referencedComponentId not in (select id from <PROSPECTIVE>.description_<SNAPSHOT>                                                      where moduleId = 900062011000036108)                                          and referencedComponentId not in (select id from <PROSPECTIVE>.textdefinitions_<SNAPSHOT>                                                      where moduleId = 900062011000036108);
*/

/* SKIPPED_TEST
	: Query references a non-simple refset. Likely need to refactor this query

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'08 All AMT descriptions are referenced on AMT module',
	null,
	null
from <PROSPECTIVE>.description_<SNAPSHOT>                                          where moduleId = 900062011000036108 and typeId != 900000000000003001                                          and id not in (select referencedComponentId from <PROSPECTIVE>.cRefset_<SNAPSHOT>                                                              WHERE refsetId = 32570271000036106 AND moduleId = 900062011000036108)                                          and id != 170037011000036115;
*/

