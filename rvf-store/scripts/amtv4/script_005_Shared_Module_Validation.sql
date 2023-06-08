insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'01 All AU Shared metadata concepts on shared metadata module',
	null,
	null
from <PROSPECTIVE>.concept_<SNAPSHOT>           where id in (161771000036108,32570231000036109,32570271000036106,32570871000036105)           and moduleId != 161771000036108;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'02 All Au shared metadata descriptions are on shared metadata module',
	null,
	null
from <PROSPECTIVE>.description_<SNAPSHOT>           where conceptId in (161771000036108,32570231000036109,32570271000036106,32570871000036105)           and moduleId != 161771000036108 and id != 108642491000036119;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'03 All Au shared metadata relationships are on shared metadata module',
	null,
	null
from <PROSPECTIVE>.relationship_<SNAPSHOT>           where sourceId in (161771000036108,32570231000036109,32570271000036106,32570871000036105)           and moduleId != 161771000036108;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'03a Any core model component concepts only have Model Component, or AU shared relationships',
	null,
	null
from <PROSPECTIVE>.concept_active           where moduleId = 900000000000012004           and id not in (select sourceId from <PROSPECTIVE>.relationship_active where moduleId in (900000000000012004,161771000036108)) and id != 900000000000441003;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'04 ADRS entries for shared metadata are on the shared module',
	null,
	null
from <PROSPECTIVE>.crefset_<SNAPSHOT>           where refsetId = 32570271000036106           and referencedComponentId in (select id from <PROSPECTIVE>.description_<SNAPSHOT>           where conceptId in (161771000036108,32570231000036109,32570271000036106,32570871000036105))           and moduleId != 161771000036108;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'05 Only the 20 expected concepts exist on the shared metadata module',
	null,
	null
from <PROSPECTIVE>.concept_<SNAPSHOT>           where moduleId = 161771000036108           and id not in (410670007,161771000036108,32570231000036109,32570271000036106,32570871000036105,32570661000036101,32570701000036108,1445101000168103,1445111000168100,1445121000168107,1445131000168105,1445141000168101,1445151000168104,1445161000168102,1445171000168108,1445181000168106,1445191000168109,541000168105,551000168107,561000168109)           and effectiveTime > 20180930;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'06 Only descriptions for concepts on the shared or metadata module exist on the shared metadata module',
	null,
	null
from <PROSPECTIVE>.description_<SNAPSHOT>           where moduleId = 161771000036108           and conceptId not in (select id from <PROSPECTIVE>.concept_<SNAPSHOT> where moduleId in (161771000036108,900000000000012004));

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'07 Only relationships for concepts on the shared or metadata module exist on the shared metadata module',
	null,
	null
from <PROSPECTIVE>.relationship_<SNAPSHOT>           where moduleId = 161771000036108           and sourceId not in (select id from <PROSPECTIVE>.concept_<SNAPSHOT> where moduleId in (161771000036108,900000000000012004));

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'08 All active concepts on the shared or metadata module are subtypes of 900000000000441003 | SNOMED CT Model Component | ',
	null,
	null
from <PROSPECTIVE>.concept_active                                         where moduleId = 161771000036108                                          and not isKindOf_cr(id, 900000000000441003);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'09 All Au shared metadata relationship concrete values are on shared metadata module',
	null,
	null
from <PROSPECTIVE>.relationship_concrete_value_<SNAPSHOT>           where sourceId in (161771000036108,32570231000036109,32570271000036106,32570871000036105)           and moduleId != 161771000036108;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'10 Any core model component concepts only have Model Component, or AU shared relationship concrete values',
	null,
	null
from <PROSPECTIVE>.concept_active where moduleId = 900000000000012004                                         and id IN (select sourceId from <PROSPECTIVE>.relationship_concrete_value_active where moduleId not in (900000000000012004,161771000036108));

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'11 Only relationship concrete values for concepts on the shared or metadata module exist on the shared metadata module',
	null,
	null
from <PROSPECTIVE>.relationship_concrete_value_active where moduleId in (900000000000012004,161771000036108)                                         AND sourceId NOT IN (select id from <PROSPECTIVE>.concept_active where moduleId = 900000000000012004 );

