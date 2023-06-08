insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'05 OWL Axioms for concepts on AMT Module must be on modules that are dependent AMT Module',
	null,
	null
from <PROSPECTIVE>.owlexpressionrefset_<SNAPSHOT>           where referencedComponentId in (select id from <PROSPECTIVE>.concept_<SNAPSHOT> where moduleId =  900062011000036108)           and moduleId not in (select referencedComponentId from <PROSPECTIVE>.ssrefset_<SNAPSHOT> where active and moduleId =  900062011000036108 UNION select  900062011000036108);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'06 OWL Axioms on AMT Module must be for concepts on modules AMT Module dependens on',
	null,
	null
from <PROSPECTIVE>.owlexpressionrefset_<SNAPSHOT>                                         where moduleId = 900062011000036108           and referencedComponentId not in (select id from <PROSPECTIVE>.concept_<SNAPSHOT>                                                                          where moduleId in (select referencedComponentId from <PROSPECTIVE>.ssrefset_<SNAPSHOT>                                                                                             where active and moduleId =  900062011000036108 UNION select  900062011000036108                                                                                            )                                                                         );

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'07 Inferred relationships sourceId from AMT Module only depends on concepts from modules AMT Module depends on',
	null,
	null
from <PROSPECTIVE>.relationship_<SNAPSHOT>           where sourceId in (select id from <PROSPECTIVE>.concept_<SNAPSHOT> where moduleId =  900062011000036108)           and destinationId not in (select id from <PROSPECTIVE>.concept_<SNAPSHOT>                    where moduleId in (select referencedComponentId from <PROSPECTIVE>.ssrefset_<SNAPSHOT> where active and moduleId =  900062011000036108 UNION select  900062011000036108));

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'08 Inferred Relationships for concepts on AMT Module must be on modules that are dependent AMT Module',
	null,
	null
from <PROSPECTIVE>.relationship_<SNAPSHOT>           where sourceId in (select id from <PROSPECTIVE>.concept_<SNAPSHOT> where moduleId =  900062011000036108)           and moduleId not in (select referencedComponentId from <PROSPECTIVE>.ssrefset_<SNAPSHOT> where active and moduleId =  900062011000036108 UNION select  900062011000036108);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'09 Inferred Relationships on AMT Module must be for sourceIds from modules AMT Module dependens on',
	null,
	null
from <PROSPECTIVE>.relationship_<SNAPSHOT>           where moduleId = 900062011000036108                                         and sourceId not in (select id from <PROSPECTIVE>.concept_<SNAPSHOT>                                                                          where moduleId in (select referencedComponentId from <PROSPECTIVE>.ssrefset_<SNAPSHOT>                                                                                             where active and moduleId =  900062011000036108 UNION select  900062011000036108                                                                                            )                                                                         );

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'10 Inferred Relationships on AMT Module must be for destinationIds from modules AMT Module dependens on',
	null,
	null
from <PROSPECTIVE>.relationship_<SNAPSHOT>           where moduleId = 900062011000036108                                         and destinationId not in (select id from <PROSPECTIVE>.concept_<SNAPSHOT>                                                                          where moduleId in (select referencedComponentId from <PROSPECTIVE>.ssrefset_<SNAPSHOT>                                                                                             where active and moduleId =  900062011000036108 UNION select  900062011000036108                                                                                            )                                                                         );

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'11  Descriptions for concepts on AMT Module must be on modules that are dependent AMT Module, excluding concepts previously on dependent modules.',
	null,
	null
from <PROSPECTIVE>.description_<SNAPSHOT>           where conceptId in (select id from <PROSPECTIVE>.concept_<SNAPSHOT> where moduleId =  900062011000036108)           and moduleId not in (select moduleId from <PROSPECTIVE>.ssrefset_<SNAPSHOT> where active and referencedComponentId =  900062011000036108 UNION select  900062011000036108)                                         and conceptId not in (select distinct id from <PROSPECTIVE>.concept_<FULL>                                                              where id in (select id from <PROSPECTIVE>.concept_<SNAPSHOT> where moduleId =  900062011000036108)                                                             and moduleId != 900062011000036108                                                             and moduleId in (select referencedComponentId from <PROSPECTIVE>.ssrefset_<SNAPSHOT> where active and moduleId = 900062011000036108));

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'12  Descriptions on AMT Module must be for concepts that AMT Module depends on',
	null,
	null
from <PROSPECTIVE>.description_<SNAPSHOT>           where moduleId = 900062011000036108           and conceptId not in (select id from <PROSPECTIVE>.concept_<SNAPSHOT>                                                                                  where moduleId in (select referencedComponentId from <PROSPECTIVE>.ssrefset_<SNAPSHOT>                                                                                                          where active and moduleId = 900062011000036108                                      UNION select 900062011000036108));

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'13 ADRS Language preferences for descriptions on AMT Module must be on modules that are dependent AMT Module',
	null,
	null
from <PROSPECTIVE>.crefset_<SNAPSHOT>           where refsetId =  900000000000506000           and referencedComponentId in (select id from <PROSPECTIVE>.description_<SNAPSHOT> where moduleId =  900062011000036108)           and moduleId not in (select moduleId from <PROSPECTIVE>.ssrefset_<SNAPSHOT> where active and referencedComponentId =  900062011000036108 UNION select  900062011000036108);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'14 ADRS Language preferences on AMT Module must be for descriptions on modules AMT Module dependens on',
	null,
	null
from <PROSPECTIVE>.crefset_<SNAPSHOT>                                         where moduleId = 900062011000036108                                         and refsetId =  900000000000506000                                         and referencedComponentId not in (select id from <PROSPECTIVE>.description_<SNAPSHOT>                                                                                  where moduleId in (select moduleId from <PROSPECTIVE>.ssrefset_<SNAPSHOT>                                                                                                          where active and referencedComponentId = 900062011000036108 UNION select 900062011000036108));

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'15 Simple Type Refset memberships for concepts on AMT Module must be on modules that are dependent AMT Module',
	null,
	null
from <PROSPECTIVE>.simplerefset_<SNAPSHOT>           where referencedComponentId in (select id from <PROSPECTIVE>.concept_<SNAPSHOT> where moduleId =  900062011000036108)           and moduleId not in (select moduleId from <PROSPECTIVE>.ssrefset_<SNAPSHOT> where active and referencedComponentId =  900062011000036108 UNION select  900062011000036108);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'16 Simple Type Refset memberships on AMT Module must be for concepts on modules AMT Module dependens on',
	null,
	null
from <PROSPECTIVE>.simplerefset_<SNAPSHOT>                                         where moduleId = 900062011000036108                                         and referencedComponentId not in (select id from <PROSPECTIVE>.concept_<SNAPSHOT>                                                                                  where moduleId in (select moduleId from <PROSPECTIVE>.ssrefset_<SNAPSHOT>                                                                                                          where active and referencedComponentId = 900062011000036108 UNION select 900062011000036108));

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'17 cRefset memberships for concepts on AMT Module must be on modules that are dependent AMT Module',
	null,
	null
from <PROSPECTIVE>.crefset_<SNAPSHOT>                                         where referencedComponentId in (select id from <PROSPECTIVE>.concept_<SNAPSHOT> where moduleId = 900062011000036108)                                         and moduleId not in (select moduleId from <PROSPECTIVE>.ssrefset_<SNAPSHOT> where active and referencedComponentId = 900062011000036108 UNION select 900062011000036108)                                         and referencedComponentId not in (select distinct id from <PROSPECTIVE>.concept_<FULL>                                                              where id in (select id from <PROSPECTIVE>.concept_<SNAPSHOT> where moduleId =  900062011000036108)                                                             and moduleId != 900062011000036108                                                             and moduleId in (select referencedComponentId from <PROSPECTIVE>.ssrefset_<SNAPSHOT> where active and moduleId = 900062011000036108));

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'18 cRefset memberships on AMT Module must be for concepts on modules AMT Module dependens on',
	null,
	null
from <PROSPECTIVE>.crefset_<SNAPSHOT>                                         where moduleId = 900062011000036108                                         and referencedComponentId in (select id from <PROSPECTIVE>.concept_<SNAPSHOT>)                                         and referencedComponentId not in ( select id from <PROSPECTIVE>.concept_<SNAPSHOT>                           where moduleId in (select moduleId from <PROSPECTIVE>.ssrefset_<SNAPSHOT>                                 where active and referencedComponentId = 900062011000036108 UNION select 900062011000036108));

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'19 cRefset memberships for descriptions on AMT Module must be on modules that are dependent AMT Module',
	null,
	null
from <PROSPECTIVE>.crefset_<SNAPSHOT>           where referencedComponentId in (select id from <PROSPECTIVE>.description_<SNAPSHOT> where moduleId =  900062011000036108)           and moduleId not in (select moduleId from <PROSPECTIVE>.ssrefset_<SNAPSHOT> where active and referencedComponentId =  900062011000036108 UNION select  900062011000036108)                                         and referencedComponentId not in (select distinct id from <PROSPECTIVE>.description_<FULL>                                                              where id in (select id from <PROSPECTIVE>.description_<SNAPSHOT> where moduleId =  900062011000036108)                                                             and moduleId != 900062011000036108                                                             and moduleId in (select referencedComponentId from <PROSPECTIVE>.ssrefset_<SNAPSHOT> where active and moduleId = 900062011000036108));

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'20 cRefset memberships on AMT Module must be for descriptions on modules AMT Module depends on',
	null,
	null
from <PROSPECTIVE>.crefset_<SNAPSHOT>                                         where moduleId = 900062011000036108                                         and referencedComponentId in (select id from <PROSPECTIVE>.description_<SNAPSHOT>)                                         and referencedComponentId not in ( select id from <PROSPECTIVE>.description_<SNAPSHOT>                           where moduleId in (select referencedComponentId from <PROSPECTIVE>.ssrefset_<SNAPSHOT>                                 where active and moduleId = 900062011000036108 UNION select 900062011000036108));

