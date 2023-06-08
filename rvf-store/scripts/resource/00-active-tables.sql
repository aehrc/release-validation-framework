/**
 * Creates a bunch of pre-coordinated tables with all the ACTIVE components in the SNAPSHOT
 */

DROP TABLE IF EXISTS concept_active;
CREATE TABLE concept_active ENGINE=MyISAM DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci AS
SELECT * FROM concept_<SNAPSHOT> AS concepts WHERE active = 1;
create unique index concept_active_id_ix on concept_active(id);
create index concept_active_effectivetime_ix on concept_active(effectivetime);
create index concept_active_definitionstatusid_ix on concept_active(definitionstatusid);
create index concept_active_moduleid_ix on concept_active(moduleid);
create index concept_active_active_ix on concept_active(active);

DROP TABLE IF EXISTS description_active;
CREATE TABLE description_active ENGINE=MyISAM DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci AS
SELECT * FROM description_<SNAPSHOT> AS descriptions WHERE active = 1;
create unique index description_active_id_ix on description_active(id);
create index description_active_casesignificanceid_ix on description_active(casesignificanceid);
create index description_active_conceptid_ix on description_active(conceptid);
create index description_active_effectivetime_ix on description_active(effectivetime);
create index description_active_moduleid_ix on description_active(moduleid);
create index description_active_term_ix on description_active(term(256));
create fulltext index description_active_term_ftix on description_active(term(256));
create index description_active_typeid_ix on description_active(typeid);
create index description_active_active_ix on description_active(active);
create index description_active_languagecode_ix on description_active(languagecode);

DROP TABLE IF EXISTS relationship_active;
CREATE TABLE relationship_active ENGINE=MyISAM DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci AS
SELECT * FROM relationship_<SNAPSHOT> AS relationships WHERE active = 1;
Create unique index relationship_active_id_idx on relationship_active(id);
Create index relationship_active_effectivetime_idx on relationship_active(effectivetime);
Create index relationship_active_moduleid_idx on relationship_active(moduleid);
Create index relationship_active_sourceid_idx on relationship_active(sourceid);
Create index relationship_active_destinationid_idx on relationship_active(destinationid);
Create index relationship_active_relationshipgroup_idx on relationship_active(relationshipgroup);
Create index relationship_active_typeid_idx on relationship_active(typeid);
Create index relationship_active_characteristictypeid_idx on relationship_active(characteristictypeid);
Create index relationship_active_modifierid_idx on relationship_active(modifierid);
Create index relationship_active_active_idx on relationship_active(active);

DROP TABLE IF EXISTS  simplerefset_active;
CREATE TABLE simplerefset_active ENGINE=MyISAM DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci AS
SELECT * FROM simplerefset_<SNAPSHOT> refset WHERE ACTIVE = 1;
create unique index simplerefset_active_id_ix on simplerefset_active(id);
create index simplerefset_active_effectivetime_ix on simplerefset_active(effectivetime);
create index simplerefset_active_moduleid_ix on simplerefset_active(moduleid);
create index simplerefset_active_referencedComponentId_ix on simplerefset_active(referencedComponentId);
create index simplerefset_active_refsetid_ix on simplerefset_active(refsetid);
create index simplerefset_active_active_ix on simplerefset_active(active);

DROP TABLE IF EXISTS relationship_concrete_values_active;
CREATE TABLE relationship_concrete_values_active ENGINE=MyISAM DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci AS
SELECT * FROM relationship_concrete_values_<SNAPSHOT> AS relationships WHERE active = 1;
Create unique index relconvals_active_id_idx on relationship_concrete_values_active(id);
Create index relconvals_active_effectivetime_idx on relationship_concrete_values_active(effectivetime);
Create index relconvals_active_moduleid_idx on relationship_concrete_values_active(moduleid);
Create index relconvals_active_sourceid_idx on relationship_concrete_values_active(sourceid);
Create index relconvals_active_value_idx on relationship_concrete_values_active(value);
Create index relconvals_active_relationshipgroup_idx on relationship_concrete_values_active(relationshipgroup);
Create index relconvals_active_typeid_idx on relationship_concrete_values_active(typeid);
Create index relconvals_active_characteristictypeid_idx on relationship_concrete_values_active(characteristictypeid);
Create index relconvals_active_modifierid_idx on relationship_concrete_values_active(modifierid);
Create index relconvals_active_active_idx on relationship_concrete_values_active(active);

drop table if exists langrefset_active;
CREATE TABLE langrefset_active ENGINE=MyISAM DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci AS
SELECT * FROM langrefset_<SNAPSHOT> where active = 1;
create index langrefset_active_idx_acceptabilityid on langrefset_active (acceptabilityid);
create index langrefset_active_idx_active on langrefset_active (active);
create index langrefset_active_idx_effectivetime on langrefset_active (effectivetime);
create index langrefset_active_idx_id on langrefset_active (id);
create index langrefset_active_idx_moduleid on langrefset_active (moduleid);
create index langrefset_active_idx_referencedcomponentid on langrefset_active (referencedcomponentid);
create index langrefset_active_idx_refsetid on langrefset_active (refsetid);
