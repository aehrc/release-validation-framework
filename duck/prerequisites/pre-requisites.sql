DELIMITER //
SET default_storage_engine=MYISAM//
DROP TABLE IF EXISTS concept_active//
CREATE TABLE concept_active ENGINE=MyISAM DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci AS
SELECT * FROM concept_<SNAPSHOT> AS concepts WHERE active = 1//
create unique index concept_active_id_ix on concept_active(id)//
create index concept_active_effectivetime_ix on concept_active(effectivetime)//
create index concept_active_definitionstatusid_ix on concept_active(definitionstatusid)//
create index concept_active_moduleid_ix on concept_active(moduleid)//
create index concept_active_active_ix on concept_active(active)//

DROP TABLE IF EXISTS description_active//
CREATE TABLE description_active ENGINE=MyISAM DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci AS
SELECT * FROM description_<SNAPSHOT> AS descriptions WHERE active = 1//
create unique index description_active_id_ix on description_active(id)//
create index description_active_casesignificanceid_ix on description_active(casesignificanceid)//
create index description_active_conceptid_ix on description_active(conceptid)//
create index description_active_effectivetime_ix on description_active(effectivetime)//
create index description_active_moduleid_ix on description_active(moduleid)//
create index description_active_term_ix on description_active(term(333))//
create fulltext index description_active_term_ftix on description_active(term(333))//
create index description_active_typeid_ix on description_active(typeid)//
create index description_active_active_ix on description_active(active)//
create index description_active_languagecode_ix on description_active(languagecode)//

DROP TABLE IF EXISTS relationship_active//
CREATE TABLE relationship_active ENGINE=MyISAM DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci AS
SELECT * FROM relationship_<SNAPSHOT> AS relationships WHERE active = 1//
Create unique index relationship_active_id_idx on relationship_active(id)//
Create index relationship_active_effectivetime_idx on relationship_active(effectivetime)//
Create index relationship_active_moduleid_idx on relationship_active(moduleid)//
Create index relationship_active_sourceid_idx on relationship_active(sourceid)//
Create index relationship_active_destinationid_idx on relationship_active(destinationid)//
Create index relationship_active_relationshipgroup_idx on relationship_active(relationshipgroup)//
Create index relationship_active_typeid_idx on relationship_active(typeid)//
Create index relationship_active_characteristictypeid_idx on relationship_active(characteristictypeid)//
Create index relationship_active_modifierid_idx on relationship_active(modifierid)//
Create index relationship_active_active_idx on relationship_active(active)//

DROP TABLE IF EXISTS  simplerefset_active//
CREATE TABLE simplerefset_active ENGINE=MyISAM DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci AS
SELECT * FROM simplerefset_<SNAPSHOT> refset WHERE ACTIVE = 1//
create unique index simplerefset_active_id_ix on simplerefset_active(id)//
create index simplerefset_active_effectivetime_ix on simplerefset_active(effectivetime)//
create index simplerefset_active_moduleid_ix on simplerefset_active(moduleid)//
create index simplerefset_active_referencedComponentId_ix on simplerefset_active(referencedComponentId)//
create index simplerefset_active_refsetid_ix on simplerefset_active(refsetid)//
create index simplerefset_active_active_ix on simplerefset_active(active)//

DROP TABLE IF EXISTS relationship_concrete_values_active//
CREATE TABLE relationship_concrete_values_active ENGINE=MyISAM DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci AS
SELECT * FROM relationship_concrete_values_<SNAPSHOT> AS relationships WHERE active = 1//
Create unique index relconvals_active_id_idx on relationship_concrete_values_active(id)//
Create index relconvals_active_effectivetime_idx on relationship_concrete_values_active(effectivetime)//
Create index relconvals_active_moduleid_idx on relationship_concrete_values_active(moduleid)//
Create index relconvals_active_sourceid_idx on relationship_concrete_values_active(sourceid)//

Create index relconvals_active_relationshipgroup_idx on relationship_concrete_values_active(relationshipgroup)//
Create index relconvals_active_typeid_idx on relationship_concrete_values_active(typeid)//
Create index relconvals_active_characteristictypeid_idx on relationship_concrete_values_active(characteristictypeid)//
Create index relconvals_active_modifierid_idx on relationship_concrete_values_active(modifierid)//
Create index relconvals_active_active_idx on relationship_concrete_values_active(active)//

drop table if exists langrefset_active//
CREATE TABLE langrefset_active ENGINE=MyISAM DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci AS
SELECT * FROM langrefset_<SNAPSHOT> where active = 1//
create index langrefset_active_idx_acceptabilityid on langrefset_active (acceptabilityid)//
create index langrefset_active_idx_active on langrefset_active (active)//
create index langrefset_active_idx_effectivetime on langrefset_active (effectivetime)//
create index langrefset_active_idx_id on langrefset_active (id)//
create index langrefset_active_idx_moduleid on langrefset_active (moduleid)//
create index langrefset_active_idx_referencedcomponentid on langrefset_active (referencedcomponentid)//
create index langrefset_active_idx_refsetid on langrefset_active (refsetid)//

drop table if exists attributevaluemap_active//
CREATE TABLE attributevaluemap_active ENGINE=MyISAM DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci AS
SELECT * FROM attributevaluemap_<SNAPSHOT> where active = 1//
create index attributevaluemap_idx_id on attributevaluemap_active(id)//
create index attributevaluemap_idx_effectivetime on attributevaluemap_active(effectivetime)//
create index attributevaluemap_idx_active on attributevaluemap_active(active)//
create index attributevaluemap_idx_moduleid on attributevaluemap_active(moduleid)//
create index attributevaluemap_idx_refsetid on attributevaluemap_active(refsetid)//
create index attributevaluemap_idx_referencedcomponentid on attributevaluemap_active(referencedcomponentid)//
create index attributevaluemap_idx_maptarget on attributevaluemap_active(maptarget)//

drop table if exists extendedassociation_active//
CREATE TABLE extendedassociation_active ENGINE=MyISAM DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci AS
SELECT * FROM extendedassociation_<SNAPSHOT> where active = 1//
create index extendedassociation_idx_id on extendedassociation_active(id)//
create index extendedassociation_idx_effectivetime on extendedassociation_active(effectivetime)//
create index extendedassociation_idx_active on extendedassociation_active(active)//
create index extendedassociation_idx_moduleid on extendedassociation_active(moduleid)//
create index extendedassociation_idx_refsetid on extendedassociation_active(refsetid)//
create index extendedassociation_idx_referencedcomponentid on extendedassociation_active(referencedcomponentid)//
create index extendedassociation_idx_targetcomponentid on extendedassociation_active(targetcomponentid)//
create index extendedassociation_idx_value on extendedassociation_active(value)//

drop table if exists identifier_active//
CREATE TABLE identifier_active ENGINE=MyISAM DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci AS
SELECT * FROM identifier_<SNAPSHOT> where active = 1//
create index identifier_idx_identifierschemeid on identifier_active(identifierschemeid)//
create index identifier_idx_alternateidentifier on identifier_active(alternateidentifier)//
create index identifier_idx_effectivetime on identifier_active(effectivetime)//
create index identifier_idx_active on identifier_active(active)//
create index identifier_idx_moduleid on identifier_active(moduleid)//
create index identifier_idx_referencedcomponentid on identifier_active(referencedcomponentid)//

drop table if exists isimplemaprefset_active//
CREATE TABLE isimplemaprefset_active ENGINE=MyISAM DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci AS
SELECT * FROM isimplemaprefset_<SNAPSHOT> where active = 1//
create index isimplemaprefset_idx_id on isimplemaprefset_active(id)//
create index isimplemaprefset_idx_effectivetime on isimplemaprefset_active(effectivetime)//
create index isimplemaprefset_idx_active on isimplemaprefset_active(active)//
create index isimplemaprefset_idx_moduleid on isimplemaprefset_active(moduleid)//
create index isimplemaprefset_idx_refsetid on isimplemaprefset_active(refsetid)//
create index isimplemaprefset_idx_referencedcomponentid on isimplemaprefset_active(referencedcomponentid)//
create index isimplemaprefset_idx_maptarget on isimplemaprefset_active(maptarget)//

drop table if exists ccsrefset_active//
CREATE TABLE ccsrefset_active ENGINE=MyISAM DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci AS
SELECT * FROM ccsrefset_<SNAPSHOT> where active = 1//
create index ccsrefset_idx_id on ccsrefset_active(id)//
create index ccsrefset_idx_effectivetime on ccsrefset_active(effectivetime)//
create index ccsrefset_idx_active on ccsrefset_active(active)//
create index ccsrefset_idx_moduleid on ccsrefset_active(moduleid)//
create index ccsrefset_idx_refsetid on ccsrefset_active(refsetid)//
create index ccsrefset_idx_referencedcomponentid on ccsrefset_active(referencedcomponentid)//
create index ccsrefset_idx_componentid1 on ccsrefset_active(componentid1)//
create index ccsrefset_idx_componentid2 on ccsrefset_active(componentid2)//
create index ccsrefset_idx_value on ccsrefset_active(value)//

drop table if exists owlexpressionrefset_active//
CREATE TABLE owlexpressionrefset_active ENGINE=MyISAM DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci AS
SELECT * FROM owlexpressionrefset_<SNAPSHOT> WHERE active = 1//
create unique index owlexpressionrefset_active_id_ix on owlexpressionrefset_active(id)//
create index owlexpressionrefset_active_referencedcomponentid_ix on owlexpressionrefset_active(referencedcomponentid)//
create index owlexpressionrefset_active_active_ix on owlexpressionrefset_active(active)//

drop table if exists ccirefset_active//
CREATE TABLE ccirefset_active ENGINE=MyISAM DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci AS
SELECT * FROM ccirefset_<SNAPSHOT> where active = 1//
create index ccirefset_idx_id on ccirefset_active(id)//
create index ccirefset_idx_effectivetime on ccirefset_active(effectivetime)//
create index ccirefset_idx_active on ccirefset_active(active)//
create index ccirefset_idx_moduleid on ccirefset_active(moduleid)//
create index ccirefset_idx_refsetid on ccirefset_active(refsetid)//
create index ccirefset_idx_referencedcomponentid on ccirefset_active(referencedcomponentid)//
create index ccirefset_idx_componentid1 on ccirefset_active(componentid1)//
create index ccirefset_idx_componentid2 on ccirefset_active(componentid2)//
create index ccirefset_idx_value on ccirefset_active(value)//

drop function if exists get_cr_ADRS_PT//
create function get_cr_ADRS_PT(candidate bigint) returns varchar(4500) DETERMINISTIC
BEGIN RETURN (SELECT da.term FROM langrefset_active as lr join description_active as da
                                                               on da.id = lr.referencedcomponentid WHERE lr.refsetId = 32570271000036106 and da.typeId = 900000000000013009 and lr.acceptabilityid = 900000000000548007 and conceptid = candidate limit 1); END//

drop function if exists get_cr_FSN//
create function get_cr_FSN(candidate bigint) returns varchar(4500) DETERMINISTIC
BEGIN RETURN (SELECT term FROM description_active where conceptId = candidate and typeId = 900000000000003001 and languageCode = 'en'); END//

drop function if exists get_cr_PercentDefined//
create function get_cr_PercentDefined(refset bigint) returns decimal(6, 4) DETERMINISTIC
BEGIN SET @refset = refset; SET @refsetSize = (select count(1) from simplerefset_active where refsetId = @refset); SET @definedCount = (select count(1) from concept_active where definitionStatusId = 900000000000073002 and id in (select referencedComponentId from simplerefset_active where refsetId = @refset));  RETURN CONVERT(@definedCount/ @refsetSize * 100,DECIMAL(6,4)); END//

drop function if exists isActiveConcept_cr//
-- SCTIDs exceed DOUBLE's exact-integer range. A varchar parameter compared
-- against a BIGINT id column makes MySQL evaluate the comparison as DOUBLE
-- (53-bit mantissa), so every id above 2^53 = 9007199254740992 silently
-- fails to match: 12% of active concepts, 34% of active relationships on
-- the AU daily build. bigint is what the rest of this file already uses
-- (isActiveMemberOf_cr_refset, isDescendentOf_cr), so this is an
-- inconsistency rather than a deliberate choice.
create function isActiveConcept_cr(candidate bigint) returns tinyint(1) DETERMINISTIC
BEGIN  RETURN (select count(1) from concept_active where id = candidate);  END//

drop function if exists isActiveMemberOf_cr_refset//
create function isActiveMemberOf_cr_refset(candidate bigint, targetRefset bigint) returns int DETERMINISTIC
BEGIN RETURN (select count(1) from simplerefset_active where refsetId = targetRefset and referencedComponentId = candidate); END//

drop function if exists isAncestorOf_cr//
create function isAncestorOf_cr(candidate bigint, target bigint) returns int DETERMINISTIC
BEGIN RETURN (select count(1) from transitiveClosureTable where destinationId = candidate AND sourceId = target); END//

drop function if exists isChildOf_cr//
create function isChildOf_cr(candidate bigint, target bigint) returns int DETERMINISTIC
BEGIN RETURN (select count(1) from relationship_active where sourceId = candidate AND destinationId = target AND typeId = 116680003); END//

drop function if exists isDescendentOf_cr//
create function isDescendentOf_cr(candidate bigint, target bigint) returns int DETERMINISTIC
BEGIN RETURN (select count(1) from transitiveClosureTable where sourceId = candidate AND destinationId = target); END//

drop function if exists isConcept_cr//
create function isConcept_cr(candidate bigint, target bigint) returns int DETERMINISTIC
BEGIN  DECLARE passValue int;  IF candidate = target THEN SET passValue = 1; ELSE SET passValue = 0; END IF;  RETURN passValue; END//

drop function if exists isKindOf_cr//
create function isKindOf_cr(candidate bigint, target bigint) returns int DETERMINISTIC
BEGIN RETURN isDescendentOf_cr(candidate,target)+isConcept_cr(candidate,target); END//

drop function if exists isPositiveInteger//
create function isPositiveInteger(candidate varchar(20)) returns tinyint(1) DETERMINISTIC
BEGIN CASE WHEN (SIGN(candidate) != 1) THEN RETURN FALSE; WHEN (ROUND(candidate) != candidate) THEN RETURN FALSE; ELSE RETURN TRUE; END CASE; END//

drop function if exists isValidComponentId_cr//
-- SCTIDs exceed DOUBLE's exact-integer range. A varchar parameter compared
-- against a BIGINT id column makes MySQL evaluate the comparison as DOUBLE
-- (53-bit mantissa), so every id above 2^53 = 9007199254740992 silently
-- fails to match: 12% of active concepts, 34% of active relationships on
-- the AU daily build. bigint is what the rest of this file already uses
-- (isActiveMemberOf_cr_refset, isDescendentOf_cr), so this is an
-- inconsistency rather than a deliberate choice.
create function isValidComponentId_cr(candidate bigint) returns tinyint(1) DETERMINISTIC
BEGIN declare x int;  IF (CHAR_LENGTH(candidate) <19) THEN CASE SUBSTRING(candidate, -2, 1) WHEN 0 THEN SET x = (select count(1) from concept_<SNAPSHOT> where id = candidate); WHEN 1 THEN	SET x = (select count(1) from (select * from description_<SNAPSHOT> where id = candidate UNION select * from textdefinition_<SNAPSHOT> where id = candidate) AS D); WHEN 2 THEN SET x = (select count(1) from relationship_<SNAPSHOT> where id = candidate); ELSE RETURN FALSE; END CASE; ELSE return false; END IF;  IF x > 0 THEN return true; ELSE return false; END IF;   END//

drop function if exists isValidModuleId//
-- SCTIDs exceed DOUBLE's exact-integer range. A varchar parameter compared
-- against a BIGINT id column makes MySQL evaluate the comparison as DOUBLE
-- (53-bit mantissa), so every id above 2^53 = 9007199254740992 silently
-- fails to match: 12% of active concepts, 34% of active relationships on
-- the AU daily build. bigint is what the rest of this file already uses
-- (isActiveMemberOf_cr_refset, isDescendentOf_cr), so this is an
-- inconsistency rather than a deliberate choice.
create function isValidModuleId(candidate bigint) returns tinyint(1) DETERMINISTIC
BEGIN  RETURN isDescendentOf_cr(candidate, 900000000000443000);  END//

drop function if exists isValidRefsetId_cr//
-- SCTIDs exceed DOUBLE's exact-integer range. A varchar parameter compared
-- against a BIGINT id column makes MySQL evaluate the comparison as DOUBLE
-- (53-bit mantissa), so every id above 2^53 = 9007199254740992 silently
-- fails to match: 12% of active concepts, 34% of active relationships on
-- the AU daily build. bigint is what the rest of this file already uses
-- (isActiveMemberOf_cr_refset, isDescendentOf_cr), so this is an
-- inconsistency rather than a deliberate choice.
create function isValidRefsetId_cr(candidate bigint) returns tinyint(1) DETERMINISTIC
BEGIN  RETURN isDescendentOf_cr(candidate, 900000000000455006);  END//

drop function if exists isValidTimeFormat//
create function isValidTimeFormat(candidate varchar(30)) returns tinyint(1) DETERMINISTIC
BEGIN  declare shortDate varchar(30); SET shortDate = DATE_FORMAT(candidate,'%Y%m%d');  CASE WHEN (CHAR_LENGTH(shortDate) != 8) THEN RETURN FALSE; WHEN (SUBSTRING(shortDate,1,4) < 2002 || SUBSTRING(shortDate,1,4) > 2099) THEN RETURN FALSE; WHEN (SUBSTRING(shortDate,5,2) < 1 || SUBSTRING(shortDate,5,2) > 12) THEN RETURN FALSE; WHEN (SUBSTRING(shortDate,7,2) < 1 || SUBSTRING(shortDate,7,2) > 31) THEN RETURN FALSE; WHEN (NOT isPositiveInteger(shortDate)) THEN RETURN FALSE; ELSE RETURN TRUE; END CASE;  END//

drop function if exists isValidUUID//
create function isValidUUID(candidate varchar(40)) returns tinyint(1) DETERMINISTIC
BEGIN IF (LENGTH(candidate) != 36) THEN RETURN FALSE; ELSEIF (SUBSTRING(candidate,9,1) != '-') THEN RETURN FALSE; ELSEIF (SUBSTRING(candidate,14,1) != '-') THEN RETURN FALSE; ELSEIF (SUBSTRING(candidate,19,1) != '-') THEN RETURN FALSE; ELSEIF (SUBSTRING(candidate,24,1) != '-') THEN RETURN FALSE; ELSE RETURN TRUE; END IF;  END//

DROP PROCEDURE IF EXISTS create_transitiveClosureTable//

CREATE PROCEDURE create_transitiveClosureTable()

BEGIN

DROP TABLE IF EXISTS transitiveClosureTable;

CREATE TABLE transitiveClosureTable (sourceid BIGINT NOT NULL , destinationid BIGINT NOT NULL , PRIMARY KEY (sourceid, destinationid) ) ENGINE=MyISAM DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;
INSERT INTO transitiveClosureTable (sourceid, destinationid)
SELECT distinct sourceid, destinationid from relationship_active where typeid = 116680003;


REPEAT

insert into transitiveClosureTable (sourceid, destinationid)
SELECT distinct b.sourceid, a.destinationid from transitiveClosureTable a
                                                     join transitiveClosureTable b on a.sourceid = b.destinationid
                                                     left join transitiveClosureTable c on c.sourceid = b.sourceid and c.destinationid = a.destinationid
where c.sourceid is null;
set @x = row_count();

    UNTIL @x = 0 END REPEAT;

create index idx_transitiveClosureTable_sourceid on transitiveClosureTable (sourceid);
create index idx_transitiveClosureTable_destinationid on transitiveClosureTable (destinationid);

create unique index uidx_transitiveClosureTable_sd on transitiveClosureTable (sourceId,destinationid);
create unique index uidx_transitiveClosureTable_ds on transitiveClosureTable (destinationid,sourceId);

END//
CALL create_transitiveClosureTable()//

