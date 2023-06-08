insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'01 All Australian concept (30561011000036101) are on AMT module (900062011000036108)',
	null,
	null
from <PROSPECTIVE>.concept_active           where isKindOf_cr(id, 30561011000036101)           and moduleId != 900062011000036108;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'02 All AMT metadata all on AMT module (900062011000036108)',
	null,
	null
from <PROSPECTIVE>.concept_active          where isKindOf_cr(id, 900000000000441003)          and id not in (select distinct id from rf2_ir_concepts_<SNAPSHOT>)          and moduleId != 900062011000036108          and id not in (161771000036108,32570231000036109,32570271000036106,32570871000036105);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'03 All AMT descriptions for AMT concepts on AMT module',
	null,
	null
from <PROSPECTIVE>.description_<SNAPSHOT>           where moduleId != 900062011000036108           and conceptId not in (select distinct id from rf2_ir_concepts_<SNAPSHOT>)           and conceptId not in (161771000036108,32570231000036109,32570271000036106,32570871000036105);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'04 All AMT relationships for AMT concepts on AMT module',
	null,
	null
from <PROSPECTIVE>.relationship_<SNAPSHOT>           where moduleId != 900062011000036108           and sourceId not in (select distinct id from rf2_ir_concepts_<SNAPSHOT>)           and sourceId not in (161771000036108,32570231000036109,32570271000036106,32570871000036105);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'05 All AMT 7 box refset content are on AMT module',
	null,
	null
from <PROSPECTIVE>.simplerefset_<SNAPSHOT>           where moduleId != 900062011000036108           and refsetId in (929360021000036102,929360031000036100,929360041000036105,929360051000036108,929360061000036106,929360071000036103,929360081000036101);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'06 All Non-automatable name reference set on AMT module',
	null,
	null
from <PROSPECTIVE>.simplerefset_<SNAPSHOT>           where moduleId != 900062011000036108           and refsetId = 178001000036102;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'07 All AMT concrete domain content on AMT module',
	null,
	null
from <PROSPECTIVE>.ccsrefset_<SNAPSHOT>           where moduleId != 900062011000036108           union           select * from <PROSPECTIVE>.ccirefset_<SNAPSHOT>           where moduleId != 900062011000036108           and refsetId != 900000000000456007;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'08 AMT substance map and OSR refset on AMT module',
	null,
	null
from <PROSPECTIVE>.csrefset_<SNAPSHOT>           where refsetid in (172071000036109,281000036105)           and moduleId != 900062011000036108;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'09 All OII refset entries on AMT module',
	null,
	null
from <PROSPECTIVE>.srefset_<SNAPSHOT>       where refsetId = 172061000036101       and moduleId != 900062011000036108;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'10 ARTGID map  and Preferred component order reference set entries on AMT module',
	null,
	null
from <PROSPECTIVE>.irefset_<SNAPSHOT>           where refsetId in (172051000036104,11000168105)           and moduleId != 900062011000036108;

