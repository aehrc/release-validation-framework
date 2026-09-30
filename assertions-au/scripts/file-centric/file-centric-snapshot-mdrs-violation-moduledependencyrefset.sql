/******************************************************************************** 
file-centric-snapshot-mdrs-violation-moduledependencyrefset.sql
	Assertion:
        "The moduleId,effectiveTime pair should always respect the MDRS."

	AU PATCH (assertions-au): the third statement checks ACTIVE rows only. A
	retired MDRS row names a module that is, by its retirement, no longer
	declared; the snapshot must keep it.
********************************************************************************/

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
select
  <RUNID>,
  '<ASSERTIONUUID>',
  a.referencedcomponentid,
  concat('moduledependencyrefset: Component effective time: ', a.effectivetime, ' must not be later than effective time: ', m.targeteffectivetime, ' of included module ', a.moduleid, ' as per MDRS'),
  a.id,
  'curr_moduledependencyrefset_s'
from curr_moduledependencyrefset_s a, curr_moduledependencyrefset_s m
where m.moduleid = '<MODULEID>' and m.effectivetime = '<VERSION>' and m.active = 1 and a.moduleid = m.referencedcomponentid and (a.effectivetime > m.targeteffectivetime);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
select
  <RUNID>,
  '<ASSERTIONUUID>',
  a.referencedcomponentid,
  concat('moduledependencyrefset: Component effective time: ', a.effectivetime, ' must not be later than effective time: ', m.sourceeffectivetime, ' of Edition module ', a.moduleid, ' as per MDRS'),
  a.id,
  'curr_moduledependencyrefset_s'
from curr_moduledependencyrefset_s a, curr_moduledependencyrefset_s m
where m.moduleid = '<MODULEID>' and m.effectivetime = '<VERSION>' and m.active = 1 and a.moduleid = m.moduleid and (a.effectivetime > m.sourceeffectivetime);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
select
  <RUNID>,
  '<ASSERTIONUUID>',
  a.moduleid,
  concat('moduledependencyrefset: Component moduleId: ', a.moduleid, ' is not a valid module defined as per MDRS'),
  a.id,
  'curr_moduledependencyrefset_s'
from curr_moduledependencyrefset_s a
where a.active = 1 and a.moduleid not in (
  select m.moduleid from curr_moduledependencyrefset_s m where m.active = 1 union select n.referencedcomponentid from curr_moduledependencyrefset_s n where n.active = 1);
